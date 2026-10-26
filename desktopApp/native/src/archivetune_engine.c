/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

/*
 * Desktop playback engine.
 *
 * One producer thread owns every FFmpeg object. It opens the source, demuxes and decodes packets,
 * and runs them through an abuffer -> [atrim] -> [atempo] -> aformat -> abuffersink graph. The graph
 * output lands in a float ring buffer as interleaved stereo at the engine rate. The consumer, either
 * a miniaudio device callback or ae_engine_render in manual mode, drains that ring.
 *
 * Position counts only the frames the consumer really takes. Each ring segment remembers the tempo
 * it was rendered with, so a speed change never retimes audio that is already queued. Silence,
 * starvation and pause never advance the position.
 */

#include "archivetune_device.h"
#include "archivetune_engine.h"
#include "archivetune_engine_internal.h"

#include <errno.h>
#include <math.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

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
#include <libavutil/rational.h>
#include <libavutil/samplefmt.h>

#define AE_CHANNELS 2u
#define AE_DEFAULT_RATE 48000u
#define AE_MIN_RATE 8000u
#define AE_MAX_RATE 192000u
#define AE_DEFAULT_RING_MS 2000u
#define AE_DEFAULT_PREBUFFER_MS 200u
#define AE_MIN_RING_FRAMES 16384u
#define AE_SPEED_MIN 0.5
#define AE_SPEED_MAX 4.0
#define AE_SEGMENTS 64u
#define AE_ERROR_CAPACITY 512u
#define AE_RENDER_TIMEOUT_NS 10000000000LL
#define AE_OUTPUT_SPACE_POLL_NS 5000000LL

/*
 * Consumers of consume_locked. Only the blocking kind notifies the producer through the condition
 * variable. The realtime and mixed kinds never enter pthread_cond_*, so an audio callback cannot wait
 * on it; their producers are manual and poll for ring space instead (see wait_for_output_space_locked).
 */
#define AE_CONSUME_BLOCKING 0 /* device and manual output: prebuffer gate, condition notification */
#define AE_CONSUME_REALTIME 1 /* ae_engine_render_realtime: starts on any decoded frame */
#define AE_CONSUME_MIXED 2    /* two-deck mixer: prebuffer gate, no condition calls */

_Static_assert(ATOMIC_INT_LOCK_FREE == 2, "Realtime output requires lock-free integer atomics");

/*
 * A host read that answers AE_IO_RETRY is asked again after a short pause, and the source is given up
 * after the starvation limit. The pause keeps a host that answers too early from spinning the producer.
 */
#define AE_STREAM_RETRY_NS 5000000LL
#define AE_STREAM_STARVATION_NS 30000000000LL
#define AE_STREAM_BUFFER_BYTES 32768

/*
 * The producer's copy of a host source. FFmpeg's AVIO callbacks receive a pointer to it, so it must stay
 * at a fixed address until its AVIOContext is freed; the producer's ae_source owns it for that reason.
 */
typedef struct ae_lease {
    ae_stream_io io;     /* the host callbacks; the producer owns this copy */
    ae_engine *engine;   /* carries the interrupt flag that ends a blocked callback */
    int64_t position;    /* absolute offset of the next byte the host will supply */
    int64_t size;        /* total length, or -1 when the host does not know it */
    int seekable;
} ae_lease;

/* Producer-owned state. Only the producer thread touches it, so it needs no lock. */
typedef struct ae_source {
    AVFormatContext *format;
    AVCodecContext *decoder;
    AVFilterGraph *graph;
    AVFilterContext *buffer_src;
    AVFilterContext *buffer_sink;
    AVPacket *packet;
    AVFrame *frame;
    AVFrame *out;
    AVRational time_base;
    int64_t start_time; /* stream start in time_base units, 0 when the container reports none */
    int stream_index;
    int seekable;
    int64_t duration_us; /* -1 when unknown */
    double graph_tempo;  /* tempo the current graph applies */
    double trim_seconds; /* absolute stream time the current graph starts at, -1 when untrimmed */
    int flushed;         /* end of input has been sent through the graph */
    AVIOContext *avio;   /* custom input of a host source, or NULL for a file or URL */
    ae_lease lease;     /* the host source behind avio; valid while leased is set */
    int leased;
} ae_source;

struct ae_engine {
    pthread_mutex_t mutex;
    pthread_cond_t changed; /* the producer waits for work or space; consumers wait for data */
    pthread_t producer;
    ae_device *device;
    _Atomic int interrupt; /* aborts a blocking read in the producer */

    int manual;
    uint32_t rate;
    uint32_t capacity;   /* ring capacity in frames */
    uint32_t high_water; /* the producer stops reading ahead above this */
    uint32_t prebuffer;  /* frames needed before output starts or resumes */
    float *ring;
    uint32_t ring_head;
    uint32_t ring_count;

    uint32_t seg_head;
    uint32_t seg_count;
    uint32_t seg_frames[AE_SEGMENTS];
    double seg_tempo[AE_SEGMENTS];

    int shutdown;
    int decoding; /* the producer is inside a blocking decode step, so a request must interrupt it */
    uint64_t epoch; /* bumps on every load and stop, which invalidates in-flight work */

    int pending_load;
    char *pending_source;
    int64_t pending_start_us;
    int pending_seek;
    int64_t pending_seek_us;

    int play_intent;
    double speed;
    int has_source;
    int ready;
    int failed;
    int eof;
    int ended;
    int filling;
    int buffering;
    _Atomic int advancing; /* a contended realtime callback can clear this without taking mutex */
    int seekable;
    int64_t duration_us;
    double position_us;

    uint64_t load_generation;
    uint64_t seek_generation;
    uint64_t error_sequence;
    uint32_t error_code;
    char error_text[AE_ERROR_CAPACITY];

    /*
     * The host source the producer has open, or NULL. Commands read it and call its interrupt under the mutex,
     * and the producer clears it under the mutex before closing the source, so a wake never reaches a freed lease.
     */
    ae_lease *active_lease;
    /* A host source queued for the next load. The engine owns it until the producer takes it. */
    int pending_stream;
    ae_stream_io pending_io;
};

static uint32_t min_u32(uint32_t a, uint32_t b) { return a < b ? a : b; }

#ifndef __APPLE__
static void deadline_after(struct timespec *deadline, int64_t nanoseconds);
#endif

/* Darwin has no clock-selectable condition attribute; pthread_cond_timedwait_relative_np
 * is its monotonic-relative wait. Other platforms wait on a CLOCK_MONOTONIC deadline. */
static int wait_changed_locked(ae_engine *e, int64_t nanoseconds) {
#ifdef __APPLE__
    struct timespec relative;
    relative.tv_sec = (long)(nanoseconds / 1000000000LL);
    relative.tv_nsec = (long)(nanoseconds % 1000000000LL);
    return pthread_cond_timedwait_relative_np(&e->changed, &e->mutex, &relative);
#else
    struct timespec deadline;
    deadline_after(&deadline, nanoseconds);
    return pthread_cond_timedwait(&e->changed, &e->mutex, &deadline);
#endif
}

/*
 * The realtime consumer never enters pthread_cond_* (which may acquire an internal lock).
 * Only an active manual producer waiting for output space polls; paused/idle producers still sleep.
 * Blocking/manual and device consumers retain their existing immediate condition notification.
 */
static void wait_for_output_space_locked(ae_engine *e) {
    if (e->manual && e->play_intent) {
        wait_changed_locked(e, AE_OUTPUT_SPACE_POLL_NS);
    } else {
        pthread_cond_wait(&e->changed, &e->mutex);
    }
}

static double frames_to_us(double frames, double tempo, uint32_t rate) {
    return frames * tempo * 1e6 / (double)rate;
}

static void format_error(char *message, size_t len, const char *what, int code) {
    char detail[160];
    av_strerror(code, detail, sizeof detail);
    snprintf(message, len, "%s failed: %s", what, detail);
}

static int interrupt_requested(ae_engine *e) {
    return atomic_load(&e->interrupt) != 0;
}

static int interrupt_cb(void *opaque) {
    return interrupt_requested((ae_engine *)opaque);
}

/* Caller holds the mutex. Wakes a host callback blocked on the active source. The host must return at once. */
static void interrupt_lease_locked(ae_engine *e) {
    if (e->active_lease && e->active_lease->io.interrupt) {
        e->active_lease->io.interrupt(e->active_lease->io.opaque);
    }
}

/* Caller holds the mutex. Stops the producer's blocking step and wakes any host callback it is waiting in. */
static void interrupt_locked(ae_engine *e) {
    atomic_store(&e->interrupt, 1);
    interrupt_lease_locked(e);
}

/*
 * Caller holds the mutex. Takes a queued host source that no producer has opened. The caller closes it
 * after unlocking, because a host close must not run under the engine mutex.
 */
static int take_pending_stream_locked(ae_engine *e, ae_stream_io *out) {
    if (!e->pending_stream) {
        return 0;
    }
    *out = e->pending_io;
    e->pending_stream = 0;
    memset(&e->pending_io, 0, sizeof e->pending_io);
    return 1;
}

/* Releases a host source. Never called under the engine mutex. */
static void close_stream_io(const ae_stream_io *io) {
    if (io->close) {
        io->close(io->opaque);
    }
}

/* ------------------------------------------------------------------------------------------------
 * Ring and segment bookkeeping. Every function here runs with the engine mutex held.
 * ---------------------------------------------------------------------------------------------- */

static void ring_clear_locked(ae_engine *e) {
    e->ring_head = 0;
    e->ring_count = 0;
    e->seg_head = 0;
    e->seg_count = 0;
}

static void fail_locked(ae_engine *e, uint32_t code, const char *message) {
    e->failed = 1;
    e->ready = 0;
    e->filling = 0;
    e->pending_seek = 0;
    e->error_code = code;
    e->error_sequence++;
    snprintf(e->error_text, sizeof e->error_text, "%s", message);
    pthread_cond_broadcast(&e->changed);
}

/* Copies frames out of the ring. The caller guarantees frames <= ring_count. */
static void consume_copy_locked(ae_engine *e, float *out, uint32_t frames) {
    while (frames > 0) {
        uint32_t seg = e->seg_head;
        uint32_t n = min_u32(e->seg_frames[seg], frames);
        uint32_t first = min_u32(n, e->capacity - e->ring_head);
        memcpy(out, e->ring + (size_t)e->ring_head * AE_CHANNELS, (size_t)first * AE_CHANNELS * sizeof(float));
        if (n > first) {
            memcpy(out + (size_t)first * AE_CHANNELS, e->ring, (size_t)(n - first) * AE_CHANNELS * sizeof(float));
        }
        e->ring_head = (e->ring_head + n) % e->capacity;
        e->ring_count -= n;
        e->position_us += frames_to_us((double)n, e->seg_tempo[seg], e->rate);
        e->seg_frames[seg] -= n;
        if (e->seg_frames[seg] == 0) {
            e->seg_head = (e->seg_head + 1) % AE_SEGMENTS;
            e->seg_count--;
        }
        out += (size_t)n * AE_CHANNELS;
        frames -= n;
    }
}

static void silence(float *out, uint32_t frames) {
    memset(out, 0, (size_t)frames * AE_CHANNELS * sizeof(float));
}

/*
 * The consumer side of the state machine. Writes exactly `frames` frames to out. Frames that are not
 * real decoded audio are silence, and only real audio advances the position. `mode` is one of the
 * AE_CONSUME_* kinds, which differ only in their start gate and in whether they notify the producer.
 */
static uint32_t consume_locked(ae_engine *e, float *out, uint32_t frames, int mode) {
    uint32_t taken = 0;

    e->advancing = 0;
    if (!e->has_source || e->failed || !e->play_intent || e->ended) {
        e->buffering = 0;
        silence(out, frames);
        return 0;
    }
    if (!e->ready) {
        e->buffering = 1;
        silence(out, frames);
        return 0;
    }
    if (e->filling) {
        if ((mode == AE_CONSUME_REALTIME ? e->ring_count > 0 : e->ring_count >= e->prebuffer) || e->eof) {
            e->filling = 0;
        } else {
            e->buffering = 1;
            silence(out, frames);
            return 0;
        }
    }

    e->buffering = 0;
    taken = min_u32(frames, e->ring_count);
    if (taken > 0) {
        consume_copy_locked(e, out, taken);
        if (mode == AE_CONSUME_BLOCKING) pthread_cond_broadcast(&e->changed);
    }
    if (taken < frames) {
        silence(out + (size_t)taken * AE_CHANNELS, frames - taken);
        if (!e->eof) {
            /* Starved: hold the position until the decoder refills the prebuffer. */
            e->filling = 1;
            e->buffering = 1;
        }
    }
    e->advancing = taken > 0;
    if (e->eof && e->ring_count == 0) {
        e->ended = 1;
    }
    return taken;
}

/*
 * Manual mode waits until the ring holds the whole chunk it is about to take, or the prebuffer when
 * the output is refilling. Waiting for less would turn the remainder of the chunk into silence.
 */
static int manual_wait_needed_locked(const ae_engine *e, uint32_t need) {
    if (!e->has_source || e->failed || !e->play_intent || e->ended || e->eof) {
        return 0;
    }
    if (!e->ready) {
        return 1;
    }
    uint32_t required = need;
    if (e->filling && e->prebuffer > required) {
        required = e->prebuffer;
    }
    return e->ring_count < required;
}

/* ------------------------------------------------------------------------------------------------
 * Producer side: append graph output to the ring.
 * ---------------------------------------------------------------------------------------------- */

static int room_for_locked(const ae_engine *e, double tempo) {
    if (e->ring_count >= e->capacity) {
        return 0;
    }
    if (e->seg_count == 0) {
        return 1;
    }
    uint32_t last = (e->seg_head + e->seg_count - 1) % AE_SEGMENTS;
    if (e->seg_tempo[last] == tempo) {
        return 1;
    }
    return e->seg_count < AE_SEGMENTS;
}

/* Returns 0 when appended, or 1 when the work is stale (superseded, or shutting down) and dropped. */
static int append_frames(ae_engine *e, uint64_t epoch, const float *samples, uint32_t frames, double tempo) {
    uint32_t done = 0;
    int result = 0;

    pthread_mutex_lock(&e->mutex);
    while (done < frames) {
        while (!e->shutdown && epoch == e->epoch && !e->pending_seek && !room_for_locked(e, tempo)) {
            wait_for_output_space_locked(e);
        }
        if (e->shutdown || epoch != e->epoch || e->pending_seek) {
            result = 1;
            break;
        }

        uint32_t n = min_u32(e->capacity - e->ring_count, frames - done);
        uint32_t last = (e->seg_head + e->seg_count - 1) % AE_SEGMENTS;
        if (e->seg_count == 0 || e->seg_tempo[last] != tempo) {
            uint32_t slot = (e->seg_head + e->seg_count) % AE_SEGMENTS;
            e->seg_frames[slot] = 0;
            e->seg_tempo[slot] = tempo;
            e->seg_count++;
            last = slot;
        }
        e->seg_frames[last] += n;

        uint32_t write = (e->ring_head + e->ring_count) % e->capacity;
        uint32_t first = min_u32(n, e->capacity - write);
        memcpy(e->ring + (size_t)write * AE_CHANNELS, samples + (size_t)done * AE_CHANNELS,
               (size_t)first * AE_CHANNELS * sizeof(float));
        if (n > first) {
            memcpy(e->ring, samples + (size_t)(done + first) * AE_CHANNELS,
                   (size_t)(n - first) * AE_CHANNELS * sizeof(float));
        }
        e->ring_count += n;
        done += n;
        pthread_cond_broadcast(&e->changed);
    }
    pthread_mutex_unlock(&e->mutex);
    return result;
}

/* ------------------------------------------------------------------------------------------------
 * Producer side: FFmpeg graph and source management. None of these take the engine mutex.
 * ---------------------------------------------------------------------------------------------- */

static int64_t monotonic_ns(void) {
    struct timespec now;
    clock_gettime(CLOCK_MONOTONIC, &now);
    return (int64_t)now.tv_sec * 1000000000LL + (int64_t)now.tv_nsec;
}

/*
 * FFmpeg callbacks for a host source. They run on the producer thread without the mutex. An interrupted
 * read reports AVERROR_EXIT, which the decode loop already treats as a quiet stop rather than a failure.
 */
static int lease_read(void *opaque, uint8_t *buffer, int size) {
    ae_lease *lease = opaque;
    int64_t started = monotonic_ns();
    for (;;) {
        if (interrupt_requested(lease->engine)) {
            return AVERROR_EXIT;
        }
        int32_t r = lease->io.read(lease->io.opaque, buffer, (uint32_t)size);
        if (r > 0) {
            if (r > size) {
                /* A host that returns more than it was asked for has broken the contract. */
                return AVERROR(EIO);
            }
            lease->position += r;
            return r;
        }
        if (r == AE_IO_EOF) {
            return AVERROR_EOF;
        }
        if (r == AE_IO_INTERRUPTED) {
            return AVERROR_EXIT;
        }
        if (r != AE_IO_RETRY) {
            return AVERROR(EIO);
        }
        if (monotonic_ns() - started > AE_STREAM_STARVATION_NS) {
            return AVERROR(ETIMEDOUT);
        }
        struct timespec pause = {0, (long)AE_STREAM_RETRY_NS};
        nanosleep(&pause, NULL);
    }
}

static int64_t lease_seek(void *opaque, int64_t offset, int whence) {
    ae_lease *lease = opaque;
    if (whence & AVSEEK_SIZE) {
        return lease->size >= 0 ? lease->size : AVERROR(ENOSYS);
    }
    if (!lease->seekable) {
        return AVERROR(EPIPE);
    }
    int64_t target;
    /* The flags FFmpeg ORs above bit 15 are not part of the seek origin. */
    switch (whence & 0xffff) {
    case SEEK_SET:
        target = offset;
        break;
    case SEEK_CUR:
        target = lease->position + offset;
        break;
    case SEEK_END:
        if (lease->size < 0) {
            return AVERROR(EINVAL);
        }
        target = lease->size + offset;
        break;
    default:
        return AVERROR(EINVAL);
    }
    if (target < 0) {
        return AVERROR(EINVAL);
    }
    /* The header promises an interrupt check before every host call, so a seek starts none once one is pending. */
    if (interrupt_requested(lease->engine)) {
        return AVERROR_EXIT;
    }
    int64_t reached = lease->io.seek(lease->io.opaque, target);
    if (reached == AE_IO_INTERRUPTED) {
        return AVERROR_EXIT;
    }
    /* Landing anywhere but the target would silently desynchronise FFmpeg's view of the stream. */
    if (reached < 0 || reached != target) {
        return AVERROR(EIO);
    }
    lease->position = reached;
    return reached;
}

/*
 * FFmpeg falls back to its own opener for any child it cannot find in the lease, such as a manifest
 * segment or a key. Refusing that open keeps every request on the host's transport and policy.
 */
static int lease_io_open_refused(struct AVFormatContext *format, AVIOContext **pb, const char *url, int flags,
                                 AVDictionary **options) {
    (void)format;
    (void)url;
    (void)flags;
    (void)options;
    *pb = NULL;
    return AVERROR(EPERM);
}

/* Lets commands reach a lease only while the producer owns it. Retract before anything it points to is freed. */
static void lease_retract(ae_lease *lease) {
    ae_engine *e = lease->engine;
    pthread_mutex_lock(&e->mutex);
    if (e->active_lease == lease) {
        e->active_lease = NULL;
    }
    pthread_mutex_unlock(&e->mutex);
}

/*
 * FFmpeg latches a failed read as end of input until something seeks. An interrupt is not the end of the
 * stream, so the latch is cleared and the next read asks the host again.
 */
static void clear_avio_latch(ae_source *s) {
    if (s->avio) {
        s->avio->eof_reached = 0;
        s->avio->error = 0;
    }
}

static void source_close(ae_source *s) {
    if (s->leased) {
        lease_retract(&s->lease);
    }
    if (s->graph) {
        avfilter_graph_free(&s->graph);
    }
    s->buffer_src = NULL;
    s->buffer_sink = NULL;
    if (s->decoder) {
        avcodec_free_context(&s->decoder);
    }
    if (s->format) {
        avformat_close_input(&s->format);
    }
    if (s->avio) {
        /* A format context opened on a caller's AVIOContext never frees it, so the buffer and context go here. */
        av_freep(&s->avio->buffer);
        avio_context_free(&s->avio);
    }
    if (s->leased) {
        s->leased = 0;
        close_stream_io(&s->lease.io);
    }
    s->flushed = 0;
    s->graph_tempo = 0.0;
    s->trim_seconds = -1.0;
    s->seekable = 0;
    s->duration_us = -1;
}

/*
 * Formats seconds with nine decimals using integer arithmetic. snprintf("%f") follows the process
 * LC_NUMERIC, and a comma decimal separator would make the filter arguments unparseable.
 */
static void format_fixed9(char *out, size_t len, double value) {
    long long scaled = llround(value * 1e9);
    long long whole = scaled / 1000000000LL;
    long long fraction = scaled % 1000000000LL;
    if (fraction < 0) {
        fraction = -fraction;
    }
    snprintf(out, len, "%lld.%09lld", whole, fraction);
}

/*
 * Builds abuffer -> [atrim] -> [atempo] -> aformat -> abuffersink. trim_seconds is an absolute stream
 * timestamp in seconds; a negative value disables the trim. Tempo 1.0 bypasses atempo so that
 * unity-speed output is sample-exact.
 */
static int build_graph(ae_source *s, double tempo, double trim_seconds, uint32_t rate, char *message, size_t len) {
    const AVFilter *buffer = avfilter_get_by_name("abuffer");
    const AVFilter *sink = avfilter_get_by_name("abuffersink");
    const AVFilter *format = avfilter_get_by_name("aformat");
    const AVFilter *trim = avfilter_get_by_name("atrim");
    const AVFilter *stretch = avfilter_get_by_name("atempo");
    const AVFilter *duplicate = avfilter_get_by_name("channelmap");
    AVFilterContext *src = NULL;
    AVFilterContext *prev = NULL;
    AVFilterContext *ctx = NULL;
    char args[160];
    char number[48];
    int r;

    if (s->graph) {
        avfilter_graph_free(&s->graph);
    }
    s->buffer_src = NULL;
    s->buffer_sink = NULL;
    s->flushed = 0;

    if (!buffer || !sink || !format || !duplicate || (trim_seconds >= 0.0 && !trim) ||
        (fabs(tempo - 1.0) > 1e-9 && !stretch)) {
        snprintf(message, len, "required FFmpeg filters are missing from the native build");
        return -1;
    }

    s->graph = avfilter_graph_alloc();
    if (!s->graph) {
        snprintf(message, len, "out of memory");
        return -1;
    }

    /* The source must receive its parameters before init, because init validates the sample format. */
    src = avfilter_graph_alloc_filter(s->graph, buffer, "in");
    if (!src) {
        snprintf(message, len, "out of memory");
        return -1;
    }
    AVBufferSrcParameters *params = av_buffersrc_parameters_alloc();
    if (!params) {
        snprintf(message, len, "out of memory");
        return -1;
    }
    params->format = s->decoder->sample_fmt;
    params->time_base = s->time_base;
    params->sample_rate = s->decoder->sample_rate;
    r = av_channel_layout_copy(&params->ch_layout, &s->decoder->ch_layout);
    if (r >= 0) {
        r = av_buffersrc_parameters_set(src, params);
    }
    av_channel_layout_uninit(&params->ch_layout);
    av_freep(&params);
    if (r >= 0) {
        r = avfilter_init_str(src, NULL);
    }
    if (r < 0) {
        format_error(message, len, "audio input", r);
        return -1;
    }
    prev = src;

    if (trim_seconds >= 0.0) {
        format_fixed9(number, sizeof number, trim_seconds);
        snprintf(args, sizeof args, "start=%s", number);
        r = avfilter_graph_create_filter(&ctx, trim, "trim", args, NULL, s->graph);
        if (r < 0 || avfilter_link(prev, 0, ctx, 0) < 0) {
            format_error(message, len, "trim", r < 0 ? r : AVERROR(EINVAL));
            return -1;
        }
        prev = ctx;
    }

    if (fabs(tempo - 1.0) > 1e-9) {
        format_fixed9(number, sizeof number, tempo);
        snprintf(args, sizeof args, "tempo=%s", number);
        r = avfilter_graph_create_filter(&ctx, stretch, "tempo", args, NULL, s->graph);
        if (r < 0 || avfilter_link(prev, 0, ctx, 0) < 0) {
            format_error(message, len, "time stretch", r < 0 ? r : AVERROR(EINVAL));
            return -1;
        }
        prev = ctx;
    }

    /*
     * aformat's mono-to-stereo conversion applies the -3 dB centre mix. Mono sources are duplicated
     * at unity instead, which is what a stereo output device plays for a mono file.
     */
    if (s->decoder->ch_layout.nb_channels == 1) {
        r = avfilter_graph_create_filter(&ctx, duplicate, "duplicate", "map=0|0:channel_layout=stereo", NULL,
                                         s->graph);
        if (r < 0 || avfilter_link(prev, 0, ctx, 0) < 0) {
            format_error(message, len, "mono duplication", r < 0 ? r : AVERROR(EINVAL));
            return -1;
        }
        prev = ctx;
    }

    snprintf(args, sizeof args, "sample_fmts=flt:sample_rates=%u:channel_layouts=stereo", rate);
    r = avfilter_graph_create_filter(&ctx, format, "format", args, NULL, s->graph);
    if (r < 0 || avfilter_link(prev, 0, ctx, 0) < 0) {
        format_error(message, len, "output format", r < 0 ? r : AVERROR(EINVAL));
        return -1;
    }
    prev = ctx;

    AVFilterContext *out = NULL;
    r = avfilter_graph_create_filter(&out, sink, "out", NULL, NULL, s->graph);
    if (r < 0 || avfilter_link(prev, 0, out, 0) < 0) {
        format_error(message, len, "output sink", r < 0 ? r : AVERROR(EINVAL));
        return -1;
    }

    r = avfilter_graph_config(s->graph, NULL);
    if (r < 0) {
        format_error(message, len, "filter graph", r);
        return -1;
    }
    s->buffer_src = src;
    s->buffer_sink = out;
    s->graph_tempo = tempo;
    s->trim_seconds = trim_seconds;
    return 0;
}

/* Pulls every ready frame from the sink into the ring. Returns 0, 1 for stale work, or -1 on error. */
static int drain_sink(ae_engine *e, ae_source *s, uint64_t epoch, char *message, size_t len) {
    for (;;) {
        int r = av_buffersink_get_frame(s->buffer_sink, s->out);
        if (r == AVERROR(EAGAIN) || r == AVERROR_EOF) {
            return 0;
        }
        if (r < 0) {
            format_error(message, len, "audio output", r);
            return -1;
        }
        int status = 0;
        if (s->out->nb_samples > 0) {
            status = append_frames(e, epoch, (const float *)s->out->data[0], (uint32_t)s->out->nb_samples,
                                   s->graph_tempo);
        }
        av_frame_unref(s->out);
        if (status != 0) {
            return status;
        }
    }
}

/* Sends decoded frames through the graph. Returns 0 on success, 1 for stale work, or -1 on error. */
static int receive_frames(ae_engine *e, ae_source *s, uint64_t epoch, char *message, size_t len) {
    for (;;) {
        int r = avcodec_receive_frame(s->decoder, s->frame);
        if (r == AVERROR(EAGAIN) || r == AVERROR_EOF) {
            return 0;
        }
        if (r < 0) {
            format_error(message, len, "decode", r);
            return -1;
        }
        if (s->frame->pts == AV_NOPTS_VALUE) {
            s->frame->pts = s->frame->best_effort_timestamp;
        }
        r = av_buffersrc_add_frame_flags(s->buffer_src, s->frame, 0);
        av_frame_unref(s->frame);
        if (r < 0) {
            format_error(message, len, "filter input", r);
            return -1;
        }
        r = drain_sink(e, s, epoch, message, len);
        if (r != 0) {
            return r;
        }
    }
}

/*
 * Finishes an open once FFmpeg has a demuxer: picks the audio stream, opens its decoder and builds the graph
 * at the requested start. File and host-source opens share it, so both follow the same rules.
 */
static int source_finish_open(ae_engine *e, ae_source *s, int64_t start_us, double tempo, char *message,
                              size_t len) {
    const AVCodec *codec = NULL;
    int r = avformat_find_stream_info(s->format, NULL);
    if (r < 0) {
        format_error(message, len, "probe", r);
        source_close(s);
        return -1;
    }
    r = av_find_best_stream(s->format, AVMEDIA_TYPE_AUDIO, -1, -1, &codec, 0);
    if (r < 0 || !codec) {
        snprintf(message, len, "the source has no decodable audio stream");
        source_close(s);
        return -1;
    }
    s->stream_index = r;
    AVStream *stream = s->format->streams[r];
    s->time_base = stream->time_base;
    s->start_time = stream->start_time != AV_NOPTS_VALUE ? stream->start_time : 0;

    s->decoder = avcodec_alloc_context3(codec);
    if (!s->decoder) {
        snprintf(message, len, "out of memory");
        source_close(s);
        return -1;
    }
    r = avcodec_parameters_to_context(s->decoder, stream->codecpar);
    if (r >= 0) {
        r = avcodec_open2(s->decoder, codec, NULL);
    }
    if (r < 0) {
        format_error(message, len, "decoder", r);
        source_close(s);
        return -1;
    }

    if (s->format->duration != AV_NOPTS_VALUE) {
        s->duration_us = s->format->duration;
    } else if (stream->duration != AV_NOPTS_VALUE) {
        s->duration_us = av_rescale_q(stream->duration, stream->time_base, AV_TIME_BASE_Q);
    } else {
        s->duration_us = -1;
    }
    /* A host source seeks only when the host said it can, whatever the container reports. */
    s->seekable = s->duration_us > 0 && (!s->avio || (s->avio->seekable & AVIO_SEEKABLE_NORMAL) != 0);

    double trim = -1.0;
    if (start_us > 0) {
        trim = (double)s->start_time * av_q2d(s->time_base) + (double)start_us / 1e6;
    }
    if (build_graph(s, tempo, trim, e->rate, message, len) < 0) {
        source_close(s);
        return -1;
    }
    return 0;
}

static int source_open(ae_engine *e, ae_source *s, const char *path, int64_t start_us, double tempo,
                       char *message, size_t len) {
    AVDictionary *options = NULL;
    int r;

    if (strncmp(path, "http://", 7) == 0 || strncmp(path, "https://", 8) == 0) {
        /* Ktor and the app own connection reuse, so FFmpeg must not keep its own HTTP connections. */
        av_dict_set(&options, "multiple_requests", "0", 0);
    }

    s->format = avformat_alloc_context();
    if (!s->format) {
        av_dict_free(&options);
        snprintf(message, len, "out of memory");
        return -1;
    }
    s->format->interrupt_callback.callback = interrupt_cb;
    s->format->interrupt_callback.opaque = e;

    r = avformat_open_input(&s->format, path, NULL, &options);
    av_dict_free(&options);
    if (r < 0) {
        s->format = NULL;
        format_error(message, len, "open", r);
        return -1;
    }
    return source_finish_open(e, s, start_us, tempo, message, len);
}

/*
 * Opens a host source. The host reports its length and seekability first, then FFmpeg demuxes the container
 * through the lease. io_open is refused, so a manifest that names another resource fails here rather than
 * reaching the network through FFmpeg's own protocol layer.
 */
static int source_open_stream(ae_engine *e, ae_source *s, const ae_stream_io *io, int64_t start_us, double tempo,
                              char *message, size_t len) {
    int64_t size = -1;
    int32_t seekable = 0;
    int32_t status;
    int r;

    s->lease.io = *io;
    s->lease.engine = e;
    s->lease.position = 0;
    s->lease.size = -1;
    s->lease.seekable = 0;
    s->leased = 1;
    /* Published before the first host call, so a command issued from now on can wake that call. */
    pthread_mutex_lock(&e->mutex);
    e->active_lease = &s->lease;
    pthread_mutex_unlock(&e->mutex);

    if (interrupt_requested(e)) {
        snprintf(message, len, "the source was interrupted");
        return -1;
    }
    status = s->lease.io.open(s->lease.io.opaque, &size, &seekable);
    if (status != 0) {
        snprintf(message, len, "%s",
                 status == AE_IO_INTERRUPTED ? "the source was interrupted" : "the source could not be opened");
        return -1;
    }
    s->lease.size = size < 0 ? -1 : size;
    s->lease.seekable = seekable != 0;

    unsigned char *buffer = av_malloc(AE_STREAM_BUFFER_BYTES);
    if (!buffer) {
        snprintf(message, len, "out of memory");
        return -1;
    }
    s->avio = avio_alloc_context(buffer, AE_STREAM_BUFFER_BYTES, 0, &s->lease, lease_read, NULL, lease_seek);
    if (!s->avio) {
        av_free(buffer);
        snprintf(message, len, "out of memory");
        return -1;
    }
    s->avio->seekable = s->lease.seekable ? AVIO_SEEKABLE_NORMAL : 0;
    /*
     * An empty whitelist refuses every protocol. avformat_open_input duplicates a non-NULL pb whitelist into the
     * input context (demux.c), so a child that reached a protocol opener anyway would still be refused, behind the
     * refused io_open. A literal is used because the AVIOContext never frees the string it points to, so a
     * duplicate allocated here would leak on every open.
     */
    s->avio->protocol_whitelist = "";

    s->format = avformat_alloc_context();
    if (!s->format) {
        snprintf(message, len, "out of memory");
        return -1;
    }
    s->format->pb = s->avio;
    s->format->flags |= AVFMT_FLAG_CUSTOM_IO;
    s->format->io_open = lease_io_open_refused;
    s->format->interrupt_callback.callback = interrupt_cb;
    s->format->interrupt_callback.opaque = e;

    r = avformat_open_input(&s->format, NULL, NULL, NULL);
    if (r < 0) {
        s->format = NULL;
        format_error(message, len, "open", r);
        return -1;
    }
    return source_finish_open(e, s, start_us, tempo, message, len);
}

/* Repositions the decoder for an accurate seek: the trim drops samples before the exact target. */
static int seek_source(ae_engine *e, ae_source *s, int64_t target_us, double tempo, char *message, size_t len) {
    int64_t timestamp = s->start_time + av_rescale_q(target_us, AV_TIME_BASE_Q, s->time_base);
    int r = av_seek_frame(s->format, s->stream_index, timestamp, AVSEEK_FLAG_BACKWARD);
    if (r < 0) {
        format_error(message, len, "seek", r);
        return -1;
    }
    avcodec_flush_buffers(s->decoder);
    double trim = (double)s->start_time * av_q2d(s->time_base) + (double)target_us / 1e6;
    return build_graph(s, tempo, trim, e->rate, message, len);
}

/*
 * Drains the decoder and the graph after the last packet, then marks the end of input. A stale
 * result (a newer command is pending) must not mark the end, or a following seek would end early.
 */
static void finish_input(ae_engine *e, ae_source *s, uint64_t epoch, char *message, size_t len) {
    int status;
    avcodec_send_packet(s->decoder, NULL);
    status = receive_frames(e, s, epoch, message, len);
    if (status == 0 && !s->flushed) {
        s->flushed = 1;
        int r = av_buffersrc_add_frame(s->buffer_src, NULL);
        if (r < 0) {
            format_error(message, len, "end of input", r);
            status = -1;
        } else {
            status = drain_sink(e, s, epoch, message, len);
        }
    }
    if (status < 0) {
        pthread_mutex_lock(&e->mutex);
        if (epoch == e->epoch) {
            fail_locked(e, AE_ERROR_DECODE, message);
        }
        pthread_mutex_unlock(&e->mutex);
        return;
    }
    if (status == 0) {
        pthread_mutex_lock(&e->mutex);
        if (epoch == e->epoch) {
            e->eof = 1;
            pthread_cond_broadcast(&e->changed);
        }
        pthread_mutex_unlock(&e->mutex);
    }
}

/* Reads and decodes one packet. Runs without the mutex; interruption ends the step early. */
static void decode_step(ae_engine *e, ae_source *s, uint64_t epoch, char *message, size_t len) {
    int r = av_read_frame(s->format, s->packet);
    if (r == AVERROR_EOF) {
        finish_input(e, s, epoch, message, len);
        return;
    }
    if (r < 0) {
        if (r == AVERROR_EXIT || interrupt_requested(e)) {
            /* An interrupted host read is not the end of the stream, so the next read must ask the host again. */
            clear_avio_latch(s);
            return;
        }
        format_error(message, len, "read", r);
        pthread_mutex_lock(&e->mutex);
        if (epoch == e->epoch) {
            fail_locked(e, AE_ERROR_DECODE, message);
        }
        pthread_mutex_unlock(&e->mutex);
        return;
    }
    if (s->packet->stream_index != s->stream_index) {
        av_packet_unref(s->packet);
        return;
    }

    for (;;) {
        r = avcodec_send_packet(s->decoder, s->packet);
        if (r != AVERROR(EAGAIN)) {
            break;
        }
        int status = receive_frames(e, s, epoch, message, len);
        if (status != 0) {
            av_packet_unref(s->packet);
            if (status < 0) {
                pthread_mutex_lock(&e->mutex);
                if (epoch == e->epoch) {
                    fail_locked(e, AE_ERROR_DECODE, message);
                }
                pthread_mutex_unlock(&e->mutex);
            }
            return;
        }
    }
    av_packet_unref(s->packet);
    if (r < 0 && r != AVERROR_EOF) {
        format_error(message, len, "decode", r);
        pthread_mutex_lock(&e->mutex);
        if (epoch == e->epoch) {
            fail_locked(e, AE_ERROR_DECODE, message);
        }
        pthread_mutex_unlock(&e->mutex);
        return;
    }
    if (receive_frames(e, s, epoch, message, len) < 0) {
        pthread_mutex_lock(&e->mutex);
        if (epoch == e->epoch) {
            fail_locked(e, AE_ERROR_DECODE, message);
        }
        pthread_mutex_unlock(&e->mutex);
    }
}

/* ------------------------------------------------------------------------------------------------
 * Producer thread
 * ---------------------------------------------------------------------------------------------- */

/* Caller holds the mutex. True when the producer has something to do right now. */
static int work_pending_locked(const ae_engine *e, const ae_source *s) {
    if (e->pending_load) {
        return 1;
    }
    if (s->format && !e->has_source) {
        return 1;
    }
    if (!s->format || !e->has_source || e->failed) {
        return 0;
    }
    if (e->pending_seek) {
        return 1;
    }
    if (!e->ready) {
        return 0;
    }
    if (!s->flushed && e->speed != s->graph_tempo) {
        return 1;
    }
    return !s->flushed && e->ring_count < e->high_water;
}

static void *producer_main(void *argument) {
    ae_engine *e = (ae_engine *)argument;
    ae_source s;
    char message[AE_ERROR_CAPACITY];

    memset(&s, 0, sizeof s);
    s.duration_us = -1;
    s.trim_seconds = -1.0;
    s.packet = av_packet_alloc();
    s.frame = av_frame_alloc();
    s.out = av_frame_alloc();

    pthread_mutex_lock(&e->mutex);
    for (;;) {
        while (!e->shutdown && !work_pending_locked(e, &s)) {
            if (s.format && e->has_source && e->ready && !e->failed && !s.flushed) {
                wait_for_output_space_locked(e);
            } else {
                pthread_cond_wait(&e->changed, &e->mutex);
            }
        }
        if (e->shutdown) {
            break;
        }

        if (e->pending_load) {
            /* A queued request is a path or a host source; a newer request has already replaced the other kind. */
            int stream = e->pending_stream;
            ae_stream_io io = {0};
            char *path = e->pending_source;
            int64_t start_us = e->pending_start_us;
            uint64_t epoch = e->epoch;
            double tempo = e->speed;
            if (stream) {
                take_pending_stream_locked(e, &io);
            }
            e->pending_source = NULL;
            e->pending_load = 0;
            atomic_store(&e->interrupt, 0);
            pthread_mutex_unlock(&e->mutex);

            source_close(&s);
            int rc;
            if (stream) {
                rc = source_open_stream(e, &s, &io, start_us, tempo, message, sizeof message);
            } else {
                rc = source_open(e, &s, path, start_us, tempo, message, sizeof message);
            }
            free(path);
            if (rc != 0) {
                /* A failed host source is released now, so its connection is not held until the next command. */
                source_close(&s);
            }

            pthread_mutex_lock(&e->mutex);
            if (epoch == e->epoch) {
                if (rc == 0) {
                    /* A seek queued while the source was opening owns the next ready transition. */
                    e->ready = e->pending_seek ? 0 : 1;
                    e->duration_us = s.duration_us;
                    e->seekable = s.seekable;
                } else {
                    fail_locked(e, AE_ERROR_OPEN, message);
                }
            }
            pthread_cond_broadcast(&e->changed);
        } else if (s.format && !e->has_source) {
            pthread_mutex_unlock(&e->mutex);
            source_close(&s);
            pthread_mutex_lock(&e->mutex);
        } else if (e->pending_seek) {
            int64_t target_us = e->pending_seek_us;
            uint64_t epoch = e->epoch;
            double tempo = e->speed;
            e->pending_seek = 0;
            atomic_store(&e->interrupt, 0);
            int ok = 0;
            if (!s.seekable) {
                snprintf(message, sizeof message, "the source is not seekable");
            } else {
                pthread_mutex_unlock(&e->mutex);
                ok = seek_source(e, &s, target_us, tempo, message, sizeof message) == 0;
                pthread_mutex_lock(&e->mutex);
            }
            if (epoch == e->epoch) {
                if (ok) {
                    /* A seek that arrived during this one keeps the source not ready until it runs. */
                    e->ready = e->pending_seek ? 0 : 1;
                    e->filling = 1;
                    e->seek_generation++;
                } else {
                    fail_locked(e, AE_ERROR_DECODE, message);
                }
            }
            pthread_cond_broadcast(&e->changed);
        } else if (!s.flushed && e->speed != s.graph_tempo) {
            /* Speed changed: drain the old graph with its own tempo, then start a new graph. */
            uint64_t epoch = e->epoch;
            double tempo = e->speed;
            pthread_mutex_unlock(&e->mutex);
            int status = 0;
            if (s.buffer_src) {
                s.flushed = 1;
                int r = av_buffersrc_add_frame(s.buffer_src, NULL);
                if (r < 0) {
                    format_error(message, sizeof message, "end of input", r);
                    status = -1;
                } else {
                    status = drain_sink(e, &s, epoch, message, sizeof message);
                }
            }
            if (status == 0 && build_graph(&s, tempo, s.trim_seconds, e->rate, message, sizeof message) < 0) {
                status = -1;
            }
            pthread_mutex_lock(&e->mutex);
            if (status < 0 && epoch == e->epoch) {
                fail_locked(e, AE_ERROR_DECODE, message);
            }
        } else {
            uint64_t epoch = e->epoch;
            e->decoding = 1;
            atomic_store(&e->interrupt, 0);
            pthread_mutex_unlock(&e->mutex);
            decode_step(e, &s, epoch, message, sizeof message);
            pthread_mutex_lock(&e->mutex);
            e->decoding = 0;
        }
    }
    pthread_mutex_unlock(&e->mutex);

    source_close(&s);
    av_packet_free(&s.packet);
    av_frame_free(&s.frame);
    av_frame_free(&s.out);
    return NULL;
}

/* ------------------------------------------------------------------------------------------------
 * Device callback and public API
 * ---------------------------------------------------------------------------------------------- */

static void device_pull(void *user, float *out, uint32_t frames) {
    ae_engine *e = (ae_engine *)user;
    pthread_mutex_lock(&e->mutex);
    consume_locked(e, out, frames, AE_CONSUME_BLOCKING);
    pthread_mutex_unlock(&e->mutex);
}

#ifndef __APPLE__
static void deadline_after(struct timespec *deadline, int64_t nanoseconds) {
    clock_gettime(CLOCK_MONOTONIC, deadline);
    int64_t total = (int64_t)deadline->tv_nsec + nanoseconds;
    deadline->tv_sec += total / 1000000000LL;
    deadline->tv_nsec = (long)(total % 1000000000LL);
}
#endif

static void engine_destroy_partial(ae_engine *e) {
    if (!e) {
        return;
    }
    free(e->ring);
    free(e->pending_source);
    free(e);
}

ae_engine *ae_engine_create(const ae_config *config, char *error, size_t error_len) {
    if (error && error_len > 0) {
        error[0] = '\0';
    }
    if (!config || config->abi_version != AE_ABI_VERSION) {
        if (error && error_len > 0) {
            snprintf(error, error_len, "playback engine ABI version mismatch");
        }
        return NULL;
    }

    uint32_t rate = config->sample_rate ? config->sample_rate : AE_DEFAULT_RATE;
    if (rate < AE_MIN_RATE || rate > AE_MAX_RATE) {
        if (error && error_len > 0) {
            snprintf(error, error_len, "unsupported output sample rate %u", rate);
        }
        return NULL;
    }

    ae_engine *e = calloc(1, sizeof *e);
    if (!e) {
        return NULL;
    }
    e->manual = config->output_mode == AE_OUTPUT_MANUAL;
    e->rate = rate;
    e->speed = 1.0;
    e->duration_us = -1;
    e->position_us = 0.0;
    atomic_init(&e->interrupt, 0);
    atomic_init(&e->advancing, 0);

    uint32_t ring_ms = config->ring_ms ? config->ring_ms : AE_DEFAULT_RING_MS;
    uint64_t capacity = (uint64_t)rate * ring_ms / 1000u;
    if (capacity < AE_MIN_RING_FRAMES) {
        capacity = AE_MIN_RING_FRAMES;
    }
    e->capacity = (uint32_t)capacity;
    e->high_water = e->capacity - e->capacity / 4u;

    uint32_t prebuffer_ms = config->prebuffer_ms ? config->prebuffer_ms : AE_DEFAULT_PREBUFFER_MS;
    uint64_t prebuffer = (uint64_t)rate * prebuffer_ms / 1000u;
    if (prebuffer > e->capacity / 2u) {
        prebuffer = e->capacity / 2u;
    }
    e->prebuffer = (uint32_t)prebuffer;

    e->ring = calloc((size_t)e->capacity * AE_CHANNELS, sizeof(float));
    if (!e->ring) {
        engine_destroy_partial(e);
        return NULL;
    }

    pthread_condattr_t attributes;
    pthread_condattr_init(&attributes);
#ifndef __APPLE__
    pthread_condattr_setclock(&attributes, CLOCK_MONOTONIC);
#endif
    pthread_mutex_init(&e->mutex, NULL);
    pthread_cond_init(&e->changed, &attributes);
    pthread_condattr_destroy(&attributes);

    if (pthread_create(&e->producer, NULL, producer_main, e) != 0) {
        pthread_cond_destroy(&e->changed);
        pthread_mutex_destroy(&e->mutex);
        engine_destroy_partial(e);
        if (error && error_len > 0) {
            snprintf(error, error_len, "could not start the playback producer thread");
        }
        return NULL;
    }

    if (config->output_mode == AE_OUTPUT_DEVICE) {
        char device_error[AE_ERROR_CAPACITY];
        e->device = ae_device_open(rate, config->backend == AE_BACKEND_NULL, device_pull, e, device_error,
                                   sizeof device_error);
        if (!e->device) {
            ae_engine_destroy(e);
            if (error && error_len > 0) {
                snprintf(error, error_len, "%s", device_error);
            }
            return NULL;
        }
    }
    return e;
}

void ae_engine_destroy(ae_engine *e) {
    if (!e) {
        return;
    }
    if (e->device) {
        ae_device_close(e->device);
        e->device = NULL;
    }
    pthread_mutex_lock(&e->mutex);
    e->shutdown = 1;
    interrupt_locked(e);
    pthread_cond_broadcast(&e->changed);
    pthread_mutex_unlock(&e->mutex);
    pthread_join(e->producer, NULL);

    /* The producer leaves without taking a queued host source, so the engine closes that source here. */
    ae_stream_io unconsumed;
    pthread_mutex_lock(&e->mutex);
    int had_unconsumed = take_pending_stream_locked(e, &unconsumed);
    pthread_mutex_unlock(&e->mutex);
    if (had_unconsumed) {
        close_stream_io(&unconsumed);
    }

    pthread_cond_destroy(&e->changed);
    pthread_mutex_destroy(&e->mutex);
    free(e->pending_source);
    free(e->ring);
    free(e);
}

/*
 * Caller holds the mutex and has already queued the request. Starts a new load generation: the producer's
 * current work belongs to the old one, so it is interrupted and its results are dropped by epoch.
 */
static uint64_t begin_load_locked(ae_engine *e, uint64_t start_us, int play_when_ready) {
    e->pending_load = 1;
    e->pending_start_us = (int64_t)start_us;
    e->pending_seek = 0;

    e->epoch++;
    e->load_generation++;
    e->has_source = 1;
    e->ready = 0;
    e->failed = 0;
    e->eof = 0;
    e->ended = 0;
    e->filling = 1;
    e->buffering = 0;
    e->advancing = 0;
    e->seekable = 0;
    e->duration_us = -1;
    e->seek_generation = 0;
    e->error_code = 0;
    e->position_us = (double)start_us;
    e->play_intent = play_when_ready ? 1 : 0;
    ring_clear_locked(e);
    interrupt_locked(e);

    uint64_t generation = e->load_generation;
    pthread_cond_broadcast(&e->changed);
    return generation;
}

uint64_t ae_engine_load(ae_engine *e, const char *source, uint64_t start_us, int play_when_ready) {
    if (!e || !source || !*source) {
        return 0;
    }
    char *copy = strdup(source);
    if (!copy) {
        return 0;
    }
    ae_stream_io stale;
    int had_stale = 0;
    pthread_mutex_lock(&e->mutex);
    if (e->shutdown) {
        pthread_mutex_unlock(&e->mutex);
        free(copy);
        return 0;
    }
    /* A path replaces a queued host source, which the engine closes once the mutex is released. */
    had_stale = take_pending_stream_locked(e, &stale);
    free(e->pending_source);
    e->pending_source = copy;
    uint64_t generation = begin_load_locked(e, start_us, play_when_ready);
    pthread_mutex_unlock(&e->mutex);
    if (had_stale) {
        close_stream_io(&stale);
    }
    return generation;
}

uint64_t ae_engine_load_stream(ae_engine *e, const ae_stream_io *io, uint64_t start_us, int play_when_ready) {
    if (!io || io->abi_version != AE_STREAM_IO_VERSION) {
        /* A layout this engine does not know cannot be called safely, so the caller keeps its resources. */
        return 0;
    }
    ae_stream_io owned = *io;
    if (!e || !owned.open || !owned.read || !owned.seek || !owned.close) {
        close_stream_io(&owned);
        return 0;
    }
    ae_stream_io stale;
    int had_stale = 0;
    pthread_mutex_lock(&e->mutex);
    if (e->shutdown) {
        pthread_mutex_unlock(&e->mutex);
        close_stream_io(&owned);
        return 0;
    }
    had_stale = take_pending_stream_locked(e, &stale);
    free(e->pending_source);
    e->pending_source = NULL;
    e->pending_stream = 1;
    e->pending_io = owned;
    uint64_t generation = begin_load_locked(e, start_us, play_when_ready);
    pthread_mutex_unlock(&e->mutex);
    if (had_stale) {
        close_stream_io(&stale);
    }
    return generation;
}

void ae_engine_play(ae_engine *e) {
    if (!e) {
        return;
    }
    pthread_mutex_lock(&e->mutex);
    if (e->has_source) {
        e->play_intent = 1;
        if (!e->eof && e->ring_count < e->prebuffer) {
            e->filling = 1;
        }
        pthread_cond_broadcast(&e->changed);
    }
    pthread_mutex_unlock(&e->mutex);
}

void ae_engine_pause(ae_engine *e) {
    if (!e) {
        return;
    }
    pthread_mutex_lock(&e->mutex);
    e->play_intent = 0;
    pthread_mutex_unlock(&e->mutex);
}

void ae_engine_seek(ae_engine *e, uint64_t position_us) {
    if (!e) {
        return;
    }
    pthread_mutex_lock(&e->mutex);
    if (e->has_source && !e->failed && (!e->ready || e->seekable)) {
        int64_t target = (int64_t)position_us;
        if (e->duration_us >= 0 && target > e->duration_us) {
            target = e->duration_us;
        }
        e->position_us = (double)target;
        e->ended = 0;
        e->eof = 0;
        if (e->pending_load) {
            /* The source has not been opened yet, so opening at the target is the seek. */
            e->pending_start_us = target;
        } else {
            e->pending_seek = 1;
            e->pending_seek_us = target;
            e->ready = 0;
            e->filling = 1;
            ring_clear_locked(e);
            if (e->decoding) {
                interrupt_locked(e);
            }
        }
        pthread_cond_broadcast(&e->changed);
    }
    pthread_mutex_unlock(&e->mutex);
}

void ae_engine_set_speed(ae_engine *e, float speed) {
    if (!e || !isfinite(speed) || speed <= 0.0f) {
        return;
    }
    double clamped = speed;
    if (clamped < AE_SPEED_MIN) {
        clamped = AE_SPEED_MIN;
    }
    if (clamped > AE_SPEED_MAX) {
        clamped = AE_SPEED_MAX;
    }
    pthread_mutex_lock(&e->mutex);
    e->speed = clamped;
    pthread_cond_broadcast(&e->changed);
    pthread_mutex_unlock(&e->mutex);
}

void ae_engine_stop(ae_engine *e) {
    if (!e) {
        return;
    }
    ae_stream_io stale;
    int had_stale = 0;
    pthread_mutex_lock(&e->mutex);
    e->epoch++;
    e->load_generation++;
    free(e->pending_source);
    e->pending_source = NULL;
    e->pending_load = 0;
    had_stale = take_pending_stream_locked(e, &stale);
    e->pending_seek = 0;
    e->has_source = 0;
    e->ready = 0;
    e->failed = 0;
    e->eof = 0;
    e->ended = 0;
    e->filling = 0;
    e->buffering = 0;
    e->advancing = 0;
    e->seekable = 0;
    e->duration_us = -1;
    e->seek_generation = 0;
    e->position_us = 0.0;
    e->play_intent = 0;
    ring_clear_locked(e);
    interrupt_locked(e);
    pthread_cond_broadcast(&e->changed);
    pthread_mutex_unlock(&e->mutex);
    if (had_stale) {
        close_stream_io(&stale);
    }
}

void ae_engine_snapshot(ae_engine *e, ae_snapshot *out) {
    if (!e || !out) {
        return;
    }
    pthread_mutex_lock(&e->mutex);

    uint32_t flags = 0;
    if (e->has_source) {
        flags |= AE_FLAG_HAS_SOURCE;
        if (e->seekable && e->duration_us >= 0) {
            flags |= AE_FLAG_SEEKABLE;
        }
    }
    if (e->play_intent) {
        flags |= AE_FLAG_PLAY_WHEN_READY;
    }
    if (e->advancing) {
        flags |= AE_FLAG_ADVANCING;
    }
    /* Opening and refilling count as buffering even before the consumer has run to say so. */
    int buffering = e->buffering;
    if (e->has_source && !e->failed && !e->ended && e->play_intent) {
        if (!e->ready || (e->filling && !e->eof && e->ring_count < e->prebuffer)) {
            buffering = 1;
        }
    }
    if (buffering) {
        flags |= AE_FLAG_BUFFERING;
    }
    if (e->ended) {
        flags |= AE_FLAG_ENDED;
    }

    double buffered = e->position_us;
    for (uint32_t i = 0; i < e->seg_count; i++) {
        uint32_t slot = (e->seg_head + i) % AE_SEGMENTS;
        buffered += frames_to_us((double)e->seg_frames[slot], e->seg_tempo[slot], e->rate);
    }

    out->flags = flags;
    out->error_code = e->error_code;
    out->load_generation = e->load_generation;
    out->seek_generation = e->seek_generation;
    out->error_sequence = e->error_sequence;
    out->position_us = (int64_t)llround(e->position_us);
    out->duration_us = e->has_source ? e->duration_us : -1;
    out->buffered_us = e->has_source ? (int64_t)llround(buffered) : -1;
    out->playback_speed = (float)e->speed;

    pthread_mutex_unlock(&e->mutex);
}

size_t ae_engine_last_error(ae_engine *e, char *buffer, size_t buffer_len) {
    if (!e) {
        return 0;
    }
    pthread_mutex_lock(&e->mutex);
    size_t length = strlen(e->error_text);
    if (buffer && buffer_len > 0) {
        size_t copy = length < buffer_len - 1 ? length : buffer_len - 1;
        memcpy(buffer, e->error_text, copy);
        buffer[copy] = '\0';
    }
    pthread_mutex_unlock(&e->mutex);
    return length;
}

uint32_t ae_engine_render(ae_engine *e, float *out, uint32_t frames) {
    if (!e || !out || !e->manual) {
        return 0;
    }
    pthread_mutex_lock(&e->mutex);
    uint32_t chunk = e->capacity / 2u;
    uint32_t total = 0;
    while (total < frames) {
        uint32_t need = min_u32(frames - total, chunk);
        while (!e->shutdown && manual_wait_needed_locked(e, need)) {
            if (wait_changed_locked(e, AE_RENDER_TIMEOUT_NS) != 0) {
                break;
            }
        }
        consume_locked(e, out + (size_t)total * AE_CHANNELS, need, AE_CONSUME_BLOCKING);
        total += need;
    }
    pthread_mutex_unlock(&e->mutex);
    return frames;
}

uint32_t ae_engine_render_realtime(ae_engine *e, float *out, uint32_t frames) {
    if (e && e->manual) atomic_store(&e->advancing, 0);
    if (!out) return 0;
    if (!e || !e->manual || frames == 0 || pthread_mutex_trylock(&e->mutex) != 0) {
        silence(out, frames);
        return 0;
    }
    uint32_t taken = consume_locked(e, out, frames, AE_CONSUME_REALTIME);
    pthread_mutex_unlock(&e->mutex);
    return taken;
}

/*
 * Two-deck mixer pull for a manual deck. It takes decoded PCM under the deck's own lock with the mixed
 * gate, so a deck that is still prebuffering contributes silence and its position does not move. The
 * mixer owns the device and calls this from its callback; the deck producer polls for space rather than
 * being woken, which keeps the callback free of condition-variable calls.
 */
uint32_t ae_engine_pull_mixed(ae_engine *e, float *out, uint32_t frames) {
    if (!e || !out) {
        return 0;
    }
    pthread_mutex_lock(&e->mutex);
    uint32_t taken = consume_locked(e, out, frames, AE_CONSUME_MIXED);
    pthread_mutex_unlock(&e->mutex);
    return taken;
}
