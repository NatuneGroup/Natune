/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

/*
 * Versioned C ABI of the desktop playback engine.
 *
 * The engine decodes local files and HTTP/HLS/DASH sources with FFmpeg and renders them through
 * miniaudio. Every function is safe to call from any thread: the engine serialises commands under
 * one mutex, and the producer thread never runs while that mutex is held. Positions are measured in
 * microseconds of media time, counted only for audio that has been rendered.
 */
#ifndef ARCHIVETUNE_ENGINE_H
#define ARCHIVETUNE_ENGINE_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define AE_ABI_VERSION 1u

#if defined(_WIN32)
#define AE_EXPORT __declspec(dllexport)
#else
#define AE_EXPORT __attribute__((visibility("default")))
#endif

typedef struct ae_engine ae_engine;

typedef enum ae_output_mode {
    /* The caller pulls audio with ae_engine_render. Used by tests to get deterministic timing. */
    AE_OUTPUT_MANUAL = 0,
    /* A miniaudio device pulls audio on its own thread. */
    AE_OUTPUT_DEVICE = 1,
} ae_output_mode;

typedef enum ae_backend {
    /* The platform audio backend that miniaudio selects. */
    AE_BACKEND_DEFAULT = 0,
    /* A silent backend that still runs on a clock, for hosts without an audio device. */
    AE_BACKEND_NULL = 1,
} ae_backend;

typedef struct ae_config {
    uint32_t abi_version;     /* must equal AE_ABI_VERSION */
    int32_t output_mode;      /* ae_output_mode */
    int32_t backend;          /* ae_backend, used in device mode only */
    uint32_t sample_rate;     /* output rate in manual mode, requested rate in device mode */
    uint32_t ring_ms;         /* decoded audio the engine may hold ahead of the output */
    uint32_t prebuffer_ms;    /* audio required before playback starts or resumes after a stall */
} ae_config;

enum ae_flag {
    AE_FLAG_HAS_SOURCE = 1u << 0,
    AE_FLAG_PLAY_WHEN_READY = 1u << 1,
    AE_FLAG_ADVANCING = 1u << 2,
    AE_FLAG_BUFFERING = 1u << 3,
    AE_FLAG_ENDED = 1u << 4,
    AE_FLAG_SEEKABLE = 1u << 5,
};

typedef enum ae_error {
    AE_ERROR_NONE = 0,
    AE_ERROR_OPEN = 1,
    AE_ERROR_DECODE = 2,
    AE_ERROR_OUTPUT = 3,
} ae_error;

typedef struct ae_snapshot {
    uint32_t flags;            /* combination of ae_flag */
    uint32_t error_code;       /* ae_error of the newest failure for the current load */
    uint64_t load_generation;  /* increments on every accepted load and stop */
    uint64_t seek_generation;  /* increments on every seek or repeat since the load */
    uint64_t error_sequence;   /* increments on every failure since the engine was created */
    int64_t position_us;       /* audible media position */
    int64_t duration_us;       /* -1 when the container does not report a duration */
    int64_t buffered_us;       /* media time decoded ahead of the position, -1 when no source */
    float playback_speed;      /* effective speed after clamping */
} ae_snapshot;

/* Returns NULL and writes a NUL-terminated message into error when the engine cannot start. */
AE_EXPORT ae_engine *ae_engine_create(const ae_config *config, char *error, size_t error_len);
AE_EXPORT void ae_engine_destroy(ae_engine *engine);

/*
 * Replaces the current source. Returns the new load generation, or 0 when the request is rejected.
 * Opening happens on the producer thread, so a slow network source does not block the caller;
 * failures show up in the snapshot.
 */
AE_EXPORT uint64_t ae_engine_load(ae_engine *engine, const char *source, uint64_t start_us, int play_when_ready);
AE_EXPORT void ae_engine_play(ae_engine *engine);
AE_EXPORT void ae_engine_pause(ae_engine *engine);
AE_EXPORT void ae_engine_seek(ae_engine *engine, uint64_t position_us);
AE_EXPORT void ae_engine_set_speed(ae_engine *engine, float speed);
AE_EXPORT void ae_engine_stop(ae_engine *engine);
AE_EXPORT void ae_engine_snapshot(ae_engine *engine, ae_snapshot *out);

/* Copies the newest failure message and returns its length without the terminating NUL. */
AE_EXPORT size_t ae_engine_last_error(ae_engine *engine, char *buffer, size_t buffer_len);

/* Manual mode only: fills frames of interleaved stereo float samples and returns the frame count. */
AE_EXPORT uint32_t ae_engine_render(ae_engine *engine, float *out, uint32_t frames);

/*
 * Nonwaiting manual output: takes only currently decoded PCM, without the prebuffer gate.
 * Writes `frames` interleaved stereo frames, zero-filling any unconsumed tail; returns the number
 * of decoded frames actually consumed. Invalid engine/mode or mutex contention yields silence/0.
 * NULL out yields 0. No allocation, producer notification, decode, condition wait or blocking lock.
 * The host owns a live engine and frames*2 writable floats, serializes output callbacks, and must
 * stop/join them before destroy (as for the blocking/manual and device output APIs).
 */
AE_EXPORT uint32_t ae_engine_render_realtime(ae_engine *engine, float *out, uint32_t frames);

/*
 * Host byte sources ("leases"). A source the engine cannot open by itself, such as a request that must
 * carry headers, a provider-decrypted stream or a range-reading cache, is read through these callbacks.
 * FFmpeg never opens a child of a lease (a manifest segment or a key, for example) through its own
 * protocol layer, so every request stays on the host's transport and policy.
 *
 * Threads: open, read, seek and close run on the engine's producer thread, never on a realtime thread.
 * `interrupt` runs on the thread that issues a command while the engine mutex is held, so it must return
 * at once and must not call into the engine. It may be NULL, but then every host call must return within a
 * short bounded time, because ae_engine_destroy and every seek wait for the producer to leave the call.
 *
 * Ownership: once ae_engine_load_stream accepts a struct, the engine calls `close` exactly once, including
 * when the request is then rejected (the rejection returns 0 without any other call). A struct whose
 * abi_version differs from AE_STREAM_IO_VERSION is refused before any callback, so the caller keeps its
 * resources. The host must not use `opaque` after `close` returns.
 *
 * Results: `open` writes the total length (-1 when unknown) and whether seeking is supported, then
 * returns 0 or a negative AE_IO_* code. `read` returns the bytes written (1 to length), AE_IO_EOF at the
 * end of the source, or a negative code. `seek` returns the position reached, which must equal the
 * requested one, or a negative code. AE_IO_INTERRUPTED means `interrupt` woke the call; the source stays
 * usable, so the next call starts fresh. A host that keeps answering AE_IO_INTERRUPTED when no interrupt is
 * pending has stopped producing: the engine fails the load after a bounded number of consecutive such reads
 * rather than spinning. AE_IO_RETRY means nothing has arrived yet; the engine asks again
 * until its starvation limit and then reports a decode failure. AE_IO_FAILED is permanent.
 *
 * The engine checks for an interrupt before each call, so an interrupt that lands between that check and a
 * host call wakes nothing. Hosts therefore answer AE_IO_RETRY after a short bounded wait rather than blocking
 * for long, and the engine checks again on the next slice.
 */
#define AE_STREAM_IO_VERSION 1u
#define AE_IO_EOF 0
#define AE_IO_RETRY (-1)
#define AE_IO_FAILED (-2)
#define AE_IO_INTERRUPTED (-3)

typedef struct ae_stream_io {
    uint32_t abi_version;                /* must equal AE_STREAM_IO_VERSION */
    void *opaque;                        /* passed back to every callback */
    int32_t (*open)(void *opaque, int64_t *size, int32_t *seekable);
    int32_t (*read)(void *opaque, uint8_t *buffer, uint32_t length);
    int64_t (*seek)(void *opaque, int64_t position);
    void (*interrupt)(void *opaque);     /* may be NULL when the host cannot wake a blocked call */
    void (*close)(void *opaque);
} ae_stream_io;

/*
 * Queues a host source for the next load, with the replacement rules of ae_engine_load: a newer request
 * replaces a queued one, and the replaced host source is closed. Returns the load generation, or 0 when
 * the engine refuses the request (see the ownership rules above). `io` is copied, so the caller's struct
 * need not outlive the call.
 */
AE_EXPORT uint64_t ae_engine_load_stream(ae_engine *engine, const ae_stream_io *io, uint64_t start_us,
                                         int play_when_ready);

#ifdef __cplusplus
}
#endif

#endif /* ARCHIVETUNE_ENGINE_H */
