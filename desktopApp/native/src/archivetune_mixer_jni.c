/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

/*
 * JNI bindings for the two-deck mixer. The Kotlin side owns the handle, reads snapshots and sends schedule
 * or equalizer commands. Deck handles are borrowed engine pointers, so the existing PlaybackEngineNative
 * calls work on them. No Java code runs on the audio thread.
 */

#include <stdint.h>
#include <string.h>

#include <jni.h>

#include "archivetune_engine.h"
#include "archivetune_mixer.h"

#define MIXER_BINDING(name) Java_moe_rukamori_archivetune_desktop_playback_TwoDeckMixerNative_##name

#define MIXER_STATE_FIELDS 12
#define MIXER_BIQUAD_VALUES 5 /* b0, b1, b2, a1, a2 per section, in that order */
#define MIXER_ERROR_CAPACITY 256

static ae_mixer *mixer_of(jlong handle) {
    return (ae_mixer *)(uintptr_t)handle;
}

static void throw_state(JNIEnv *env, const char *message) {
    jclass type = (*env)->FindClass(env, "java/lang/IllegalStateException");
    if (type) {
        (*env)->ThrowNew(env, type, message);
    }
}

static void throw_argument(JNIEnv *env, const char *message) {
    jclass type = (*env)->FindClass(env, "java/lang/IllegalArgumentException");
    if (type) {
        (*env)->ThrowNew(env, type, message);
    }
}

JNIEXPORT jlong JNICALL MIXER_BINDING(create)(JNIEnv *env, jclass type, jint backend, jint sample_rate, jint ring_ms,
                                              jint prebuffer_ms) {
    (void)type;
    ae_mixer_config config;
    char error[MIXER_ERROR_CAPACITY];

    memset(&config, 0, sizeof config);
    config.abi_version = AE_ABI_VERSION;
    config.backend = backend;
    config.sample_rate = (uint32_t)sample_rate;
    config.ring_ms = (uint32_t)ring_ms;
    config.prebuffer_ms = (uint32_t)prebuffer_ms;

    ae_mixer *mixer = ae_mixer_create(&config, error, sizeof error);
    if (!mixer) {
        throw_state(env, error[0] ? error : "the audio mixer could not start");
        return 0;
    }
    return (jlong)(uintptr_t)mixer;
}

JNIEXPORT void JNICALL MIXER_BINDING(destroy)(JNIEnv *env, jclass type, jlong handle) {
    (void)env;
    (void)type;
    ae_mixer_destroy(mixer_of(handle));
}

/* Returns the borrowed engine handle for deck 0 or 1, or 0 for any other index. */
JNIEXPORT jlong JNICALL MIXER_BINDING(deck)(JNIEnv *env, jclass type, jlong handle, jint deck) {
    (void)env;
    (void)type;
    return (jlong)(uintptr_t)ae_mixer_deck(mixer_of(handle), (uint32_t)deck);
}

JNIEXPORT jint JNICALL MIXER_BINDING(arm)(JNIEnv *env, jclass type, jlong handle, jint incoming, jlong start_frame,
                                          jlong overlap_frames) {
    (void)env;
    (void)type;
    return (jint)ae_mixer_arm(mixer_of(handle), (uint32_t)incoming, (uint64_t)start_frame,
                              (uint64_t)overlap_frames);
}

JNIEXPORT void JNICALL MIXER_BINDING(cancel)(JNIEnv *env, jclass type, jlong handle) {
    (void)env;
    (void)type;
    ae_mixer_cancel(mixer_of(handle));
}

JNIEXPORT jboolean JNICALL MIXER_BINDING(promote)(JNIEnv *env, jclass type, jlong handle) {
    (void)env;
    (void)type;
    return ae_mixer_promote(mixer_of(handle)) != 0 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL MIXER_BINDING(setDeckGain)(JNIEnv *env, jclass type, jlong handle, jint deck, jfloat gain) {
    (void)env;
    (void)type;
    ae_mixer_set_deck_gain(mixer_of(handle), (uint32_t)deck, (float)gain);
}

JNIEXPORT void JNICALL MIXER_BINDING(setBypass)(JNIEnv *env, jclass type, jlong handle, jboolean bypass) {
    (void)env;
    (void)type;
    ae_mixer_set_bypass(mixer_of(handle), bypass ? 1 : 0);
}

/*
 * Takes the equalizer as packed doubles, five per section. Returns the mixer's result code: 0 when the
 * equalizer was replaced, 1 when it was refused. A length that is not a whole number of sections is an
 * argument error, because a truncated section would silently change the response.
 */
JNIEXPORT jint JNICALL MIXER_BINDING(setEqualizer)(JNIEnv *env, jclass type, jlong handle,
                                                   jdoubleArray coefficients) {
    (void)type;
    if (!coefficients) {
        throw_argument(env, "coefficients must not be null");
        return AE_MIXER_REJECTED;
    }
    jsize length = (*env)->GetArrayLength(env, coefficients);
    if (length % MIXER_BIQUAD_VALUES != 0) {
        throw_argument(env, "equalizer coefficients must come in groups of five");
        return AE_MIXER_REJECTED;
    }
    jsize count = length / MIXER_BIQUAD_VALUES;
    if (count > (jsize)AE_MIXER_MAX_BANDS) {
        throw_argument(env, "the equalizer has more sections than the mixer supports");
        return AE_MIXER_REJECTED;
    }
    /* An empty array is how a host switches the equalizer off, so it must clear the sections, not be refused. */
    if (count == 0) {
        return (jint)ae_mixer_set_equalizer(mixer_of(handle), 0, NULL);
    }

    jdouble *values = (*env)->GetDoubleArrayElements(env, coefficients, NULL);
    if (!values) {
        return AE_MIXER_REJECTED;
    }
    ae_biquad bands[AE_MIXER_MAX_BANDS];
    for (jsize i = 0; i < count; i++) {
        const jdouble *source = values + (size_t)i * MIXER_BIQUAD_VALUES;
        bands[i].b0 = (double)source[0];
        bands[i].b1 = (double)source[1];
        bands[i].b2 = (double)source[2];
        bands[i].a1 = (double)source[3];
        bands[i].a2 = (double)source[4];
    }
    (*env)->ReleaseDoubleArrayElements(env, coefficients, values, JNI_ABORT);
    return (jint)ae_mixer_set_equalizer(mixer_of(handle), (uint32_t)count, bands);
}

/*
 * Writes twelve values in this order: timeline frames, commit sequence, armed start frame, armed end frame,
 * active deck, incoming deck, armed flag, bypass flag, equalizer section count, active deck position in
 * microseconds, active deck duration in microseconds (-1 when unknown), and the active deck speed as the raw
 * bits of its float value, which the Kotlin side decodes with Float.fromBits.
 */
JNIEXPORT void JNICALL MIXER_BINDING(snapshot)(JNIEnv *env, jclass type, jlong handle, jlongArray out) {
    (void)type;
    if (!out || (*env)->GetArrayLength(env, out) < MIXER_STATE_FIELDS) {
        throw_argument(env, "mixer snapshot array needs twelve entries");
        return;
    }
    ae_mixer_state state;
    memset(&state, 0, sizeof state);
    ae_mixer_snapshot(mixer_of(handle), &state);

    uint32_t speed_bits = 0;
    memcpy(&speed_bits, &state.active_speed, sizeof speed_bits);

    jlong values[MIXER_STATE_FIELDS];
    values[0] = (jlong)state.timeline_frames;
    values[1] = (jlong)state.commit_sequence;
    values[2] = (jlong)state.start_frame;
    values[3] = (jlong)state.end_frame;
    values[4] = (jlong)state.active_deck;
    values[5] = (jlong)state.incoming_deck;
    values[6] = (jlong)state.armed;
    values[7] = (jlong)state.bypass;
    values[8] = (jlong)state.band_count;
    values[9] = (jlong)state.active_position_us;
    values[10] = (jlong)state.active_duration_us;
    values[11] = (jlong)speed_bits;
    (*env)->SetLongArrayRegion(env, out, 0, MIXER_STATE_FIELDS, values);
}
