/*
 * JNI bindings for the video artwork reader. The Kotlin side owns the handle. Frames are copied straight into
 * pixel memory the caller allocated natively (a Skia bitmap), so a frame never passes through a Java array.
 */

#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include <jni.h>

#include "archivetune_video.h"
#include "archivetune_jni_utf8.h"

#define VIDEO_BINDING(name) Java_moe_rukamori_archivetune_desktop_playback_VideoArtworkNative_##name

/* Sequence, width, height, state, presentation time and duration in microseconds. */
#define VIDEO_INFO_FIELDS 6
#define VIDEO_ERROR_CAPACITY 256

static ae_video *video_of(jlong handle) {
    return (ae_video *)(uintptr_t)handle;
}

JNIEXPORT jlong JNICALL VIDEO_BINDING(open)(JNIEnv *env, jclass type, jstring url, jstring user_agent,
                                            jint max_height) {
    (void)type;
    if (!url) {
        return 0;
    }
    char *native_url = jni_utf8_from_string(env, url);
    if (!native_url) {
        return 0;
    }
    char *native_agent = user_agent ? jni_utf8_from_string(env, user_agent) : NULL;
    if (user_agent && !native_agent) {
        free(native_url);
        return 0;
    }
    ae_video *video = ae_video_open(native_url, native_agent, (int32_t)max_height);
    free(native_agent);
    free(native_url);
    return (jlong)(uintptr_t)video;
}

JNIEXPORT void JNICALL VIDEO_BINDING(setClock)(JNIEnv *env, jclass type, jlong handle, jlong position_us,
                                               jboolean playing, jfloat speed) {
    (void)env;
    (void)type;
    ae_video_set_clock(video_of(handle), (int64_t)position_us, playing ? 1 : 0, (float)speed);
}

JNIEXPORT void JNICALL VIDEO_BINDING(setActive)(JNIEnv *env, jclass type, jlong handle, jboolean active) {
    (void)env;
    (void)type;
    ae_video_set_active(video_of(handle), active ? 1 : 0);
}

JNIEXPORT jint JNICALL VIDEO_BINDING(copyFrame)(JNIEnv *env, jclass type, jlong handle, jlong known_sequence,
                                                jlong address, jlong capacity, jlongArray info) {
    (void)type;
    if (!info || (*env)->GetArrayLength(env, info) < VIDEO_INFO_FIELDS) {
        return 0;
    }
    ae_video_frame_info native_info;
    memset(&native_info, 0, sizeof native_info);
    int32_t result = ae_video_copy_frame(video_of(handle), (uint64_t)known_sequence, (uint8_t *)(uintptr_t)address,
                                         capacity > 0 ? (size_t)capacity : 0u, &native_info);
    jlong values[VIDEO_INFO_FIELDS];
    values[0] = (jlong)native_info.sequence;
    values[1] = (jlong)native_info.width;
    values[2] = (jlong)native_info.height;
    values[3] = (jlong)native_info.state;
    values[4] = (jlong)native_info.pts_us;
    values[5] = (jlong)native_info.duration_us;
    (*env)->SetLongArrayRegion(env, info, 0, VIDEO_INFO_FIELDS, values);
    return (jint)result;
}

JNIEXPORT jstring JNICALL VIDEO_BINDING(lastError)(JNIEnv *env, jclass type, jlong handle) {
    (void)type;
    char message[VIDEO_ERROR_CAPACITY];
    message[0] = '\0';
    (void)ae_video_last_error(video_of(handle), message, sizeof message);
    return jni_string_from_utf8(env, message);
}

JNIEXPORT void JNICALL VIDEO_BINDING(close)(JNIEnv *env, jclass type, jlong handle) {
    (void)env;
    (void)type;
    ae_video_close(video_of(handle));
}
