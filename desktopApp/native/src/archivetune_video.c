/*
 * Desktop video artwork reader.
 *
 * One reader thread owns every FFmpeg object of a reader. It opens the video-only stream, decodes it and converts
 * the frame that matches the host clock to BGRA. The host clock is an anchor (position, monotonic instant, speed),
 * so the picture keeps time between updates without a callback per frame. A frame behind the clock is dropped, a
 * frame ahead of it waits, and a clock that moves far from the picture seeks instead of decoding through the gap.
 * A paused or inactive reader sleeps on its condition until the host changes something.
 */

#include "archivetune_video.h"

#include <errno.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/avutil.h>
#include <libavutil/dict.h>
#include <libavutil/error.h>
#include <libavutil/frame.h>
#include <libavutil/mathematics.h>
#include <libavutil/pixfmt.h>
#include <libswscale/swscale.h>

#define AV_ERROR_CAPACITY 256u
/* A frame this far behind the clock is dropped rather than shown late. */
#define AV_LATE_US 60000LL
/* A frame this close ahead of the clock is shown at once. */
#define AV_EARLY_US 5000LL
/* The clock this far ahead of the picture seeks forward instead of decoding through the gap. */
#define AV_BEHIND_RESYNC_US 2000000LL
/* The clock this far behind the picture seeks back. */
#define AV_AHEAD_RESYNC_US 500000LL
/* A seek this far past the frame being caught up to replaces that catch-up. */
#define AV_CATCH_UP_LIMIT_US 10000000LL
/* A position below this needs no seek when nothing has been decoded yet. */
#define AV_START_SEEK_US 1000000LL
/* Nothing shown for this long shows a late frame anyway, so a slow decoder still moves the picture. */
#define AV_STARVED_NS 500000000LL
/* The longest sleep before the clock is read again, which bounds the reaction to a seek or a close. */
#define AV_MAX_WAIT_NS 50000000LL
/* The fallback frame step for a stream without timestamps. */
#define AV_FALLBACK_STEP_US 33333LL
/* Two decoder threads keep a 1080p stream real time without claiming every core for artwork. */
#define AV_DECODE_THREADS 2

struct ae_video {
    pthread_mutex_t mutex;
    pthread_cond_t changed;
    pthread_t thread;
    int thread_started;
    atomic_int stop;

    char *url;
    char *user_agent;
    int32_t max_height;

    /* Everything below is guarded by mutex. */
    int64_t anchor_us;
    int64_t anchor_ns;
    int playing;
    double speed;
    uint64_t generation;
    int active;

    uint8_t *front;
    size_t front_capacity;
    int32_t width;
    int32_t height;
    int64_t pts_us;
    int64_t duration_us;
    uint64_t sequence;
    int32_t state;
    char error[AV_ERROR_CAPACITY];
};

typedef struct clock_view {
    int64_t position_us;
    int playing;
    double speed;
    uint64_t generation;
    int active;
} clock_view;

static int64_t monotonic_ns(void) {
    struct timespec now;
    clock_gettime(CLOCK_MONOTONIC, &now);
    return (int64_t)now.tv_sec * 1000000000LL + (int64_t)now.tv_nsec;
}

static void sleep_ns(int64_t nanoseconds) {
    if (nanoseconds <= 0) {
        return;
    }
    struct timespec wait;
    wait.tv_sec = (time_t)(nanoseconds / 1000000000LL);
    wait.tv_nsec = (long)(nanoseconds % 1000000000LL);
    while (nanosleep(&wait, &wait) != 0 && errno == EINTR) {
    }
}

static void read_clock(ae_video *v, clock_view *out) {
    pthread_mutex_lock(&v->mutex);
    int64_t position = v->anchor_us;
    if (v->playing) {
        int64_t elapsed = monotonic_ns() - v->anchor_ns;
        if (elapsed > 0) {
            position += (int64_t)((double)elapsed / 1000.0 * v->speed);
        }
    }
    out->position_us = position;
    out->playing = v->playing;
    out->speed = v->speed;
    out->generation = v->generation;
    out->active = v->active;
    pthread_mutex_unlock(&v->mutex);
}

/* Sleeps until the host changes the clock or the activity, or closes the reader. */
static void wait_for_change(ae_video *v, uint64_t generation) {
    pthread_mutex_lock(&v->mutex);
    while (!atomic_load(&v->stop) && v->generation == generation) {
        pthread_cond_wait(&v->changed, &v->mutex);
    }
    pthread_mutex_unlock(&v->mutex);
}

static void set_state(ae_video *v, int32_t state) {
    pthread_mutex_lock(&v->mutex);
    if (v->state != AE_VIDEO_FAILED) {
        v->state = state;
    }
    pthread_mutex_unlock(&v->mutex);
}

static void set_failed(ae_video *v, const char *what, int code) {
    char reason[128];
    if (code == 0 || av_strerror(code, reason, sizeof reason) < 0) {
        snprintf(reason, sizeof reason, "error %d", code);
    }
    pthread_mutex_lock(&v->mutex);
    snprintf(v->error, sizeof v->error, "%s: %s", what, code == 0 ? "out of memory" : reason);
    v->state = AE_VIDEO_FAILED;
    pthread_mutex_unlock(&v->mutex);
}

static int interrupt_cb(void *opaque) {
    return atomic_load(&((ae_video *)opaque)->stop);
}

static int open_input(ae_video *v, AVFormatContext **format, AVCodecContext **decoder, int *stream_index) {
    AVDictionary *options = NULL;
    int local = strncmp(v->url, "file:", 5) == 0;
    /* Local canvases cannot open network URLs or playlist/concat demuxers. Remote streams cannot open files. */
    av_dict_set(&options, "protocol_whitelist", local ? "file" : "https,tls,tcp", 0);
    if (local) {
        av_dict_set(&options, "format_whitelist", "mov,matroska,webm,gif", 0);
    }
    av_dict_set(&options, "rw_timeout", "15000000", 0);
    av_dict_set(&options, "reconnect", "1", 0);
    av_dict_set(&options, "reconnect_on_network_error", "1", 0);
    av_dict_set(&options, "reconnect_delay_max", "4", 0);
    if (v->user_agent && v->user_agent[0]) {
        av_dict_set(&options, "user_agent", v->user_agent, 0);
    }

    AVFormatContext *fmt = avformat_alloc_context();
    if (!fmt) {
        av_dict_free(&options);
        return AVERROR(ENOMEM);
    }
    fmt->interrupt_callback.callback = interrupt_cb;
    fmt->interrupt_callback.opaque = v;
    int r = avformat_open_input(&fmt, v->url, NULL, &options);
    av_dict_free(&options);
    if (r < 0) {
        return r;
    }
    r = avformat_find_stream_info(fmt, NULL);
    if (r < 0) {
        avformat_close_input(&fmt);
        return r;
    }
    const AVCodec *codec = NULL;
    r = av_find_best_stream(fmt, AVMEDIA_TYPE_VIDEO, -1, -1, &codec, 0);
    if (r < 0 || !codec) {
        avformat_close_input(&fmt);
        return r < 0 ? r : AVERROR_DECODER_NOT_FOUND;
    }
    int index = r;
    for (unsigned int i = 0; i < fmt->nb_streams; i++) {
        if ((int)i != index) {
            fmt->streams[i]->discard = AVDISCARD_ALL;
        }
    }

    AVCodecContext *ctx = avcodec_alloc_context3(codec);
    if (!ctx) {
        avformat_close_input(&fmt);
        return AVERROR(ENOMEM);
    }
    r = avcodec_parameters_to_context(ctx, fmt->streams[index]->codecpar);
    if (r >= 0) {
        ctx->thread_count = AV_DECODE_THREADS;
        ctx->pkt_timebase = fmt->streams[index]->time_base;
        r = avcodec_open2(ctx, codec, NULL);
    }
    if (r < 0) {
        avcodec_free_context(&ctx);
        avformat_close_input(&fmt);
        return r;
    }
    *format = fmt;
    *decoder = ctx;
    *stream_index = index;
    int64_t duration = fmt->duration > 0 ? fmt->duration : 0;
    if (duration == 0 && fmt->streams[index]->duration > 0) {
        duration = av_rescale_q(fmt->streams[index]->duration, fmt->streams[index]->time_base, AV_TIME_BASE_Q);
    }
    pthread_mutex_lock(&v->mutex);
    v->duration_us = duration > 0 ? duration : 0;
    pthread_mutex_unlock(&v->mutex);
    return 0;
}

static void seek_to(AVFormatContext *fmt, AVCodecContext *dec, int index, int64_t target_us) {
    AVStream *stream = fmt->streams[index];
    int64_t ts = av_rescale_q(target_us, AV_TIME_BASE_Q, stream->time_base);
    if (stream->start_time != AV_NOPTS_VALUE) {
        ts += stream->start_time;
    }
    /* A stream that cannot seek keeps decoding from where it is; the drop rule then catches up. */
    (void)av_seek_frame(fmt, index, ts, AVSEEK_FLAG_BACKWARD);
    avcodec_flush_buffers(dec);
}

static int64_t frame_pts_us(const AVFrame *frame, const AVStream *stream) {
    int64_t pts = frame->best_effort_timestamp != AV_NOPTS_VALUE ? frame->best_effort_timestamp : frame->pts;
    if (pts == AV_NOPTS_VALUE) {
        return AV_NOPTS_VALUE;
    }
    if (stream->start_time != AV_NOPTS_VALUE) {
        pts -= stream->start_time;
    }
    return av_rescale_q(pts, stream->time_base, AV_TIME_BASE_Q);
}

/* Returns 0 with a frame, AVERROR_EOF at the end of the stream, or another negative code on failure. */
static int decode_next(AVFormatContext *fmt, AVCodecContext *dec, int index, AVPacket *packet, AVFrame *frame,
                       int *draining) {
    for (;;) {
        int r = avcodec_receive_frame(dec, frame);
        if (r == 0 || r == AVERROR_EOF) {
            return r;
        }
        if (r != AVERROR(EAGAIN)) {
            return r;
        }
        if (*draining) {
            return AVERROR_EOF;
        }
        r = av_read_frame(fmt, packet);
        if (r == AVERROR_EOF) {
            *draining = 1;
            (void)avcodec_send_packet(dec, NULL);
            continue;
        }
        if (r < 0) {
            return r;
        }
        if (packet->stream_index == index) {
            r = avcodec_send_packet(dec, packet);
            av_packet_unref(packet);
            /* One damaged packet costs one picture, not the whole video. */
            if (r < 0 && r != AVERROR(EAGAIN) && r != AVERROR_INVALIDDATA) {
                return r;
            }
        } else {
            av_packet_unref(packet);
        }
    }
}

/* Converts frame into the back buffer and makes it the presented frame. Returns a negative code on failure. */
static int present(ae_video *v, struct SwsContext **sws, const AVFrame *frame, int64_t pts_us, uint8_t **back,
                   size_t *back_capacity) {
    int src_w = frame->width;
    int src_h = frame->height;
    if (src_w <= 0 || src_h <= 0) {
        return 0;
    }
    int dst_w = src_w;
    int dst_h = src_h;
    if (v->max_height > 0 && src_h > v->max_height) {
        dst_h = v->max_height;
        dst_w = (int)((int64_t)src_w * dst_h / src_h);
        if (dst_w < 1) {
            dst_w = 1;
        }
    }
    size_t need = (size_t)dst_w * (size_t)dst_h * 4u;
    if (*back_capacity < need) {
        uint8_t *grown = realloc(*back, need);
        if (!grown) {
            return AVERROR(ENOMEM);
        }
        *back = grown;
        *back_capacity = need;
    }
    *sws = sws_getCachedContext(*sws, src_w, src_h, (enum AVPixelFormat)frame->format, dst_w, dst_h,
                                AV_PIX_FMT_BGRA, SWS_BILINEAR, NULL, NULL, NULL);
    if (!*sws) {
        return AVERROR(EINVAL);
    }
    uint8_t *planes[4] = {*back, NULL, NULL, NULL};
    int strides[4] = {dst_w * 4, 0, 0, 0};
    sws_scale(*sws, (const uint8_t *const *)frame->data, frame->linesize, 0, src_h, planes, strides);

    pthread_mutex_lock(&v->mutex);
    uint8_t *previous = v->front;
    size_t previous_capacity = v->front_capacity;
    v->front = *back;
    v->front_capacity = *back_capacity;
    v->width = dst_w;
    v->height = dst_h;
    v->pts_us = pts_us;
    v->sequence++;
    if (v->state != AE_VIDEO_FAILED) {
        v->state = AE_VIDEO_READY;
    }
    pthread_mutex_unlock(&v->mutex);
    *back = previous;
    *back_capacity = previous_capacity;
    return 1;
}

static void *video_main(void *opaque) {
    ae_video *v = (ae_video *)opaque;
    AVFormatContext *fmt = NULL;
    AVCodecContext *dec = NULL;
    struct SwsContext *sws = NULL;
    AVPacket *packet = av_packet_alloc();
    AVFrame *frame = av_frame_alloc();
    uint8_t *back = NULL;
    size_t back_capacity = 0;
    int index = -1;
    int opened = 0;
    int held = 0;
    int draining = 0;
    int ended = 0;
    int resync = 1;
    int catching_up = 0;
    int64_t catch_up_target = 0;
    int64_t held_pts = AV_NOPTS_VALUE;
    int64_t decoded_pts = AV_NOPTS_VALUE;
    int64_t last_shown_ns = 0;
    clock_view clock;

    if (!packet || !frame) {
        set_failed(v, "start", 0);
        goto done;
    }

    while (!atomic_load(&v->stop)) {
        read_clock(v, &clock);
        if (!clock.active) {
            resync = 1;
            wait_for_change(v, clock.generation);
            continue;
        }
        if (!opened) {
            int r = open_input(v, &fmt, &dec, &index);
            if (r < 0) {
                if (!atomic_load(&v->stop)) {
                    set_failed(v, "open", r);
                }
                goto done;
            }
            opened = 1;
        }
        AVStream *stream = fmt->streams[index];
        int64_t target = clock.position_us;

        int seek = 0;
        if (catching_up) {
            seek = target < catch_up_target - AV_AHEAD_RESYNC_US || target > catch_up_target + AV_CATCH_UP_LIMIT_US;
        } else if (resync) {
            seek = decoded_pts != AV_NOPTS_VALUE || target > AV_START_SEEK_US;
        } else if (decoded_pts != AV_NOPTS_VALUE) {
            int behind = target > decoded_pts + AV_BEHIND_RESYNC_US && !ended;
            int ahead = target < decoded_pts - AV_AHEAD_RESYNC_US || (ended && target < decoded_pts);
            seek = behind || ahead;
        }
        resync = 0;
        if (seek) {
            seek_to(fmt, dec, index, target);
            if (held) {
                av_frame_unref(frame);
                held = 0;
            }
            draining = 0;
            ended = 0;
            decoded_pts = AV_NOPTS_VALUE;
            catching_up = 1;
            catch_up_target = target;
            set_state(v, AE_VIDEO_READY);
        }

        if (!held && !ended) {
            int r = decode_next(fmt, dec, index, packet, frame, &draining);
            if (atomic_load(&v->stop)) {
                break;
            }
            if (r == AVERROR_EOF) {
                ended = 1;
                set_state(v, AE_VIDEO_ENDED);
                continue;
            }
            if (r < 0) {
                set_failed(v, "decode", r);
                goto done;
            }
            held_pts = frame_pts_us(frame, stream);
            if (held_pts == AV_NOPTS_VALUE) {
                held_pts = decoded_pts == AV_NOPTS_VALUE ? 0 : decoded_pts + AV_FALLBACK_STEP_US;
            }
            decoded_pts = held_pts;
            held = 1;
        }

        if (held) {
            int64_t now = monotonic_ns();
            int starved = last_shown_ns == 0 || now - last_shown_ns >= AV_STARVED_NS;
            if (catching_up && held_pts >= catch_up_target - AV_LATE_US) {
                catching_up = 0;
            }
            if (held_pts < target - AV_LATE_US && !starved) {
                av_frame_unref(frame);
                held = 0;
                continue;
            }
            if (held_pts <= target + AV_EARLY_US || (starved && held_pts < target)) {
                int r = present(v, &sws, frame, held_pts, &back, &back_capacity);
                av_frame_unref(frame);
                held = 0;
                if (r < 0) {
                    set_failed(v, "convert", r);
                    goto done;
                }
                last_shown_ns = now;
                continue;
            }
            if (!clock.playing) {
                wait_for_change(v, clock.generation);
                continue;
            }
            double speed = clock.speed > 0.01 ? clock.speed : 1.0;
            int64_t wait = (int64_t)((double)(held_pts - target) * 1000.0 / speed);
            sleep_ns(wait < AV_MAX_WAIT_NS ? wait : AV_MAX_WAIT_NS);
            continue;
        }

        /* The stream ended: the last picture stays until the host seeks back or closes. */
        wait_for_change(v, clock.generation);
    }

done:
    av_frame_free(&frame);
    av_packet_free(&packet);
    sws_freeContext(sws);
    avcodec_free_context(&dec);
    avformat_close_input(&fmt);
    free(back);
    return NULL;
}

static void video_free(ae_video *v) {
    free(v->front);
    free(v->url);
    free(v->user_agent);
    free(v);
}

ae_video *ae_video_open(const char *url, const char *user_agent, int32_t max_height) {
    if (!url || (strncmp(url, "https://", 8) != 0 && strncmp(url, "file:", 5) != 0)) {
        return NULL;
    }
    ae_video *v = calloc(1, sizeof *v);
    if (!v) {
        return NULL;
    }
    v->url = strdup(url);
    v->user_agent = user_agent ? strdup(user_agent) : NULL;
    if (!v->url || (user_agent && !v->user_agent)) {
        video_free(v);
        return NULL;
    }
    v->max_height = max_height;
    v->speed = 1.0;
    v->anchor_ns = monotonic_ns();
    v->state = AE_VIDEO_OPENING;
    atomic_init(&v->stop, 0);
    pthread_mutex_init(&v->mutex, NULL);
    pthread_cond_init(&v->changed, NULL);
    if (pthread_create(&v->thread, NULL, video_main, v) != 0) {
        pthread_cond_destroy(&v->changed);
        pthread_mutex_destroy(&v->mutex);
        video_free(v);
        return NULL;
    }
    v->thread_started = 1;
    return v;
}

void ae_video_set_clock(ae_video *v, int64_t position_us, int32_t playing, float speed) {
    if (!v) {
        return;
    }
    /* NaN fails both comparisons, so it lands on normal speed too. */
    double rate = (speed > 0.0f && speed <= 8.0f) ? (double)speed : 1.0;
    pthread_mutex_lock(&v->mutex);
    v->anchor_us = position_us > 0 ? position_us : 0;
    v->anchor_ns = monotonic_ns();
    v->playing = playing != 0;
    v->speed = rate;
    v->generation++;
    pthread_cond_broadcast(&v->changed);
    pthread_mutex_unlock(&v->mutex);
}

void ae_video_set_active(ae_video *v, int32_t active) {
    if (!v) {
        return;
    }
    pthread_mutex_lock(&v->mutex);
    if (v->active != (active != 0)) {
        v->active = active != 0;
        v->generation++;
        pthread_cond_broadcast(&v->changed);
    }
    pthread_mutex_unlock(&v->mutex);
}

int32_t ae_video_copy_frame(ae_video *v, uint64_t known_sequence, uint8_t *out, size_t capacity,
                            ae_video_frame_info *info) {
    if (!v || !info) {
        return 0;
    }
    int32_t result = 0;
    pthread_mutex_lock(&v->mutex);
    info->sequence = v->sequence;
    info->width = v->width;
    info->height = v->height;
    info->state = v->state;
    info->pts_us = v->pts_us;
    info->duration_us = v->duration_us;
    if (v->sequence != 0 && v->sequence != known_sequence && v->front) {
        size_t need = (size_t)v->width * (size_t)v->height * 4u;
        if (!out || capacity < need) {
            result = -1;
        } else {
            memcpy(out, v->front, need);
            result = 1;
        }
    }
    pthread_mutex_unlock(&v->mutex);
    return result;
}

size_t ae_video_last_error(ae_video *v, char *buffer, size_t buffer_len) {
    if (!v || !buffer || buffer_len == 0) {
        return 0;
    }
    pthread_mutex_lock(&v->mutex);
    size_t length = strlen(v->error);
    if (length >= buffer_len) {
        length = buffer_len - 1;
    }
    memcpy(buffer, v->error, length);
    buffer[length] = '\0';
    pthread_mutex_unlock(&v->mutex);
    return length;
}

void ae_video_close(ae_video *v) {
    if (!v) {
        return;
    }
    atomic_store(&v->stop, 1);
    pthread_mutex_lock(&v->mutex);
    v->generation++;
    pthread_cond_broadcast(&v->changed);
    pthread_mutex_unlock(&v->mutex);
    if (v->thread_started) {
        pthread_join(v->thread, NULL);
    }
    pthread_cond_destroy(&v->changed);
    pthread_mutex_destroy(&v->mutex);
    video_free(v);
}
