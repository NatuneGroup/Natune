/*
 * ArchiveTune (2026), GPL-3.0
 * © Rukamori — github.com/rukamori; Portions © vossgraves — github.com/vossgraves
 * Contributors: see git history. Do not remove or alter this notice.
 */
#pragma once
#include <jni.h>
#include <cstdint>
#include <cstring>
#include <string>
#include <vector>
#include "archivetune_jni_utf8.h"

/*
 * JNI's own string functions speak modified UTF-8 (CESU-8 for supplementary characters), so a track
 * name or path with an emoji would round-trip as invalid UTF-8. These two wrappers use the shared
 * standard-UTF-8 codec and the C++ JNIEnv surface.
 */
inline std::string utf8(JNIEnv* env, jstring value) {
    if (!value) return "";
    const jchar* chars = env->GetStringChars(value, nullptr);
    if (!chars) return "";
    jsize length = env->GetStringLength(value);
    if (static_cast<size_t>(length) > (SIZE_MAX - 1u) / 4u) {
        env->ReleaseStringChars(value, chars);
        return "";
    }
    std::string result(static_cast<size_t>(length) * 4u + 1u, '\0');
    size_t used = ae_utf16_to_utf8(chars, static_cast<size_t>(length), result.data(), result.size());
    env->ReleaseStringChars(value, chars);
    if (used == AE_UTF8_INVALID) return "";
    result.resize(used);
    return result;
}

inline jstring new_utf8_string(JNIEnv* env, const char* value) {
    if (!value) return nullptr;
    size_t length = std::strlen(value);
    if (length > static_cast<size_t>(0x7fffffffu) || length > SIZE_MAX / sizeof(jchar) - 1u) return nullptr;
    std::vector<jchar> units(length + 1u);
    size_t used = ae_utf8_to_utf16(value, length, units.data(), units.size());
    if (used == AE_UTF8_INVALID) return nullptr;
    return env->NewString(units.data(), static_cast<jsize>(used));
}

struct JavaCallbacks {
    JavaVM* vm = nullptr;
    jobject object = nullptr;
    jmethodID command = nullptr;
    jmethodID activity = nullptr;
    JavaCallbacks(JNIEnv* env, jobject target) {
        env->GetJavaVM(&vm);
        object = env->NewGlobalRef(target);
        jclass type = env->GetObjectClass(target);
        command = env->GetMethodID(type, "onCommand", "(Ljava/lang/String;Ljava/lang/String;JD)V");
        activity = env->GetMethodID(type, "onActivity", "(ZZ)V");
        env->DeleteLocalRef(type);
    }
    template<class F> void call(F work) {
        JNIEnv* env = nullptr;
        bool attached = vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_8) == JNI_EDETACHED;
        if (attached && vm->AttachCurrentThread(reinterpret_cast<void**>(&env), nullptr) != JNI_OK) return;
        work(env);
        // Kotlin callbacks catch their own failures; a pending exception must not escape an OS callback.
        if (env->ExceptionCheck()) env->ExceptionClear();
        if (attached) vm->DetachCurrentThread();
    }
    void emit(const char* name, const std::string& track = "", jlong position = 0, double speed = 1) {
        call([&](JNIEnv* env) {
            jstring action = new_utf8_string(env, name);
            jstring path = new_utf8_string(env, track.c_str());
            if (action && path) env->CallVoidMethod(object, command, action, path, position, speed);
            if (action) env->DeleteLocalRef(action);
            if (path) env->DeleteLocalRef(path);
        });
    }
    void visibility(bool occluded, bool resumed) {
        call([&](JNIEnv* env) { env->CallVoidMethod(object, activity, occluded, resumed); });
    }
    ~JavaCallbacks() { call([&](JNIEnv* env) { env->DeleteGlobalRef(object); }); }
};
