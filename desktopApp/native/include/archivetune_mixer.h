/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

/*
 * Versioned C ABI of the two-deck output mixer.
 *
 * A mixer owns one miniaudio output device and two manual engine decks. The device callback is the only
 * clock: every delivered frame advances the mixer timeline, and handoffs are scheduled on that timeline in
 * output frames. The gains of a handoff follow the shared-domain equal-power envelope, so an overlap is
 * sample-accurate however the device splits its callbacks. Equalizer sections and per-deck gains are
 * applied after the handoff. Bypass passes the active deck through untouched and refuses handoffs.
 *
 * Host obligations: call ae_mixer_destroy only after no other thread can call into the mixer. Decks
 * returned by ae_mixer_deck are borrowed and stay valid until destroy. Each deck is an ordinary engine,
 * so load/play/pause/seek/speed/stop/snapshot use the ae_engine_* functions.
 */
#ifndef ARCHIVETUNE_MIXER_H
#define ARCHIVETUNE_MIXER_H

#include <stddef.h>
#include <stdint.h>

#include "archivetune_engine.h"
#include "archivetune_output.h"

#ifdef __cplusplus
extern "C" {
#endif

/* Biquad sections the mixer can hold. A host with more active bands must refuse the equalizer, not drop bands. */
#define AE_MIXER_MAX_BANDS 16u

typedef struct ae_mixer ae_mixer;

typedef struct ae_mixer_config {
    uint32_t abi_version;   /* must equal AE_ABI_VERSION */
    int32_t backend;        /* ae_backend of the single output device */
    uint32_t sample_rate;   /* output rate; 0 selects 48 kHz */
    uint32_t ring_ms;       /* decoded lookahead per deck; 0 selects the engine default */
    uint32_t prebuffer_ms;  /* audio a deck needs before it may be consumed; 0 selects the engine default */
} ae_mixer_config;

/* One normalized biquad section, H(z) = (b0 + b1 z^-1 + b2 z^-2) / (1 + a1 z^-1 + a2 z^-2). */
typedef struct ae_biquad {
    double b0;
    double b1;
    double b2;
    double a1;
    double a2;
} ae_biquad;

typedef enum ae_mixer_result {
    AE_MIXER_OK = 0,
    /* The request conflicts with the schedule, the mixer is bypassed, or a value is out of range. */
    AE_MIXER_REJECTED = 1,
} ae_mixer_result;

typedef struct ae_mixer_state {
    uint64_t timeline_frames; /* output frames delivered to the device: the only clock the schedule uses */
    uint64_t commit_sequence; /* increments each time the incoming deck becomes the active deck */
    uint64_t start_frame;     /* armed handoff start on the timeline; 0 when nothing is armed */
    uint64_t end_frame;       /* armed handoff end on the timeline; 0 when nothing is armed */
    uint32_t active_deck;     /* 0 or 1 */
    uint32_t incoming_deck;   /* 0 or 1; meaningful only while armed */
    uint32_t armed;           /* 1 while a handoff is scheduled */
    uint32_t bypass;          /* 1 while mixing and software stages are bypassed */
    uint32_t band_count;      /* active equalizer sections */
    /*
     * The active deck, read in the same locked step as the timeline: its consumed media position, its
     * duration (-1 when unknown) and its playback speed. A host schedules a handoff from these values.
     */
    int64_t active_position_us;
    int64_t active_duration_us;
    float active_speed;
} ae_mixer_state;

/* Returns NULL and writes a NUL-terminated message into error when the output cannot start. */
AE_EXPORT ae_mixer *ae_mixer_create(const ae_mixer_config *config, char *error, size_t error_len);
AE_EXPORT ae_mixer *ae_mixer_create_output(const ae_mixer_config *config, int exclusive, const char *device_id,
                                         char *error, size_t error_len);

typedef struct ae_mixer_output_state {
    ae_pcm_format input;
    ae_device_state sink;
    uint32_t requested, direct_pcm, muted;
    char reason[512];
} ae_mixer_output_state;

/* Output control calls share one host command owner; never call them from the audio callback. */
AE_EXPORT void ae_mixer_set_output(ae_mixer *mixer, int exclusive, const char *device_id);
AE_EXPORT void ae_mixer_prepare_track(ae_mixer *mixer);
AE_EXPORT void ae_mixer_output_tick(ae_mixer *mixer);
AE_EXPORT void ae_mixer_output_snapshot(ae_mixer *mixer, ae_mixer_output_state *out);
AE_EXPORT void ae_mixer_output_enabled(ae_mixer *mixer, int enabled);
AE_EXPORT uint32_t ae_mixer_output_devices(ae_device_description *out, uint32_t capacity, char *error, size_t error_len);

/* Stops the device before the decks, so no callback can run during teardown. */
AE_EXPORT void ae_mixer_destroy(ae_mixer *mixer);

/* Borrowed engine for deck 0 or 1, valid until destroy. Returns NULL for any other index. */
AE_EXPORT ae_engine *ae_mixer_deck(ae_mixer *mixer, uint32_t deck);

/*
 * Schedules deck `incoming` to take over at start_frame on the timeline and to fade over overlap_frames
 * (0 is a gapless switch). Rejected when a handoff is already armed, when `incoming` is the active deck,
 * when start_frame is already in the past, when the mixer is bypassed, or when the deck index is invalid.
 */
AE_EXPORT int ae_mixer_arm(ae_mixer *mixer, uint32_t incoming, uint64_t start_frame, uint64_t overlap_frames);

/* Drops an armed handoff that has not started. The active deck keeps the output. */
AE_EXPORT void ae_mixer_cancel(ae_mixer *mixer);

/*
 * Makes an armed incoming deck active at once, for a transport command during a fade. Returns 1 when a
 * handoff was promoted, 0 when nothing was armed.
 */
AE_EXPORT int ae_mixer_promote(ae_mixer *mixer);

/* Linear gain applied to one deck before mixing. Values are clamped to [0, 16]; non-finite values reset to 1. */
AE_EXPORT void ae_mixer_set_deck_gain(ae_mixer *mixer, uint32_t deck, float gain);

/* Bypass disables the gains, equalizer and handoffs and cancels an armed handoff. */
AE_EXPORT void ae_mixer_set_bypass(ae_mixer *mixer, int bypass);

/*
 * Replaces the equalizer. Keeping the same band count keeps the filter history, so a retune does not click;
 * a different count resets it. Rejected when band_count exceeds AE_MIXER_MAX_BANDS.
 */
AE_EXPORT int ae_mixer_set_equalizer(ae_mixer *mixer, uint32_t band_count, const ae_biquad *bands);

AE_EXPORT void ae_mixer_snapshot(ae_mixer *mixer, ae_mixer_state *out);

#ifdef __cplusplus
}
#endif

#endif /* ARCHIVETUNE_MIXER_H */
