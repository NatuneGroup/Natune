// Natune OAuth-only Social SDK bridge. The licensed prefab/AAR owns transport.
// Compiled only with NATUNE_DISCORD_SOCIAL_SDK; uses the SDK 1.10.19337 header API.
#define DISCORDPP_IMPLEMENTATION
#include "discordpp.h"
#include <jni.h>
#include <algorithm>
#include <cstdint>
#include <memory>
#include <string>

namespace {
JavaVM* vm = nullptr;
jclass bridge = nullptr;
std::shared_ptr<discordpp::Client> client;
std::uint64_t applicationId = 0;

std::string text(JNIEnv* env, jstring value) {
    if (!value) return {};
    const char* bytes = env->GetStringUTFChars(value, nullptr);
    std::string result = bytes ? bytes : "";
    if (bytes) env->ReleaseStringUTFChars(value, bytes);
    return result;
}

struct CallbackEnv {
    JNIEnv* env = nullptr;
    bool attached = false;
    CallbackEnv() {
        if (vm && vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
            attached = vm->AttachCurrentThread(&env, nullptr) == JNI_OK;
        }
    }
    ~CallbackEnv() { if (attached) vm->DetachCurrentThread(); }
};

void operation(jlong id, bool success, const char* error) {
    CallbackEnv current;
    if (!current.env || !bridge) return;
    auto method = current.env->GetStaticMethodID(bridge, "onOperation", "(JZLjava/lang/String;)V");
    auto message = current.env->NewStringUTF(error);
    if (method) current.env->CallStaticVoidMethod(bridge, method, id, static_cast<jboolean>(success), message);
    current.env->DeleteLocalRef(message);
    if (current.env->ExceptionCheck()) current.env->ExceptionClear();
}

void tokenResult(jlong id, bool success, const std::string& access, const std::string& refresh,
                 jlong expiresIn, const char* error) {
    CallbackEnv current;
    if (!current.env || !bridge) return;
    auto method = current.env->GetStaticMethodID(bridge, "onToken", "(JZLjava/lang/String;Ljava/lang/String;JLjava/lang/String;)V");
    auto accessValue = current.env->NewStringUTF(access.c_str());
    auto refreshValue = current.env->NewStringUTF(refresh.c_str());
    auto message = current.env->NewStringUTF(error);
    if (method) current.env->CallStaticVoidMethod(bridge, method, id, static_cast<jboolean>(success), accessValue, refreshValue, expiresIn, message);
    current.env->DeleteLocalRef(accessValue);
    current.env->DeleteLocalRef(refreshValue);
    current.env->DeleteLocalRef(message);
    if (current.env->ExceptionCheck()) current.env->ExceptionClear();
}

void status(bool ready) {
    CallbackEnv current;
    if (!current.env || !bridge) return;
    auto method = current.env->GetStaticMethodID(bridge, "onStatus", "(Z)V");
    if (method) current.env->CallStaticVoidMethod(bridge, method, static_cast<jboolean>(ready));
    if (current.env->ExceptionCheck()) current.env->ExceptionClear();
}
} // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_moe_rukamori_archivetune_discord_DiscordNativeSdkBridge_nativeInit(JNIEnv* env, jclass type, jlong appId) {
    try {
        if (client && applicationId == static_cast<std::uint64_t>(appId)) return JNI_TRUE;
        if (client) client->Disconnect();
        if (bridge) env->DeleteGlobalRef(bridge);
        env->GetJavaVM(&vm);
        bridge = static_cast<jclass>(env->NewGlobalRef(type));
        applicationId = static_cast<std::uint64_t>(appId);
        client = std::make_shared<discordpp::Client>();
        client->SetApplicationId(applicationId);
        std::weak_ptr<discordpp::Client> expected = client;
        client->SetStatusChangedCallback([expected](auto value, auto, auto) {
            if (expected.lock() == client) status(value == discordpp::Client::Status::Ready);
        });
        return JNI_TRUE;
    } catch (...) { return JNI_FALSE; }
}

extern "C" JNIEXPORT void JNICALL
Java_moe_rukamori_archivetune_discord_DiscordNativeSdkBridge_nativeAuthorize(JNIEnv*, jclass, jlong id) {
    if (!client) { tokenResult(id, false, {}, {}, 0, "SDK_NOT_INITIALIZED"); return; }
    try {
        auto expected = client;
        auto verifier = client->CreateAuthorizationCodeVerifier();
        discordpp::AuthorizationArgs args;
        args.SetClientId(applicationId);
        args.SetScopes(discordpp::Client::GetDefaultPresenceScopes());
        args.SetCodeChallenge(verifier.Challenge());
        client->Authorize(args, [expected, verifier, id](auto result, std::string code, std::string redirectUri) {
            if (expected != client) return;
            if (!result.Successful()) { tokenResult(id, false, {}, {}, 0, "SDK_AUTHORIZATION_FAILED"); return; }
            expected->GetToken(applicationId, code, verifier.Verifier(), redirectUri,
                [expected, id](auto tokenResultValue, std::string access, std::string refresh, auto, auto expiresIn, auto) {
                    if (expected != client) return;
                    tokenResult(id, tokenResultValue.Successful(), access, refresh, expiresIn,
                        tokenResultValue.Successful() ? "" : "SDK_TOKEN_EXCHANGE_FAILED");
                });
        });
    } catch (...) { tokenResult(id, false, {}, {}, 0, "SDK_AUTHORIZATION_FAILED"); }
}

extern "C" JNIEXPORT void JNICALL
Java_moe_rukamori_archivetune_discord_DiscordNativeSdkBridge_nativeRefresh(JNIEnv* env, jclass, jstring refresh, jlong id) {
    if (!client) { tokenResult(id, false, {}, {}, 0, "SDK_NOT_INITIALIZED"); return; }
    try {
        auto expected = client;
        client->RefreshToken(applicationId, text(env, refresh),
            [expected, id](auto result, std::string access, std::string rotatedRefresh, auto, auto expiresIn, auto) {
                if (expected != client) return;
                tokenResult(id, result.Successful(), access, rotatedRefresh, expiresIn,
                    result.Successful() ? "" : "SDK_TOKEN_REFRESH_FAILED");
            });
    } catch (...) { tokenResult(id, false, {}, {}, 0, "SDK_TOKEN_REFRESH_FAILED"); }
}

extern "C" JNIEXPORT void JNICALL
Java_moe_rukamori_archivetune_discord_DiscordNativeSdkBridge_nativeSetToken(JNIEnv* env, jclass, jstring token, jlong id) {
    if (!client) { operation(id, false, "SDK_NOT_INITIALIZED"); return; }
    try {
        auto expected = client;
        client->UpdateToken(discordpp::AuthorizationTokenType::Bearer, text(env, token), [expected, id](auto result) {
            if (expected != client) return;
            if (result.Successful()) expected->Connect();
            operation(id, result.Successful(), result.Successful() ? "" : "SDK_TOKEN_UPDATE_FAILED");
        });
    } catch (...) { operation(id, false, "SDK_TOKEN_UPDATE_FAILED"); }
}

extern "C" JNIEXPORT void JNICALL
Java_moe_rukamori_archivetune_discord_DiscordNativeSdkBridge_nativeUpdatePresence(JNIEnv* env, jclass, jint type,
    jstring details, jstring stateValue, jstring largeImage, jstring largeText, jstring smallImage, jstring smallText,
    jlong start, jlong end, jobjectArray labels, jobjectArray urls, jlong id) {
    if (!client) { operation(id, false, "SDK_NOT_INITIALIZED"); return; }
    try {
        discordpp::Activity activity;
        activity.SetType(static_cast<discordpp::ActivityTypes>(type));
        activity.SetDetails(text(env, details));
        activity.SetState(text(env, stateValue));
        discordpp::ActivityAssets assets;
        assets.SetLargeImage(text(env, largeImage));
        assets.SetLargeText(text(env, largeText));
        assets.SetSmallImage(text(env, smallImage));
        assets.SetSmallText(text(env, smallText));
        activity.SetAssets(assets);
        if (start > 0 || end > 0) {
            discordpp::ActivityTimestamps timestamps;
            if (start > 0) timestamps.SetStart(start);
            if (end > 0) timestamps.SetEnd(end);
            activity.SetTimestamps(timestamps);
        }
        const auto count = labels && urls ? std::min<jsize>(2, std::min(env->GetArrayLength(labels), env->GetArrayLength(urls))) : 0;
        for (jsize index = 0; index < count; ++index) {
            auto label = static_cast<jstring>(env->GetObjectArrayElement(labels, index));
            auto url = static_cast<jstring>(env->GetObjectArrayElement(urls, index));
            discordpp::ActivityButton button;
            button.SetLabel(text(env, label));
            button.SetUrl(text(env, url));
            activity.AddButton(button);
            env->DeleteLocalRef(label);
            env->DeleteLocalRef(url);
        }
        auto expected = client;
        client->UpdateRichPresence(activity, [expected, id](auto result) {
            if (expected == client) operation(id, result.Successful(), result.Successful() ? "" : "SDK_PRESENCE_FAILED");
        });
    } catch (...) { operation(id, false, "SDK_PRESENCE_FAILED"); }
}

extern "C" JNIEXPORT void JNICALL
Java_moe_rukamori_archivetune_discord_DiscordNativeSdkBridge_nativeClear(JNIEnv*, jclass, jlong id) {
    if (!client) { operation(id, true, ""); return; }
    try {
        client->ClearRichPresence();
        // Clearing is synchronous in SDK 1.10.19337; there is no completion callback.
        operation(id, true, "");
    } catch (...) { operation(id, false, "SDK_CLEAR_FAILED"); }
}

extern "C" JNIEXPORT void JNICALL
Java_moe_rukamori_archivetune_discord_DiscordNativeSdkBridge_nativeTick(JNIEnv*, jclass) {
    try { discordpp::RunCallbacks(); } catch (...) { status(false); }
}

extern "C" JNIEXPORT void JNICALL
Java_moe_rukamori_archivetune_discord_DiscordNativeSdkBridge_nativeClose(JNIEnv* env, jclass) {
    auto closing = std::move(client);
    if (closing) closing->Disconnect();
    status(false);
    if (bridge) { env->DeleteGlobalRef(bridge); bridge = nullptr; }
}
