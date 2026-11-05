/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

/*
 * JNI bindings for the source-rate PCM probe. The Kotlin side owns the handle, reads one window at a time
 * and may cancel from any thread. The read blocks in native code, so the caller must run it off the
 * playback thread. Arrays are copied rather than pinned, because the decode waits on I/O.
 */

#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include <jni.h>

#include "archivetune_probe.h"
#include "archivetune_jni_utf8.h"

#define PROBE_BINDING(name) Java_moe_rukamori_archivetune_desktop_playback_SpectralProbeNative_##name

/* Six values in this order: status, frames, channels, PCM sample rate, advertised sample rate, end of source. */
#define PROBE_INFO_FIELDS 6

static ae_probe *probe_of(jlong handle) {
    return (ae_probe *)(uintptr_t)handle;
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

JNIEXPORT jlong JNICALL PROBE_BINDING(create)(JNIEnv *env, jclass type) {
    (void)type;
    ae_probe *probe = ae_probe_create();
    if (!probe) {
        throw_state(env, "the spectral probe could not start");
        return 0;
    }
    return (jlong)(uintptr_t)probe;
}

JNIEXPORT void JNICALL PROBE_BINDING(destroy)(JNIEnv *env, jclass type, jlong handle) {
    (void)env;
    (void)type;
    ae_probe_destroy(probe_of(handle));
}

JNIEXPORT void JNICALL PROBE_BINDING(cancel)(JNIEnv *env, jclass type, jlong handle) {
    (void)env;
    (void)type;
    ae_probe_cancel(probe_of(handle));
}

/*
 * Returns null on success, or the failure message. info[0] carries the status, so the Kotlin side can tell
 * a cancelled read from a broken source without parsing text.
 */
JNIEXPORT jstring JNICALL PROBE_BINDING(read)(JNIEnv *env, jclass type, jlong handle, jstring source,
                                              jlong start_us, jint max_frames, jfloatArray out,
                                              jlongArray info) {
    (void)type;
    if (!source || !out || !info) {
        throw_argument(env, "probe read needs a source, an output array and an info array");
        return NULL;
    }
    if (start_us < 0 || max_frames <= 0) {
        throw_argument(env, "probe start must not be negative and probe frames must be positive");
        return NULL;
    }
    if ((*env)->GetArrayLength(env, info) < PROBE_INFO_FIELDS) {
        throw_argument(env, "probe info needs six entries");
        return NULL;
    }

    char *path = jni_utf8_from_string(env, source);
    if (!path) {
        return NULL;
    }
    jsize capacity = (*env)->GetArrayLength(env, out);
    jfloat *samples = (*env)->GetFloatArrayElements(env, out, NULL);
    if (!samples) {
        free(path);
        return NULL;
    }

    ae_probe_info native_info;
    char error[AE_PROBE_ERROR_CAPACITY];
    memset(&native_info, 0, sizeof native_info);
    error[0] = '\0';
    ae_probe_status status = ae_probe_read(probe_of(handle), path, (uint64_t)start_us, (uint32_t)max_frames,
                                           (float *)samples, (size_t)capacity, &native_info, error, sizeof error);

    /* Mode 0 copies the decoded window back into the Java array. */
    (*env)->ReleaseFloatArrayElements(env, out, samples, 0);
    free(path);

    jlong values[PROBE_INFO_FIELDS];
    values[0] = (jlong)status;
    values[1] = (jlong)native_info.frames;
    values[2] = (jlong)native_info.channels;
    values[3] = (jlong)native_info.sample_rate;
    values[4] = (jlong)native_info.advertised_sample_rate;
    values[5] = (jlong)native_info.end_of_source;
    (*env)->SetLongArrayRegion(env, info, 0, PROBE_INFO_FIELDS, values);

    if (status == AE_PROBE_OK) {
        return NULL;
    }
    return jni_string_from_utf8(env, error[0] ? error : "the spectral probe failed");
}

JNIEXPORT jstring JNICALL PROBE_BINDING(codecName)(JNIEnv *env, jclass type, jlong handle) {
    (void)type;
    char name[AE_PROBE_CODEC_NAME_CAPACITY];
    name[0] = '\0';
    (void)ae_probe_codec_name(probe_of(handle), name, sizeof name);
    return jni_string_from_utf8(env, name);
}
