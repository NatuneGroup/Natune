/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

/*
 * JNI bindings for the playback engine. The Kotlin side passes commands and reads immutable
 * snapshots; no Java code runs on the audio thread. Handles are opaque engine pointers.
 */

#include <pthread.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include <jni.h>

#include "archivetune_engine.h"
#include "archivetune_device.h"
#include "archivetune_jni_utf8.h"

#define BINDING(name) Java_moe_rukamori_archivetune_desktop_playback_PlaybackEngineNative_##name

#define SNAPSHOT_FIELDS 9
#define AE_RENDER_CHANNELS 2 /* interleaved stereo, as documented on ae_engine_render */
#define AE_ERROR_MESSAGE_CAPACITY 512

static ae_engine *engine_of(jlong handle) {
    return (ae_engine *)(uintptr_t)handle;
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

JNIEXPORT jint JNICALL BINDING(abiVersion)(JNIEnv *env, jclass type) {
    (void)env;
    (void)type;
    return (jint)AE_ABI_VERSION;
}

/*
 * The startup output self-check: opens a real device, starts it and closes it again. The answer is a refusal message
 * or null, so the host can tell the user why nothing will be audible before they press play.
 */
JNIEXPORT jstring JNICALL BINDING(probeOutputDevice)(JNIEnv *env, jclass type, jint sample_rate) {
    (void)type;
    char error[AE_ERROR_MESSAGE_CAPACITY];
    error[0] = '\0';
    if (ae_device_probe((uint32_t)sample_rate, error, sizeof error) == 0) {
        return NULL;
    }
    return jni_string_from_utf8(env, error[0] ? error : "the audio output could not be opened");
}

JNIEXPORT jlong JNICALL BINDING(create)(JNIEnv *env, jclass type, jint output_mode, jint backend, jint sample_rate,
                                        jint ring_ms, jint prebuffer_ms) {
    (void)type;
    ae_config config;
    char error[AE_ERROR_MESSAGE_CAPACITY];

    memset(&config, 0, sizeof config);
    config.abi_version = AE_ABI_VERSION;
    config.output_mode = output_mode;
    config.backend = backend;
    config.sample_rate = (uint32_t)sample_rate;
    config.ring_ms = (uint32_t)ring_ms;
    config.prebuffer_ms = (uint32_t)prebuffer_ms;

    ae_engine *engine = ae_engine_create(&config, error, sizeof error);
    if (!engine) {
        throw_state(env, error[0] ? error : "the playback engine could not start");
        return 0;
    }
    return (jlong)(uintptr_t)engine;
}

JNIEXPORT void JNICALL BINDING(destroy)(JNIEnv *env, jclass type, jlong handle) {
    (void)env;
    (void)type;
    ae_engine_destroy(engine_of(handle));
}

JNIEXPORT jlong JNICALL BINDING(load)(JNIEnv *env, jclass type, jlong handle, jstring source, jlong start_us,
                                      jboolean play_when_ready) {
    (void)type;
    if (!source) {
        throw_argument(env, "source must not be null");
        return 0;
    }
    char *path = jni_utf8_from_string(env, source);
    if (!path) {
        return 0;
    }
    uint64_t generation = ae_engine_load(engine_of(handle), path, (uint64_t)start_us, play_when_ready ? 1 : 0);
    free(path);
    return (jlong)generation;
}

/*
 * Host source bridge. The engine calls open, read, seek and close on its producer thread, which the JVM does
 * not know, and interrupt on the command thread while the engine mutex is held. Each callback reaches the
 * Kotlin NativeStreamLease through the method IDs resolved at load time. Java exceptions never cross into C:
 * a pending one is cleared and reported as AE_IO_FAILED, so the engine treats the source as failed.
 */
#define LEASE_SCRATCH_BYTES 32768 /* the engine's stream buffer size; reads are clamped to it */

typedef struct jni_lease {
    JavaVM *vm;
    jobject host;        /* global reference to the NativeStreamLease */
    jbyteArray scratch;  /* global reference, reused by every read: the producer is the only reader */
    jint scratch_capacity;
    jmethodID open_id;      /* int open(long[] out) */
    jmethodID read_id;      /* int read(byte[] buffer, int length) */
    jmethodID seek_id;      /* long seek(long position) */
    jmethodID interrupt_id; /* void interrupt() */
    jmethodID close_id;     /* void close() */
} jni_lease;

static pthread_key_t attach_key;
static pthread_once_t attach_once = PTHREAD_ONCE_INIT;

/* Runs when a producer thread exits, so it detaches once instead of attaching on every callback. */
static void detach_attached_thread(void *vm) {
    if (vm) {
        (*(JavaVM *)vm)->DetachCurrentThread((JavaVM *)vm);
    }
}

static void create_attach_key(void) {
    pthread_key_create(&attach_key, detach_attached_thread);
}

/* Returns this thread's JNIEnv. A thread the JVM does not know is attached as a daemon, so it never delays JVM exit. */
static JNIEnv *lease_env(JavaVM *vm) {
    JNIEnv *env = NULL;
    if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) == JNI_OK) {
        return env;
    }
    pthread_once(&attach_once, create_attach_key);
    if ((*vm)->AttachCurrentThreadAsDaemon(vm, (void **)&env, NULL) != JNI_OK) {
        return NULL;
    }
    pthread_setspecific(attach_key, vm);
    return env;
}

/* Clears a pending Java exception. Returns 1 when there was one. */
static int clear_exception(JNIEnv *env) {
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        return 1;
    }
    return 0;
}

static int32_t lease_open(void *opaque, int64_t *size, int32_t *seekable) {
    jni_lease *lease = opaque;
    JNIEnv *env = lease_env(lease->vm);
    if (!env) {
        return AE_IO_FAILED;
    }
    jlongArray out = (*env)->NewLongArray(env, 2);
    if (!out) {
        clear_exception(env);
        return AE_IO_FAILED;
    }
    jint status = (*env)->CallIntMethod(env, lease->host, lease->open_id, out);
    int32_t result = AE_IO_FAILED;
    if (!clear_exception(env)) {
        jlong values[2] = {-1, 0};
        (*env)->GetLongArrayRegion(env, out, 0, 2, values);
        *size = values[0];
        *seekable = values[1] != 0;
        result = (int32_t)status;
    }
    (*env)->DeleteLocalRef(env, out);
    return result;
}

static int32_t lease_read(void *opaque, uint8_t *buffer, uint32_t length) {
    jni_lease *lease = opaque;
    JNIEnv *env = lease_env(lease->vm);
    if (!env) {
        return AE_IO_FAILED;
    }
    if (length > (uint32_t)lease->scratch_capacity) {
        length = (uint32_t)lease->scratch_capacity;
    }
    jint got = (*env)->CallIntMethod(env, lease->host, lease->read_id, lease->scratch, (jint)length);
    if (clear_exception(env)) {
        return AE_IO_FAILED;
    }
    if (got > 0) {
        /* A host that returns more than it was asked for has broken the contract. */
        if ((uint32_t)got > length) {
            return AE_IO_FAILED;
        }
        (*env)->GetByteArrayRegion(env, lease->scratch, 0, got, (jbyte *)buffer);
        if (clear_exception(env)) {
            return AE_IO_FAILED;
        }
    }
    return (int32_t)got;
}

static int64_t lease_seek(void *opaque, int64_t position) {
    jni_lease *lease = opaque;
    JNIEnv *env = lease_env(lease->vm);
    if (!env) {
        return AE_IO_FAILED;
    }
    jlong reached = (*env)->CallLongMethod(env, lease->host, lease->seek_id, (jlong)position);
    if (clear_exception(env)) {
        return AE_IO_FAILED;
    }
    return (int64_t)reached;
}

static void lease_interrupt(void *opaque) {
    jni_lease *lease = opaque;
    JNIEnv *env = lease_env(lease->vm);
    if (env) {
        (*env)->CallVoidMethod(env, lease->host, lease->interrupt_id);
        clear_exception(env);
    }
}

/* The engine calls this exactly once for every accepted or rejected source, so the lease is freed here. */
static void lease_close(void *opaque) {
    jni_lease *lease = opaque;
    JNIEnv *env = lease_env(lease->vm);
    if (env) {
        (*env)->CallVoidMethod(env, lease->host, lease->close_id);
        clear_exception(env);
        (*env)->DeleteGlobalRef(env, lease->host);
        (*env)->DeleteGlobalRef(env, lease->scratch);
    }
    free(lease);
}

JNIEXPORT jlong JNICALL BINDING(loadStream)(JNIEnv *env, jclass type, jlong handle, jobject host, jlong start_us,
                                            jboolean play_when_ready) {
    (void)type;
    if (!host) {
        throw_argument(env, "stream source must not be null");
        return 0;
    }
    jni_lease *lease = calloc(1, sizeof *lease);
    if (!lease) {
        throw_state(env, "out of memory");
        return 0;
    }
    if ((*env)->GetJavaVM(env, &lease->vm) != JNI_OK) {
        free(lease);
        throw_state(env, "the JVM is not available");
        return 0;
    }
    jclass host_type = (*env)->GetObjectClass(env, host);
    lease->open_id = (*env)->GetMethodID(env, host_type, "open", "([J)I");
    lease->read_id = (*env)->GetMethodID(env, host_type, "read", "([BI)I");
    lease->seek_id = (*env)->GetMethodID(env, host_type, "seek", "(J)J");
    lease->interrupt_id = (*env)->GetMethodID(env, host_type, "interrupt", "()V");
    lease->close_id = (*env)->GetMethodID(env, host_type, "close", "()V");
    (*env)->DeleteLocalRef(env, host_type);
    if (!lease->open_id || !lease->read_id || !lease->seek_id || !lease->interrupt_id || !lease->close_id) {
        clear_exception(env);
        free(lease);
        throw_argument(env, "stream source does not implement the lease callbacks");
        return 0;
    }

    lease->host = (*env)->NewGlobalRef(env, host);
    jbyteArray scratch = (*env)->NewByteArray(env, LEASE_SCRATCH_BYTES);
    if (scratch) {
        lease->scratch = (jbyteArray)(*env)->NewGlobalRef(env, scratch);
        (*env)->DeleteLocalRef(env, scratch);
    }
    if (!lease->host || !lease->scratch) {
        clear_exception(env);
        if (lease->host) {
            (*env)->DeleteGlobalRef(env, lease->host);
        }
        if (lease->scratch) {
            (*env)->DeleteGlobalRef(env, lease->scratch);
        }
        free(lease);
        throw_state(env, "out of memory");
        return 0;
    }
    lease->scratch_capacity = LEASE_SCRATCH_BYTES;

    ae_stream_io io;
    memset(&io, 0, sizeof io);
    io.abi_version = AE_STREAM_IO_VERSION;
    io.opaque = lease;
    io.open = lease_open;
    io.read = lease_read;
    io.seek = lease_seek;
    io.interrupt = lease_interrupt;
    io.close = lease_close;
    /* From here the engine owns the lease. It calls lease_close exactly once, even when the load is refused. */
    return (jlong)ae_engine_load_stream(engine_of(handle), &io, (uint64_t)start_us, play_when_ready ? 1 : 0);
}

JNIEXPORT void JNICALL BINDING(play)(JNIEnv *env, jclass type, jlong handle) {
    (void)env;
    (void)type;
    ae_engine_play(engine_of(handle));
}

JNIEXPORT void JNICALL BINDING(pause)(JNIEnv *env, jclass type, jlong handle) {
    (void)env;
    (void)type;
    ae_engine_pause(engine_of(handle));
}

JNIEXPORT void JNICALL BINDING(seek)(JNIEnv *env, jclass type, jlong handle, jlong position_us) {
    (void)env;
    (void)type;
    ae_engine_seek(engine_of(handle), (uint64_t)position_us);
}

JNIEXPORT void JNICALL BINDING(setSpeed)(JNIEnv *env, jclass type, jlong handle, jfloat speed) {
    (void)env;
    (void)type;
    ae_engine_set_speed(engine_of(handle), (float)speed);
}

JNIEXPORT void JNICALL BINDING(setPitch)(JNIEnv *env, jclass type, jlong handle, jfloat semitones) {
    (void)env; (void)type;
    ae_engine_set_pitch(engine_of(handle), semitones);
}
JNIEXPORT void JNICALL BINDING(setSkipSilence)(JNIEnv *env, jclass type, jlong handle, jboolean enabled) {
    (void)env; (void)type;
    ae_engine_set_skip_silence(engine_of(handle), enabled ? 1 : 0);
}

JNIEXPORT void JNICALL BINDING(stop)(JNIEnv *env, jclass type, jlong handle) {
    (void)env;
    (void)type;
    ae_engine_stop(engine_of(handle));
}

/*
 * Writes the snapshot into a LongArray of SNAPSHOT_FIELDS entries, in this order: flags, error code,
 * load generation, seek generation, error sequence, position, duration, buffered, and the bits of
 * the playback speed float.
 */
JNIEXPORT void JNICALL BINDING(snapshot)(JNIEnv *env, jclass type, jlong handle, jlongArray out) {
    (void)type;
    ae_snapshot snapshot;
    jlong values[SNAPSHOT_FIELDS];
    union {
        float value;
        uint32_t bits;
    } speed;

    if (!out || (*env)->GetArrayLength(env, out) < SNAPSHOT_FIELDS) {
        throw_argument(env, "snapshot array needs nine entries");
        return;
    }
    memset(&snapshot, 0, sizeof snapshot);
    ae_engine_snapshot(engine_of(handle), &snapshot);

    speed.value = snapshot.playback_speed;
    values[0] = (jlong)snapshot.flags;
    values[1] = (jlong)snapshot.error_code;
    values[2] = (jlong)snapshot.load_generation;
    values[3] = (jlong)snapshot.seek_generation;
    values[4] = (jlong)snapshot.error_sequence;
    values[5] = (jlong)snapshot.position_us;
    values[6] = (jlong)snapshot.duration_us;
    values[7] = (jlong)snapshot.buffered_us;
    values[8] = (jlong)speed.bits;
    (*env)->SetLongArrayRegion(env, out, 0, SNAPSHOT_FIELDS, values);
}

JNIEXPORT jstring JNICALL BINDING(lastError)(JNIEnv *env, jclass type, jlong handle) {
    (void)type;
    char buffer[AE_ERROR_MESSAGE_CAPACITY];
    size_t length = ae_engine_last_error(engine_of(handle), buffer, sizeof buffer);
    if (length == 0) {
        return jni_string_from_utf8(env, "");
    }
    return jni_string_from_utf8(env, buffer);
}

JNIEXPORT jint JNICALL BINDING(render)(JNIEnv *env, jclass type, jlong handle, jfloatArray out, jint frames) {
    (void)type;
    if (!out || frames < 0) {
        throw_argument(env, "render needs a buffer and a non-negative frame count");
        return 0;
    }
    jsize capacity = (*env)->GetArrayLength(env, out);
    if ((jlong)frames * AE_RENDER_CHANNELS > (jlong)capacity) {
        throw_argument(env, "render buffer is smaller than the requested frames");
        return 0;
    }
    jfloat *samples = (*env)->GetFloatArrayElements(env, out, NULL);
    if (!samples) {
        return 0;
    }
    uint32_t rendered = ae_engine_render(engine_of(handle), (float *)samples, (uint32_t)frames);
    (*env)->ReleaseFloatArrayElements(env, out, samples, 0);
    return (jint)rendered;
}
