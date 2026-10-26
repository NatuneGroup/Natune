/*
 * ArchiveTune (2026), GPL-3.0
 * © Rukamori — github.com/rukamori; Portions © vossgraves — github.com/vossgraves
 * Contributors: see git history. Do not remove or alter this notice.
 */
#pragma once
#include <jni.h>
#include <string>

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
            auto action = env->NewStringUTF(name);
            auto path = env->NewStringUTF(track.c_str());
            env->CallVoidMethod(object, command, action, path, position, speed);
            env->DeleteLocalRef(action); env->DeleteLocalRef(path);
        });
    }
    void visibility(bool occluded, bool resumed) {
        call([&](JNIEnv* env) { env->CallVoidMethod(object, activity, occluded, resumed); });
    }
    ~JavaCallbacks() { call([&](JNIEnv* env) { env->DeleteGlobalRef(object); }); }
};

inline std::string utf8(JNIEnv* env, jstring value) {
    if (!value) return "";
    const jchar* chars = env->GetStringChars(value, nullptr);
    jsize length = env->GetStringLength(value);
    std::string result;
    for (jsize i = 0; i < length; ++i) {
        unsigned int c = chars[i];
        if (c >= 0xd800 && c <= 0xdbff && i + 1 < length && chars[i + 1] >= 0xdc00 && chars[i + 1] <= 0xdfff) {
            c = 0x10000 + ((c - 0xd800) << 10) + (chars[++i] - 0xdc00);
        } else if (c >= 0xd800 && c <= 0xdfff) c = 0xfffd;
        if (c < 0x80) result += static_cast<char>(c);
        else if (c < 0x800) {
            result += static_cast<char>(0xc0 | (c >> 6)); result += static_cast<char>(0x80 | (c & 0x3f));
        } else if (c < 0x10000) {
            result += static_cast<char>(0xe0 | (c >> 12)); result += static_cast<char>(0x80 | ((c >> 6) & 0x3f));
            result += static_cast<char>(0x80 | (c & 0x3f));
        } else {
            result += static_cast<char>(0xf0 | (c >> 18)); result += static_cast<char>(0x80 | ((c >> 12) & 0x3f));
            result += static_cast<char>(0x80 | ((c >> 6) & 0x3f)); result += static_cast<char>(0x80 | (c & 0x3f));
        }
    }
    env->ReleaseStringChars(value, chars);
    return result;
}
