/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

/*
 * Two-deck output mixer.
 *
 * The miniaudio callback pulls from the mixer. The mixer reads the active deck, or both decks while a
 * handoff overlaps, and advances a sample timeline by the frames it delivers. Nothing else measures time,
 * so a handoff lands on the same frame whatever the device's callback size is. The fade gains are the
 * shared-domain equal-power envelope evaluated per frame, and the equalizer and deck gains are applied
 * after the handoff, so a bypassed chain passes the decoded samples through unchanged.
 *
 * Locking: one mixer lock covers the schedule, the gains, the filter state and the timeline. The device
 * callback holds it while it pulls from a deck, so the lock order is always mixer, then deck; no deck call
 * ever takes the mixer lock back. Decks hold their own lock only in short sections (their producer never
 * decodes under it), so control calls wait briefly at most. A snapshot reads the active deck under the mixer
 * lock, so its position is exactly the media time consumed through the reported timeline frame.
 */

#include "archivetune_device.h"
#include "archivetune_engine.h"
#include "archivetune_engine_internal.h"
#include "archivetune_mixer.h"

#include <math.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define AE_MIXER_CHANNELS 2u
/* Callback frames are split into chunks of this size, so the scratch buffers need no allocation. */
#define AE_MIXER_CHUNK_FRAMES 512u
#define AE_MIXER_DEFAULT_RATE 48000u
#define AE_MIXER_MIN_RATE 8000u
#define AE_MIXER_MAX_RATE 192000u
#define AE_MIXER_MAX_GAIN 16.0f
#define AE_MIXER_ERROR_CAPACITY 256u
#define AE_MIXER_DECKS 2u
#define AE_MIXER_HALF_PI 1.57079632679489661923

struct ae_mixer {
    pthread_mutex_t mutex;
    ae_device *device;
    ae_engine *deck[AE_MIXER_DECKS];
    uint32_t rate;

    uint64_t timeline;         /* output frames delivered so far */
    uint64_t commit_sequence;  /* bumps whenever the incoming deck becomes active */
    uint32_t active;
    uint32_t incoming;
    int armed;
    uint64_t start;            /* first timeline frame of the handoff */
    uint64_t end;              /* first timeline frame after the handoff, equal to start for a switch */
    int bypass;

    float deck_gain[AE_MIXER_DECKS];
    uint32_t band_count;
    ae_biquad bands[AE_MIXER_MAX_BANDS];
    double z1[AE_MIXER_MAX_BANDS][AE_MIXER_CHANNELS];
    double z2[AE_MIXER_MAX_BANDS][AE_MIXER_CHANNELS];

    float outgoing_samples[AE_MIXER_CHUNK_FRAMES * AE_MIXER_CHANNELS];
    float incoming_samples[AE_MIXER_CHUNK_FRAMES * AE_MIXER_CHANNELS];
};

static uint32_t min_u32(uint32_t a, uint32_t b) { return a < b ? a : b; }
static uint64_t min_u64(uint64_t a, uint64_t b) { return a < b ? a : b; }
static uint64_t max_u64(uint64_t a, uint64_t b) { return a > b ? a : b; }

static void set_error(char *error, size_t error_len, const char *message) {
    if (error && error_len > 0) {
        snprintf(error, error_len, "%s", message);
    }
}

/* A NaN never reaches the device, and every sample stays inside full scale. */
static float clamp_sample(float value) {
    if (isnan(value)) {
        return 0.0f;
    }
    if (value > 1.0f) {
        return 1.0f;
    }
    if (value < -1.0f) {
        return -1.0f;
    }
    return value;
}

static void clamp_block(float *samples, uint32_t count) {
    for (uint32_t i = 0; i < count; i++) {
        samples[i] = clamp_sample(samples[i]);
    }
}

static void scale_block(float *samples, uint32_t count, float gain) {
    if (gain == 1.0f) {
        return;
    }
    for (uint32_t i = 0; i < count; i++) {
        samples[i] *= gain;
    }
}

static void reset_filters_locked(struct ae_mixer *m) {
    memset(m->z1, 0, sizeof m->z1);
    memset(m->z2, 0, sizeof m->z2);
}

/*
 * Biquad sections in series, transposed direct form II per channel. This is the recursion BiquadFilter runs
 * in shared-domain: y = b0·x + z1; z1 = b1·x − a1·y + z2; z2 = b2·x − a2·y.
 */
static void equalize_block_locked(struct ae_mixer *m, float *samples, uint32_t frames) {
    for (uint32_t band = 0; band < m->band_count; band++) {
        const ae_biquad *k = &m->bands[band];
        for (uint32_t frame = 0; frame < frames; frame++) {
            for (uint32_t channel = 0; channel < AE_MIXER_CHANNELS; channel++) {
                float *sample = &samples[(size_t)frame * AE_MIXER_CHANNELS + channel];
                double x = (double)*sample;
                double y = k->b0 * x + m->z1[band][channel];
                m->z1[band][channel] = k->b1 * x - k->a1 * y + m->z2[band][channel];
                m->z2[band][channel] = k->b2 * x - k->a2 * y;
                *sample = (float)y;
            }
        }
    }
}

/*
 * Equal-power gains at one timeline frame, matching EqualPowerEnvelope.outgoingGainAt and incomingGainAt.
 * A zero-length handoff never reaches the division, because frame >= end is checked first.
 */
static void envelope_at(uint64_t frame, uint64_t start, uint64_t end, float *outgoing, float *incoming) {
    if (frame < start) {
        *outgoing = 1.0f;
        *incoming = 0.0f;
    } else if (frame >= end) {
        *outgoing = 0.0f;
        *incoming = 1.0f;
    } else {
        double progress = (double)(frame - start) / (double)(end - start);
        *outgoing = (float)cos(progress * AE_MIXER_HALF_PI);
        *incoming = (float)sin(progress * AE_MIXER_HALF_PI);
    }
}

static void commit_locked(struct ae_mixer *m) {
    m->active = m->incoming;
    m->armed = 0;
    m->commit_sequence++;
}

/* Steady state: the active deck alone, through the software stages unless they are bypassed. */
static void mix_single_locked(struct ae_mixer *m, float *out, uint32_t frames) {
    uint32_t deck = m->active;
    ae_engine_pull_mixed(m->deck[deck], out, frames);
    if (!m->bypass) {
        scale_block(out, frames * AE_MIXER_CHANNELS, m->deck_gain[deck]);
        equalize_block_locked(m, out, frames);
        clamp_block(out, frames * AE_MIXER_CHANNELS);
    }
}

/*
 * A chunk that overlaps the armed handoff. The outgoing deck supplies the frames before the handoff ends and
 * the incoming deck supplies the frames from its start on. Each deck is read exactly at its own timeline
 * positions, so an idle deck neither advances nor leaks samples into the other deck's timeline.
 */
static void mix_handoff_locked(struct ae_mixer *m, float *out, uint32_t frames) {
    uint64_t t0 = m->timeline;
    uint32_t outgoing_deck = m->active;
    uint32_t incoming_deck = m->incoming;
    uint64_t start = m->start;
    uint64_t end = m->end;
    /* The first timeline frame in this chunk that the incoming deck contributes to. */
    uint64_t mix_start = max_u64(start, t0);

    uint32_t outgoing_frames = end > t0 ? (uint32_t)min_u64(end - t0, frames) : 0u;
    uint32_t incoming_frames = t0 + frames > mix_start ? (uint32_t)(t0 + frames - mix_start) : 0u;

    if (outgoing_frames > 0) {
        ae_engine_pull_mixed(m->deck[outgoing_deck], m->outgoing_samples, outgoing_frames);
    }
    if (incoming_frames > 0) {
        ae_engine_pull_mixed(m->deck[incoming_deck], m->incoming_samples, incoming_frames);
    }

    float outgoing_gain = m->deck_gain[outgoing_deck];
    float incoming_gain = m->deck_gain[incoming_deck];
    for (uint32_t i = 0; i < frames; i++) {
        uint64_t frame = t0 + i;
        float weight_out;
        float weight_in;
        envelope_at(frame, start, end, &weight_out, &weight_in);
        int has_out = i < outgoing_frames;
        int has_in = frame >= mix_start;
        uint32_t in_index = has_in ? (uint32_t)(frame - mix_start) : 0u;
        for (uint32_t c = 0; c < AE_MIXER_CHANNELS; c++) {
            float a = has_out ? m->outgoing_samples[(size_t)i * AE_MIXER_CHANNELS + c] * outgoing_gain : 0.0f;
            float b = has_in ? m->incoming_samples[(size_t)in_index * AE_MIXER_CHANNELS + c] * incoming_gain : 0.0f;
            out[(size_t)i * AE_MIXER_CHANNELS + c] = a * weight_out + b * weight_in;
        }
    }
    equalize_block_locked(m, out, frames);
    clamp_block(out, frames * AE_MIXER_CHANNELS);
}

static void mix_chunk_locked(struct ae_mixer *m, float *out, uint32_t frames) {
    uint64_t t0 = m->timeline;
    if (m->armed && !m->bypass) {
        mix_handoff_locked(m, out, frames);
    } else {
        mix_single_locked(m, out, frames);
    }
    m->timeline = t0 + frames;
    if (m->armed && m->timeline >= m->end) {
        commit_locked(m);
    }
}

/* Audio thread. Splits any callback size into chunks and holds the mixer lock for the whole callback. */
static void mixer_pull(void *user, float *out, uint32_t frames) {
    struct ae_mixer *m = (struct ae_mixer *)user;
    pthread_mutex_lock(&m->mutex);
    uint32_t done = 0;
    while (done < frames) {
        uint32_t chunk = min_u32(frames - done, AE_MIXER_CHUNK_FRAMES);
        mix_chunk_locked(m, out + (size_t)done * AE_MIXER_CHANNELS, chunk);
        done += chunk;
    }
    pthread_mutex_unlock(&m->mutex);
}

ae_mixer *ae_mixer_create(const ae_mixer_config *config, char *error, size_t error_len) {
    if (error && error_len > 0) {
        error[0] = '\0';
    }
    if (!config || config->abi_version != AE_ABI_VERSION) {
        set_error(error, error_len, "playback mixer ABI version mismatch");
        return NULL;
    }
    uint32_t rate = config->sample_rate ? config->sample_rate : AE_MIXER_DEFAULT_RATE;
    if (rate < AE_MIXER_MIN_RATE || rate > AE_MIXER_MAX_RATE) {
        char message[AE_MIXER_ERROR_CAPACITY];
        snprintf(message, sizeof message, "unsupported output sample rate %u", rate);
        set_error(error, error_len, message);
        return NULL;
    }

    struct ae_mixer *m = calloc(1, sizeof *m);
    if (!m) {
        set_error(error, error_len, "out of memory");
        return NULL;
    }
    if (pthread_mutex_init(&m->mutex, NULL) != 0) {
        free(m);
        set_error(error, error_len, "could not create the mixer lock");
        return NULL;
    }
    m->rate = rate;
    m->deck_gain[0] = 1.0f;
    m->deck_gain[1] = 1.0f;

    /* Decks are manual so that their producers poll for space; the mixer owns the only output device. */
    ae_config deck_config;
    memset(&deck_config, 0, sizeof deck_config);
    deck_config.abi_version = AE_ABI_VERSION;
    deck_config.output_mode = AE_OUTPUT_MANUAL;
    deck_config.backend = AE_BACKEND_NULL;
    deck_config.sample_rate = rate;
    deck_config.ring_ms = config->ring_ms;
    deck_config.prebuffer_ms = config->prebuffer_ms;

    for (uint32_t i = 0; i < AE_MIXER_DECKS; i++) {
        char deck_error[AE_MIXER_ERROR_CAPACITY];
        m->deck[i] = ae_engine_create(&deck_config, deck_error, sizeof deck_error);
        if (!m->deck[i]) {
            set_error(error, error_len, deck_error[0] ? deck_error : "could not start a playback deck");
            ae_mixer_destroy(m);
            return NULL;
        }
    }

    char device_error[AE_MIXER_ERROR_CAPACITY];
    m->device = ae_device_open(rate, config->backend == AE_BACKEND_NULL, mixer_pull, m, device_error,
                               sizeof device_error);
    if (!m->device) {
        set_error(error, error_len, device_error[0] ? device_error : "the audio output did not start");
        ae_mixer_destroy(m);
        return NULL;
    }
    return m;
}

void ae_mixer_destroy(ae_mixer *m) {
    if (!m) {
        return;
    }
    /* The device goes first: its callback reads the decks, so no callback may run during their teardown. */
    if (m->device) {
        ae_device_close(m->device);
        m->device = NULL;
    }
    for (uint32_t i = 0; i < AE_MIXER_DECKS; i++) {
        ae_engine_destroy(m->deck[i]);
        m->deck[i] = NULL;
    }
    pthread_mutex_destroy(&m->mutex);
    free(m);
}

ae_engine *ae_mixer_deck(ae_mixer *m, uint32_t deck) {
    if (!m || deck >= AE_MIXER_DECKS) {
        return NULL;
    }
    return m->deck[deck];
}

int ae_mixer_arm(ae_mixer *m, uint32_t incoming, uint64_t start_frame, uint64_t overlap_frames) {
    if (!m || incoming >= AE_MIXER_DECKS) {
        return AE_MIXER_REJECTED;
    }
    int result = AE_MIXER_REJECTED;
    pthread_mutex_lock(&m->mutex);
    if (!m->bypass && !m->armed && incoming != m->active && start_frame >= m->timeline &&
        overlap_frames <= UINT64_MAX - start_frame) {
        m->armed = 1;
        m->incoming = incoming;
        m->start = start_frame;
        m->end = start_frame + overlap_frames;
        result = AE_MIXER_OK;
    }
    pthread_mutex_unlock(&m->mutex);
    return result;
}

void ae_mixer_cancel(ae_mixer *m) {
    if (!m) {
        return;
    }
    pthread_mutex_lock(&m->mutex);
    m->armed = 0;
    pthread_mutex_unlock(&m->mutex);
}

int ae_mixer_promote(ae_mixer *m) {
    if (!m) {
        return 0;
    }
    int promoted = 0;
    pthread_mutex_lock(&m->mutex);
    if (m->armed) {
        commit_locked(m);
        promoted = 1;
    }
    pthread_mutex_unlock(&m->mutex);
    return promoted;
}

void ae_mixer_set_deck_gain(ae_mixer *m, uint32_t deck, float gain) {
    if (!m || deck >= AE_MIXER_DECKS) {
        return;
    }
    float value = isfinite(gain) ? gain : 1.0f;
    if (value < 0.0f) {
        value = 0.0f;
    }
    if (value > AE_MIXER_MAX_GAIN) {
        value = AE_MIXER_MAX_GAIN;
    }
    pthread_mutex_lock(&m->mutex);
    m->deck_gain[deck] = value;
    pthread_mutex_unlock(&m->mutex);
}

void ae_mixer_set_bypass(ae_mixer *m, int bypass) {
    if (!m) {
        return;
    }
    pthread_mutex_lock(&m->mutex);
    int next = bypass ? 1 : 0;
    if (next != m->bypass) {
        /* The filter history belongs to the chain that ran before, so it must not leak into the next one. */
        reset_filters_locked(m);
    }
    m->bypass = next;
    if (m->bypass) {
        m->armed = 0;
    }
    pthread_mutex_unlock(&m->mutex);
}

int ae_mixer_set_equalizer(ae_mixer *m, uint32_t band_count, const ae_biquad *bands) {
    if (!m || band_count > AE_MIXER_MAX_BANDS || (band_count > 0 && !bands)) {
        return AE_MIXER_REJECTED;
    }
    pthread_mutex_lock(&m->mutex);
    if (band_count != m->band_count) {
        reset_filters_locked(m);
    }
    if (band_count > 0) {
        memcpy(m->bands, bands, (size_t)band_count * sizeof *bands);
    }
    m->band_count = band_count;
    pthread_mutex_unlock(&m->mutex);
    return AE_MIXER_OK;
}

void ae_mixer_snapshot(ae_mixer *m, ae_mixer_state *out) {
    if (!m || !out) {
        return;
    }
    pthread_mutex_lock(&m->mutex);
    out->timeline_frames = m->timeline;
    out->commit_sequence = m->commit_sequence;
    out->start_frame = m->armed ? m->start : 0u;
    out->end_frame = m->armed ? m->end : 0u;
    out->active_deck = m->active;
    out->incoming_deck = m->incoming;
    out->armed = m->armed ? 1u : 0u;
    out->bypass = m->bypass ? 1u : 0u;
    out->band_count = m->band_count;

    /* Read under the mixer lock, so the deck position is the media time consumed through timeline_frames. */
    ae_snapshot deck;
    memset(&deck, 0, sizeof deck);
    ae_engine_snapshot(m->deck[m->active], &deck);
    out->active_position_us = deck.position_us;
    out->active_duration_us = deck.duration_us;
    out->active_speed = deck.playback_speed;
    pthread_mutex_unlock(&m->mutex);
}
