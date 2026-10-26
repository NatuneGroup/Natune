/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
#include <windows.h>
#include <wincrypt.h>
#include <jni.h>
#include <vector>

namespace {
constexpr jsize maximum = 1024 * 1024;
void unavailable(JNIEnv* env) {
    jclass type = env->FindClass("moe/rukamori/archivetune/desktop/integration/auth/DesktopCredentialStoreUnavailable");
    if (!type) return;
    jmethodID constructor = env->GetMethodID(type, "<init>", "()V");
    if (!constructor) return;
    jobject error = env->NewObject(type, constructor);
    if (error) env->Throw(static_cast<jthrowable>(error));
}
struct Buffer {
    std::vector<BYTE> bytes;
    explicit Buffer(size_t size) : bytes(size) {}
    ~Buffer() { if (!bytes.empty()) SecureZeroMemory(bytes.data(), bytes.size()); }
};
jbyteArray transform(JNIEnv* env, jbyteArray input, jbyteArray entropy, bool decrypt) {
    if (!input || !entropy) { unavailable(env); return nullptr; }
    const jsize size = env->GetArrayLength(input), saltSize = env->GetArrayLength(entropy);
    if (size < 0 || size > maximum + (decrypt ? 8192 : 0) || saltSize != 64) {
        unavailable(env); return nullptr;
    }
    try {
        Buffer data(size ? size : 1), salt(saltSize);
        if (size) env->GetByteArrayRegion(input, 0, size, reinterpret_cast<jbyte*>(data.bytes.data()));
        env->GetByteArrayRegion(entropy, 0, saltSize, reinterpret_cast<jbyte*>(salt.bytes.data()));
        if (env->ExceptionCheck()) return nullptr;
        DATA_BLOB in{static_cast<DWORD>(size), data.bytes.data()};
        DATA_BLOB extra{static_cast<DWORD>(saltSize), salt.bytes.data()}, out{};
        // No LOCAL_MACHINE flag: only this Windows logon identity can decrypt the slot.
        BOOL ok = decrypt
            ? CryptUnprotectData(&in, nullptr, &extra, nullptr, nullptr, CRYPTPROTECT_UI_FORBIDDEN, &out)
            : CryptProtectData(&in, nullptr, &extra, nullptr, nullptr, CRYPTPROTECT_UI_FORBIDDEN, &out);
        if (!ok) { unavailable(env); return nullptr; }
        jbyteArray result = nullptr;
        if (out.cbData <= static_cast<DWORD>(maximum + (decrypt ? 0 : 8192))) {
            result = env->NewByteArray(static_cast<jsize>(out.cbData));
            if (result && out.cbData) env->SetByteArrayRegion(result, 0, static_cast<jsize>(out.cbData), reinterpret_cast<jbyte*>(out.pbData));
        } else { unavailable(env); }
        SecureZeroMemory(out.pbData, out.cbData);
        LocalFree(out.pbData);
        return result;
    } catch (...) { if (!env->ExceptionCheck()) unavailable(env); return nullptr; }
}
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_moe_rukamori_archivetune_desktop_integration_auth_DesktopCredentialNative_protect(
    JNIEnv* env, jobject, jbyteArray value, jbyteArray entropy) { return transform(env, value, entropy, false); }
extern "C" JNIEXPORT jbyteArray JNICALL
Java_moe_rukamori_archivetune_desktop_integration_auth_DesktopCredentialNative_unprotect(
    JNIEnv* env, jobject, jbyteArray value, jbyteArray entropy) { return transform(env, value, entropy, true); }
