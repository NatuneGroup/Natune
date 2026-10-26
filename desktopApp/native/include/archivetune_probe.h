/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

/*
 * Source-rate PCM probe for the desktop spectral authenticity check.
 *
 * A probe owns a private demuxer and decoder. It never touches a playback engine, deck or mixer, so running
 * it cannot change what the output device plays or whether the output is bit-perfect. It decodes the first
 * audio stream at the rate and channel count the source reports, with no resampling and no tempo change. The
 * playback engine resamples every deck to the output rate, so spectral analysis must read this probe and never
 * engine output.
 *
 * A handle serves one read at a time. ae_probe_cancel may be called from any thread and makes the read in
 * progress fail with AE_PROBE_CANCELLED. Cancellation is sticky: once it is called, every later read on the
 * handle also fails with AE_PROBE_CANCELLED, so a cancelled probe is destroyed rather than reused. The owner
 * must wait for ae_probe_read to return before it calls ae_probe_destroy. A read is bounded by the frame
 * capacity of the caller's buffer, by max_frames and by a packet budget, so a source without enough audio
 * cannot keep a probe busy.
 */
#ifndef ARCHIVETUNE_PROBE_H
#define ARCHIVETUNE_PROBE_H

#include <stddef.h>
#include <stdint.h>

#include "archivetune_engine.h" /* AE_EXPORT */

#ifdef __cplusplus
extern "C" {
#endif

#define AE_PROBE_CODEC_NAME_CAPACITY 32u
#define AE_PROBE_ERROR_CAPACITY 256u

typedef enum ae_probe_status {
    AE_PROBE_OK = 0,
    AE_PROBE_CANCELLED = 1, /* the read was interrupted by ae_probe_cancel */
    AE_PROBE_FAILED = 2,    /* the source could not be opened, probed or decoded */
    AE_PROBE_INVALID = 3,   /* the arguments cannot describe a read */
} ae_probe_status;

typedef struct ae_probe_info {
    uint32_t frames;                 /* interleaved frames written to out, possibly zero */
    uint32_t channels;               /* channel count the decoder produced, 1 to 8 */
    uint32_t sample_rate;            /* PCM rate the decoder produced, in Hz */
    uint32_t advertised_sample_rate; /* rate the stream parameters report, in Hz; 0 when they report none */
    uint32_t end_of_source;          /* 1 when the source ended before the requested frames were produced */
} ae_probe_info;

typedef struct ae_probe ae_probe;

AE_EXPORT ae_probe *ae_probe_create(void);
AE_EXPORT void ae_probe_destroy(ae_probe *probe);
AE_EXPORT void ae_probe_cancel(ae_probe *probe);

/*
 * Decodes the first audio stream of source, starting at or before start_us, into out as interleaved float
 * samples at the stream's own channel count. At most max_frames frames are produced, and never more than
 * out_capacity_samples / channels whole frames. info is zeroed first and filled only on AE_PROBE_OK.
 * A NUL-terminated message is written to error when it is not NULL.
 */
AE_EXPORT ae_probe_status ae_probe_read(ae_probe *probe, const char *source, uint64_t start_us, uint32_t max_frames,
                                        float *out, size_t out_capacity_samples, ae_probe_info *info, char *error,
                                        size_t error_len);

/* Copies the FFmpeg codec name of the last read into buffer and returns its length, or 0 when none was opened. */
AE_EXPORT size_t ae_probe_codec_name(ae_probe *probe, char *buffer, size_t buffer_len);

#ifdef __cplusplus
}
#endif

#endif /* ARCHIVETUNE_PROBE_H */
