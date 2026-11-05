/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

/*
 * The demux, decode and filter steps follow archivetune_engine.c, without its ring, producer thread,
 * tempo stage or resampling to the output rate. A probe therefore hands back the samples the source
 * actually contains, which is what a spectral check has to measure.
 */

#include "archivetune_probe.h"

#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <libavcodec/avcodec.h>
#include <libavfilter/avfilter.h>
#include <libavfilter/buffersink.h>
#include <libavfilter/buffersrc.h>
#include <libavformat/avformat.h>
#include <libavutil/avutil.h>
#include <libavutil/channel_layout.h>
#include <libavutil/dict.h>
#include <libavutil/error.h>
#include <libavutil/frame.h>
#include <libavutil/mathematics.h>
#include <libavutil/samplefmt.h>

/* The analysis accepts one to eight channels, so a wider source is refused before anything is decoded. */
#define AE_PROBE_MAX_CHANNELS 8u
/* Bounds the packets one read may demux, so a source without enough audio cannot keep a probe busy. */
#define AE_PROBE_PACKET_BUDGET (1u << 18)
/*
 * Bounds one blocking protocol read. The interrupt callback is only consulted between transfers, so a socket that
 * accepts the connection and then sends nothing would otherwise hold the read (and the caller) forever.
 */
#define AE_PROBE_IO_TIMEOUT_US 5000000LL

struct ae_probe {
    _Atomic int interrupt; /* set by ae_probe_cancel and read by FFmpeg's interrupt callback */
    char codec_name[AE_PROBE_CODEC_NAME_CAPACITY];
};

/* Everything one read opens. It is owned by that read and closed before the read returns. */
typedef struct probe_session {
    AVFormatContext *format;
    AVCodecContext *decoder;
    AVFilterGraph *graph;
    AVFilterContext *buffer_src;
    AVFilterContext *buffer_sink;
    AVRational time_base;
    int64_t start_time;
    int stream_index;
    uint32_t channels;
    uint32_t sample_rate;
    uint32_t advertised_rate;
} probe_session;

static int probe_cancelled(ae_probe *probe) {
    return atomic_load(&probe->interrupt) != 0;
}

static int probe_interrupt_cb(void *opaque) {
    return probe_cancelled((ae_probe *)opaque);
}

static void set_message(char *error, size_t len, const char *message) {
    if (error && len > 0) {
        snprintf(error, len, "%s", message);
    }
}

static void set_ffmpeg_message(char *error, size_t len, const char *what, int code) {
    char detail[160];
    if (error && len > 0) {
        av_strerror(code, detail, sizeof detail);
        snprintf(error, len, "%s failed: %s", what, detail);
    }
}

/*
 * A read interrupted by ae_probe_cancel reports the cancellation, not the FFmpeg code the interrupt
 * produced. The caller must never mistake an interrupted read for a broken source.
 */
static ae_probe_status probe_fail(ae_probe *probe, char *error, size_t len, const char *what, int code) {
    if (probe_cancelled(probe)) {
        set_message(error, len, "the probe was cancelled");
        return AE_PROBE_CANCELLED;
    }
    set_ffmpeg_message(error, len, what, code);
    return AE_PROBE_FAILED;
}

static void probe_session_close(probe_session *s) {
    if (s->graph) {
        avfilter_graph_free(&s->graph);
    }
    if (s->decoder) {
        avcodec_free_context(&s->decoder);
    }
    if (s->format) {
        avformat_close_input(&s->format);
    }
}

static ae_probe_status probe_open(ae_probe *probe, probe_session *s, const char *source, uint64_t start_us,
                                  char *error, size_t len) {
    AVDictionary *options = NULL;
    const AVCodec *codec = NULL;
    AVStream *stream = NULL;
    int r;

    if (strncmp(source, "http://", 7) == 0 || strncmp(source, "https://", 8) == 0) {
        /* The app owns connection reuse, so FFmpeg keeps none of its own, as it does in the playback engine. */
        av_dict_set(&options, "multiple_requests", "0", 0);
        /* The protocol layer reads its deadline by option name; the format context has no timeout field. */
        av_dict_set_int(&options, "rw_timeout", AE_PROBE_IO_TIMEOUT_US, 0);
    }
    s->format = avformat_alloc_context();
    if (!s->format) {
        av_dict_free(&options);
        set_message(error, len, "out of memory");
        return AE_PROBE_FAILED;
    }
    s->format->interrupt_callback.callback = probe_interrupt_cb;
    s->format->interrupt_callback.opaque = probe;

    r = avformat_open_input(&s->format, source, NULL, &options);
    av_dict_free(&options);
    if (r < 0) {
        s->format = NULL; /* FFmpeg frees the context itself when opening fails */
        return probe_fail(probe, error, len, "open", r);
    }
    r = avformat_find_stream_info(s->format, NULL);
    if (r < 0) {
        return probe_fail(probe, error, len, "probe", r);
    }
    r = av_find_best_stream(s->format, AVMEDIA_TYPE_AUDIO, -1, -1, &codec, 0);
    if (r < 0 || !codec) {
        set_message(error, len, "the source has no decodable audio stream");
        return AE_PROBE_FAILED;
    }
    s->stream_index = r;
    stream = s->format->streams[r];
    s->time_base = stream->time_base;
    s->start_time = stream->start_time != AV_NOPTS_VALUE ? stream->start_time : 0;
    s->advertised_rate = stream->codecpar->sample_rate > 0 ? (uint32_t)stream->codecpar->sample_rate : 0u;
    snprintf(probe->codec_name, sizeof probe->codec_name, "%s", codec->name ? codec->name : "");

    s->decoder = avcodec_alloc_context3(codec);
    if (!s->decoder) {
        set_message(error, len, "out of memory");
        return AE_PROBE_FAILED;
    }
    r = avcodec_parameters_to_context(s->decoder, stream->codecpar);
    if (r >= 0) {
        r = avcodec_open2(s->decoder, codec, NULL);
    }
    if (r < 0) {
        return probe_fail(probe, error, len, "decoder", r);
    }
    if (s->decoder->ch_layout.nb_channels < 1 ||
        s->decoder->ch_layout.nb_channels > (int)AE_PROBE_MAX_CHANNELS) {
        set_message(error, len, "the source has a channel count the analysis does not support");
        return AE_PROBE_FAILED;
    }
    if (s->decoder->sample_rate <= 0) {
        set_message(error, len, "the decoder reported no sample rate");
        return AE_PROBE_FAILED;
    }
    s->channels = (uint32_t)s->decoder->ch_layout.nb_channels;
    s->sample_rate = (uint32_t)s->decoder->sample_rate;

    if (start_us > 0) {
        int64_t target = s->start_time + av_rescale_q((int64_t)start_us, AV_TIME_BASE_Q, s->time_base);
        r = av_seek_frame(s->format, s->stream_index, target, AVSEEK_FLAG_BACKWARD);
        if (r < 0) {
            return probe_fail(probe, error, len, "seek", r);
        }
        avcodec_flush_buffers(s->decoder);
    }
    return AE_PROBE_OK;
}

/*
 * Builds abuffer -> aformat(flt) -> abuffersink. Unlike the playback graph, it neither resamples nor changes
 * tempo, and it keeps the source's channel layout, so the samples stay at the rate the source reports.
 */
static ae_probe_status probe_build_graph(probe_session *s, char *error, size_t len) {
    const AVFilter *buffer = avfilter_get_by_name("abuffer");
    const AVFilter *format = avfilter_get_by_name("aformat");
    const AVFilter *sink = avfilter_get_by_name("abuffersink");
    AVBufferSrcParameters *params = NULL;
    AVFilterContext *ctx = NULL;
    int r;

    if (!buffer || !format || !sink) {
        set_message(error, len, "required FFmpeg filters are missing from the native build");
        return AE_PROBE_FAILED;
    }
    s->graph = avfilter_graph_alloc();
    if (!s->graph) {
        set_message(error, len, "out of memory");
        return AE_PROBE_FAILED;
    }
    /* The source must receive its parameters before init, because init validates the sample format. */
    s->buffer_src = avfilter_graph_alloc_filter(s->graph, buffer, "in");
    params = av_buffersrc_parameters_alloc();
    if (!s->buffer_src || !params) {
        av_freep(&params);
        set_message(error, len, "out of memory");
        return AE_PROBE_FAILED;
    }
    params->format = s->decoder->sample_fmt;
    params->time_base = s->time_base;
    params->sample_rate = s->decoder->sample_rate;
    r = av_channel_layout_copy(&params->ch_layout, &s->decoder->ch_layout);
    if (r >= 0) {
        r = av_buffersrc_parameters_set(s->buffer_src, params);
    }
    av_channel_layout_uninit(&params->ch_layout);
    av_freep(&params);
    if (r >= 0) {
        r = avfilter_init_str(s->buffer_src, NULL);
    }
    if (r < 0) {
        set_ffmpeg_message(error, len, "audio input", r);
        return AE_PROBE_FAILED;
    }

    /* Only the sample format changes. aformat without rate or layout arguments passes both through. */
    r = avfilter_graph_create_filter(&ctx, format, "format", "sample_fmts=flt", NULL, s->graph);
    if (r < 0 || avfilter_link(s->buffer_src, 0, ctx, 0) < 0) {
        set_ffmpeg_message(error, len, "output format", r < 0 ? r : AVERROR(EINVAL));
        return AE_PROBE_FAILED;
    }
    r = avfilter_graph_create_filter(&s->buffer_sink, sink, "out", NULL, NULL, s->graph);
    if (r < 0 || avfilter_link(ctx, 0, s->buffer_sink, 0) < 0) {
        set_ffmpeg_message(error, len, "output sink", r < 0 ? r : AVERROR(EINVAL));
        return AE_PROBE_FAILED;
    }
    r = avfilter_graph_config(s->graph, NULL);
    if (r < 0) {
        set_ffmpeg_message(error, len, "filter graph", r);
        return AE_PROBE_FAILED;
    }
    return AE_PROBE_OK;
}

/*
 * Moves every frame the sink has ready into out. Returns 0 when the sink is empty, 1 when out is full, or a
 * negative FFmpeg error code. Frames beyond capacity are dropped, so out never grows past its window.
 */
static int probe_drain_sink(AVFilterContext *sink, AVFrame *scratch, float *out, uint32_t channels,
                            uint32_t capacity_frames, uint32_t *produced) {
    for (;;) {
        int r = av_buffersink_get_frame(sink, scratch);
        if (r == AVERROR(EAGAIN) || r == AVERROR_EOF) {
            return 0;
        }
        if (r < 0) {
            return r;
        }
        if (scratch->ch_layout.nb_channels != (int)channels) {
            av_frame_unref(scratch);
            return AVERROR(EINVAL);
        }
        uint32_t room = capacity_frames - *produced;
        uint32_t available = (uint32_t)scratch->nb_samples;
        uint32_t take = available < room ? available : room;
        if (take > 0) {
            memcpy(out + (size_t)(*produced) * channels, scratch->data[0],
                   (size_t)take * channels * sizeof(float));
        }
        *produced += take;
        av_frame_unref(scratch);
        if (*produced >= capacity_frames) {
            return 1;
        }
    }
}

/*
 * Pulls every frame the decoder has ready through the graph into out. Returns 0 when the decoder needs
 * more input, 1 when out is full, or a negative FFmpeg error code.
 */
static int probe_receive_frames(probe_session *s, AVFrame *frame, AVFrame *scratch, float *out,
                                uint32_t capacity_frames, uint32_t *produced) {
    for (;;) {
        int r = avcodec_receive_frame(s->decoder, frame);
        if (r == AVERROR(EAGAIN) || r == AVERROR_EOF) {
            return 0;
        }
        if (r < 0) {
            return r;
        }
        if (frame->pts == AV_NOPTS_VALUE) {
            frame->pts = frame->best_effort_timestamp;
        }
        r = av_buffersrc_add_frame_flags(s->buffer_src, frame, 0);
        av_frame_unref(frame);
        if (r < 0) {
            return r;
        }
        r = probe_drain_sink(s->buffer_sink, scratch, out, s->channels, capacity_frames, produced);
        if (r != 0) {
            return r;
        }
    }
}

/*
 * Sends one packet to the decoder. Returns 0 when it was accepted, 1 when out filled while the decoder
 * was drained, or a negative code. A decoder that still refuses input after it was drained is stuck, and
 * that is reported as a failure rather than retried forever.
 */
static int probe_send_packet(probe_session *s, AVPacket *packet, AVFrame *frame, AVFrame *scratch, float *out,
                             uint32_t capacity_frames, uint32_t *produced) {
    int r = avcodec_send_packet(s->decoder, packet);
    if (r == AVERROR(EAGAIN)) {
        r = probe_receive_frames(s, frame, scratch, out, capacity_frames, produced);
        if (r != 0) {
            return r;
        }
        r = avcodec_send_packet(s->decoder, packet);
        if (r == AVERROR(EAGAIN)) {
            return AVERROR(EAGAIN);
        }
    }
    return r < 0 ? r : 0;
}

/*
 * Runs the demux, decode and drain loop for an open session. Returns the status and, on success, fills
 * info. The packet and frame buffers belong to the caller.
 */
static ae_probe_status probe_run(ae_probe *probe, probe_session *s, AVPacket *packet, AVFrame *frame,
                                 AVFrame *scratch, float *out, uint32_t capacity_frames, ae_probe_info *info,
                                 char *error, size_t len) {
    ae_probe_status status = AE_PROBE_OK;
    uint32_t produced = 0;
    uint32_t packets = 0;
    int input_done = 0;
    int full = 0;
    int r;

    while (!full) {
        if (probe_cancelled(probe)) {
            set_message(error, len, "the probe was cancelled");
            status = AE_PROBE_CANCELLED;
            break;
        }
        if (!input_done) {
            r = av_read_frame(s->format, packet);
            if (r == AVERROR_EOF) {
                input_done = 1;
                r = avcodec_send_packet(s->decoder, NULL); /* starts the decoder's flush */
                if (r < 0 && r != AVERROR_EOF) {
                    status = probe_fail(probe, error, len, "decode", r);
                    break;
                }
            } else if (r < 0) {
                status = probe_fail(probe, error, len, "read", r);
                break;
            } else {
                if (++packets > AE_PROBE_PACKET_BUDGET) {
                    av_packet_unref(packet);
                    set_message(error, len, "the source did not yield enough audio within the probe budget");
                    status = AE_PROBE_FAILED;
                    break;
                }
                if (packet->stream_index == s->stream_index) {
                    r = probe_send_packet(s, packet, frame, scratch, out, capacity_frames, &produced);
                    if (r == 1) {
                        av_packet_unref(packet);
                        full = 1;
                        break;
                    }
                    if (r < 0) {
                        av_packet_unref(packet);
                        status = probe_fail(probe, error, len, "decode", r);
                        break;
                    }
                }
                av_packet_unref(packet);
            }
        }
        r = probe_receive_frames(s, frame, scratch, out, capacity_frames, &produced);
        if (r == 1) {
            full = 1;
            break;
        }
        if (r < 0) {
            status = probe_fail(probe, error, len, "decode", r);
            break;
        }
        if (input_done) {
            /* The graph can hold a tail back until an end-of-stream marker releases it. */
            r = av_buffersrc_add_frame_flags(s->buffer_src, NULL, 0);
            if (r >= 0) {
                r = probe_drain_sink(s->buffer_sink, scratch, out, s->channels, capacity_frames, &produced);
            }
            if (r == 1) {
                full = 1;
            } else if (r < 0) {
                status = probe_fail(probe, error, len, "audio output", r);
            }
            break;
        }
    }

    if (status == AE_PROBE_OK) {
        info->frames = produced;
        info->channels = s->channels;
        info->sample_rate = s->sample_rate;
        info->advertised_sample_rate = s->advertised_rate;
        info->end_of_source = input_done && !full ? 1u : 0u;
    }
    return status;
}

static ae_probe_status probe_decode(ae_probe *probe, probe_session *s, float *out, size_t capacity_samples,
                                    uint32_t max_frames, ae_probe_info *info, char *error, size_t len) {
    AVPacket *packet = NULL;
    AVFrame *frame = NULL;
    AVFrame *scratch = NULL;
    ae_probe_status status;
    size_t fit = capacity_samples / s->channels;
    uint32_t capacity_frames;

    if (fit > max_frames) {
        fit = max_frames;
    }
    capacity_frames = (uint32_t)fit;
    if (capacity_frames == 0) {
        set_message(error, len, "the output buffer holds no whole frame");
        return AE_PROBE_INVALID;
    }

    packet = av_packet_alloc();
    frame = av_frame_alloc();
    scratch = av_frame_alloc();
    if (!packet || !frame || !scratch) {
        set_message(error, len, "out of memory");
        status = AE_PROBE_FAILED;
    } else {
        status = probe_run(probe, s, packet, frame, scratch, out, capacity_frames, info, error, len);
    }
    av_packet_free(&packet);
    av_frame_free(&frame);
    av_frame_free(&scratch);
    return status;
}

ae_probe *ae_probe_create(void) {
    ae_probe *probe = calloc(1, sizeof *probe);
    if (probe) {
        atomic_init(&probe->interrupt, 0);
    }
    return probe;
}

void ae_probe_destroy(ae_probe *probe) {
    free(probe);
}

void ae_probe_cancel(ae_probe *probe) {
    if (probe) {
        atomic_store(&probe->interrupt, 1);
    }
}

ae_probe_status ae_probe_read(ae_probe *probe, const char *source, uint64_t start_us, uint32_t max_frames,
                              float *out, size_t out_capacity_samples, ae_probe_info *info, char *error,
                              size_t error_len) {
    probe_session session;
    ae_probe_status status;

    if (error && error_len > 0) {
        error[0] = '\0';
    }
    if (info) {
        memset(info, 0, sizeof *info);
    }
    if (!probe || !source || !out || !info || max_frames == 0 || out_capacity_samples == 0) {
        set_message(error, error_len, "invalid probe arguments");
        return AE_PROBE_INVALID;
    }
    memset(&session, 0, sizeof session);
    session.stream_index = -1;
    probe->codec_name[0] = '\0';

    status = probe_open(probe, &session, source, start_us, error, error_len);
    if (status == AE_PROBE_OK) {
        status = probe_build_graph(&session, error, error_len);
    }
    if (status == AE_PROBE_OK) {
        status = probe_decode(probe, &session, out, out_capacity_samples, max_frames, info, error, error_len);
    }
    probe_session_close(&session);
    return status;
}

size_t ae_probe_codec_name(ae_probe *probe, char *buffer, size_t buffer_len) {
    if (!probe || !buffer || buffer_len == 0) {
        return 0;
    }
    snprintf(buffer, buffer_len, "%s", probe->codec_name);
    return strlen(buffer);
}
