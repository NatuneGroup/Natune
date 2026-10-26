/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
#import <Foundation/Foundation.h>
#import <Security/Security.h>
#include <jni.h>

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
NSMutableDictionary* query(JNIEnv* env, jstring account) {
    if (!account || env->GetStringLength(account) != 64) { unavailable(env); return nil; }
    const jchar* chars = env->GetStringChars(account, nullptr);
    if (!chars) return nil;
    NSString* identity = [[NSString alloc] initWithCharacters:reinterpret_cast<const unichar*>(chars) length:64];
    env->ReleaseStringChars(account, chars);
    return [@{(__bridge id)kSecClass: (__bridge id)kSecClassGenericPassword,
        (__bridge id)kSecAttrService: @"moe.rukamori.archivetune.credentials.v1",
        (__bridge id)kSecAttrAccount: identity,
        // Locked/unavailable/denied is an error, never a background keychain prompt or an empty password.
        (__bridge id)kSecUseAuthenticationUI: (__bridge id)kSecUseAuthenticationUIFail} mutableCopy];
}
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_moe_rukamori_archivetune_desktop_integration_auth_DesktopCredentialNative_keychainRead(
    JNIEnv* env, jobject, jstring account) {
    @autoreleasepool {
        NSMutableDictionary* search = query(env, account);
        if (!search) return nullptr;
        search[(__bridge id)kSecReturnData] = @YES;
        search[(__bridge id)kSecMatchLimit] = (__bridge id)kSecMatchLimitOne;
        CFTypeRef raw = nullptr;
        OSStatus status = SecItemCopyMatching((__bridge CFDictionaryRef)search, &raw);
        if (status == errSecItemNotFound) return nullptr;
        if (status != errSecSuccess || !raw) { if (raw) CFRelease(raw); unavailable(env); return nullptr; }
        NSData* value = CFBridgingRelease(raw);
        if (![value isKindOfClass:NSData.class] || value.length > maximum) { unavailable(env); return nullptr; }
        jbyteArray result = env->NewByteArray(static_cast<jsize>(value.length));
        if (result && value.length) env->SetByteArrayRegion(result, 0, static_cast<jsize>(value.length),
            static_cast<const jbyte*>(value.bytes));
        return result;
    }
}
extern "C" JNIEXPORT void JNICALL
Java_moe_rukamori_archivetune_desktop_integration_auth_DesktopCredentialNative_keychainWrite(
    JNIEnv* env, jobject, jstring account, jbyteArray input) {
    @autoreleasepool {
        NSMutableDictionary* search = query(env, account);
        if (!search) return;
        if (!input || env->GetArrayLength(input) > maximum) { unavailable(env); return; }
        jsize length = env->GetArrayLength(input);
        NSMutableData* value = [NSMutableData dataWithLength:length];
        if (!value) { unavailable(env); return; }
        if (length) env->GetByteArrayRegion(input, 0, length, static_cast<jbyte*>(value.mutableBytes));
        if (env->ExceptionCheck()) { [value resetBytesInRange:NSMakeRange(0, value.length)]; return; }
        NSDictionary* update = @{(__bridge id)kSecValueData: value};
        OSStatus status = SecItemUpdate((__bridge CFDictionaryRef)search, (__bridge CFDictionaryRef)update);
        if (status == errSecItemNotFound) {
            search[(__bridge id)kSecValueData] = value;
            search[(__bridge id)kSecAttrAccessible] = (__bridge id)kSecAttrAccessibleWhenUnlockedThisDeviceOnly;
            status = SecItemAdd((__bridge CFDictionaryRef)search, nullptr);
            // Another app instance may have inserted the same slot between update and add.
            if (status == errSecDuplicateItem) {
                [search removeObjectForKey:(__bridge id)kSecValueData];
                [search removeObjectForKey:(__bridge id)kSecAttrAccessible];
                status = SecItemUpdate((__bridge CFDictionaryRef)search, (__bridge CFDictionaryRef)update);
            }
        }
        [value resetBytesInRange:NSMakeRange(0, value.length)];
        if (status != errSecSuccess) unavailable(env);
    }
}
extern "C" JNIEXPORT void JNICALL
Java_moe_rukamori_archivetune_desktop_integration_auth_DesktopCredentialNative_keychainRemove(
    JNIEnv* env, jobject, jstring account) {
    @autoreleasepool {
        NSMutableDictionary* search = query(env, account);
        if (!search) return;
        OSStatus status = SecItemDelete((__bridge CFDictionaryRef)search);
        if (status != errSecSuccess && status != errSecItemNotFound) unavailable(env);
    }
}
