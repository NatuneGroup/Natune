/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

/*
 * Platform-neutral pieces shared by the three cookie-only sign-in browsers (WebKitGTK, WebView2, WKWebView).
 *
 * Nothing here touches a browser engine. It holds the UTF-8 and UTF-16 conversion JNI needs (the deprecated
 * codecvt facet is not used), the one canonical https origin rule that every platform applies before it navigates
 * or reads cookies, and the JNI listener that delivers events from the platform owner thread.
 *
 * Compiled only through the explicit AE_BROWSER option. This header has not been compiled or run in this lane.
 */

#pragma once

#include <jni.h>

#include <array>
#include <atomic>
#include <cctype>
#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace archivetune::browser {

constexpr std::size_t kCookieFields = 8;
constexpr std::size_t kMaxCookieRows = 256;
constexpr std::size_t kMaxCookieBytes = 1u << 20;
constexpr std::size_t kMaxAuthorizedOrigins = 16;

/* name, value, domain, path, expiry in epoch milliseconds or "-1" for a session cookie, secure, httpOnly, sameSite. */
using CookieRow = std::array<std::string, kCookieFields>;

inline void appendUtf8(std::string& out, std::uint32_t codePoint) {
    if (codePoint < 0x80) {
        out.push_back(static_cast<char>(codePoint));
    } else if (codePoint < 0x800) {
        out.push_back(static_cast<char>(0xC0 | (codePoint >> 6)));
        out.push_back(static_cast<char>(0x80 | (codePoint & 0x3F)));
    } else if (codePoint < 0x10000) {
        out.push_back(static_cast<char>(0xE0 | (codePoint >> 12)));
        out.push_back(static_cast<char>(0x80 | ((codePoint >> 6) & 0x3F)));
        out.push_back(static_cast<char>(0x80 | (codePoint & 0x3F)));
    } else {
        out.push_back(static_cast<char>(0xF0 | (codePoint >> 18)));
        out.push_back(static_cast<char>(0x80 | ((codePoint >> 12) & 0x3F)));
        out.push_back(static_cast<char>(0x80 | ((codePoint >> 6) & 0x3F)));
        out.push_back(static_cast<char>(0x80 | (codePoint & 0x3F)));
    }
}

/* Invalid UTF-8 becomes U+FFFD. A bad value must not fail the sign-in window, so the decoder never throws. */
inline std::u16string utf16FromUtf8(const std::string& in) {
    std::u16string out;
    out.reserve(in.size());
    std::size_t i = 0;
    while (i < in.size()) {
        const unsigned char lead = static_cast<unsigned char>(in[i]);
        std::uint32_t cp = 0;
        std::size_t length = 0;
        if (lead < 0x80) {
            cp = lead;
            length = 1;
        } else if (lead >= 0xC2 && lead <= 0xDF) {
            cp = lead & 0x1Fu;
            length = 2;
        } else if (lead >= 0xE0 && lead <= 0xEF) {
            cp = lead & 0x0Fu;
            length = 3;
        } else if (lead >= 0xF0 && lead <= 0xF4) {
            cp = lead & 0x07u;
            length = 4;
        } else {
            out.push_back(u'\uFFFD');
            ++i;
            continue;
        }
        if (i + length > in.size()) {
            out.push_back(u'\uFFFD');
            break;
        }
        bool valid = true;
        for (std::size_t k = 1; k < length; ++k) {
            const unsigned char next = static_cast<unsigned char>(in[i + k]);
            if ((next & 0xC0u) != 0x80u) {
                valid = false;
                break;
            }
            cp = (cp << 6) | (next & 0x3Fu);
        }
        if (!valid || (length == 3 && (cp < 0x800 || (cp >= 0xD800 && cp <= 0xDFFF))) ||
            (length == 4 && (cp < 0x10000 || cp > 0x10FFFF))) {
            out.push_back(u'\uFFFD');
            ++i;
            continue;
        }
        if (cp >= 0x10000) {
            cp -= 0x10000;
            out.push_back(static_cast<char16_t>(0xD800 + (cp >> 10)));
            out.push_back(static_cast<char16_t>(0xDC00 + (cp & 0x3FFu)));
        } else {
            out.push_back(static_cast<char16_t>(cp));
        }
        i += length;
    }
    return out;
}

inline std::string utf8FromUtf16(const jchar* units, std::size_t count) {
    std::string out;
    out.reserve(count);
    for (std::size_t i = 0; i < count; ++i) {
        std::uint32_t cp = units[i];
        if (cp >= 0xD800 && cp <= 0xDBFF && i + 1 < count && units[i + 1] >= 0xDC00 && units[i + 1] <= 0xDFFF) {
            cp = 0x10000 + ((cp - 0xD800) << 10) + (units[i + 1] - 0xDC00);
            ++i;
        } else if (cp >= 0xD800 && cp <= 0xDFFF) {
            cp = 0xFFFD;
        }
        appendUtf8(out, cp);
    }
    return out;
}

/* Reads through GetStringChars so the value is standard UTF-8, not the JNI modified UTF-8 form. */
inline std::string utf8FromJava(JNIEnv* env, jstring value) {
    if (!value) return std::string();
    const jsize length = env->GetStringLength(value);
    const jchar* units = env->GetStringChars(value, nullptr);
    if (!units) return std::string();
    std::string out = utf8FromUtf16(units, static_cast<std::size_t>(length));
    env->ReleaseStringChars(value, units);
    return out;
}

inline jstring javaString(JNIEnv* env, const std::string& utf8) {
    const std::u16string units = utf16FromUtf8(utf8);
    return env->NewString(reinterpret_cast<const jchar*>(units.data()), static_cast<jsize>(units.size()));
}

/* Bounded: an empty, oversized or malformed origin list yields an empty result, which refuses the attempt. */
inline std::vector<std::string> readOrigins(JNIEnv* env, jobjectArray origins) {
    std::vector<std::string> out;
    if (!origins) return out;
    const jsize count = env->GetArrayLength(origins);
    if (count < 1 || static_cast<std::size_t>(count) > kMaxAuthorizedOrigins) return out;
    for (jsize i = 0; i < count; ++i) {
        jobject element = env->GetObjectArrayElement(origins, i);
        out.push_back(utf8FromJava(env, static_cast<jstring>(element)));
        env->DeleteLocalRef(element);
    }
    return out;
}

inline bool contains(const std::vector<std::string>& values, const std::string& value) {
    for (const std::string& candidate : values) {
        if (candidate == value) return true;
    }
    return false;
}

/*
 * The canonical "https://host:port" of an absolute https URL, or an empty string for anything else. The rule is
 * deliberately strict: no userinfo, no IPv6 literal, no non-ASCII, no control characters, and the port is explicit
 * in the result. Each platform compares the result with the caller's authorized origins by exact match, so a URL the
 * parser cannot prove to be one of them is refused. Parsing it here keeps all three platforms on one rule.
 */
inline std::string httpsOriginOf(const std::string& url) {
    static const char kScheme[] = "https://";
    constexpr std::size_t kSchemeLength = sizeof(kScheme) - 1;
    if (url.size() <= kSchemeLength) return std::string();
    for (std::size_t i = 0; i < kSchemeLength; ++i) {
        if (std::tolower(static_cast<unsigned char>(url[i])) != static_cast<unsigned char>(kScheme[i])) {
            return std::string();
        }
    }
    const std::size_t end = url.find_first_of("/?#", kSchemeLength);
    const std::size_t length = end == std::string::npos ? std::string::npos : end - kSchemeLength;
    const std::string authority = url.substr(kSchemeLength, length);
    if (authority.empty()) return std::string();
    for (const char c : authority) {
        const unsigned char u = static_cast<unsigned char>(c);
        if (u <= 0x20 || u >= 0x7F || c == '@' || c == '\\' || c == '[' || c == ']' || c == '%') {
            return std::string();
        }
    }
    std::string host = authority;
    long long port = 443;
    const std::size_t colon = authority.rfind(':');
    if (colon != std::string::npos) {
        host = authority.substr(0, colon);
        const std::string digits = authority.substr(colon + 1);
        if (digits.empty() || digits.size() > 5) return std::string();
        port = 0;
        for (const char c : digits) {
            if (c < '0' || c > '9') return std::string();
            port = port * 10 + (c - '0');
        }
        if (port < 1 || port > 65535) return std::string();
    }
    if (host.empty() || host.find(':') != std::string::npos) return std::string();
    for (char& c : host) c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
    return "https://" + host + ":" + std::to_string(port);
}

/*
 * Refuses the whole attempt when any authorized origin is not already canonical. An empty entry would otherwise
 * match the empty result httpsOriginOf gives for a non-https navigation, which is exactly the navigation to refuse.
 */
inline bool allCanonical(const std::vector<std::string>& origins) {
    for (const std::string& origin : origins) {
        if (origin.empty() || httpsOriginOf(origin) != origin) return false;
    }
    return true;
}

/* The host part of a canonical origin from httpsOriginOf, used to match cookie domains natively. */
inline std::string hostOfOrigin(const std::string& origin) {
    const std::size_t colon = origin.rfind(':');
    if (origin.compare(0, 8, "https://") != 0 || colon == std::string::npos || colon < 8) return std::string();
    return origin.substr(8, colon - 8);
}

/* Whether a cookie domain, with or without a leading dot, applies to a host. Mirrors the Kotlin-side rule. */
inline bool cookieDomainApplies(const std::string& host, std::string domain) {
    if (!domain.empty() && domain.front() == '.') domain.erase(0, 1);
    for (char& c : domain) c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
    if (domain.empty() || host.empty()) return false;
    if (host == domain) return true;
    return host.size() > domain.size() && host.compare(host.size() - domain.size() - 1, std::string::npos, "." + domain) == 0;
}

/*
 * A JNI open call queues the window creation to the platform owner and waits a bounded time for the answer. When the
 * wait ends, a creation that has not started is cancelled, so no window appears without a caller to own it. A
 * creation that has started finishes promptly, and the caller takes its answer instead of leaving an unowned window.
 */
class OpenTicket {
public:
    /* Owner thread: false means the caller gave up first, so nothing may be created. */
    bool begin() {
        int expected = kQueued;
        return state_.compare_exchange_strong(expected, kStarted);
    }

    /* Caller: false means the owner already started, so the caller must wait for the answer it is producing. */
    bool abandon() {
        int expected = kQueued;
        return state_.compare_exchange_strong(expected, kAbandoned);
    }

private:
    static constexpr int kQueued = 0;
    static constexpr int kStarted = 1;
    static constexpr int kAbandoned = 2;
    std::atomic<int> state_{kQueued};
};

/*
 * Delivers events to the Java listener. The listener only completes futures, so a call never blocks the platform
 * loop for long. A call attaches the owner thread to the JVM when it is not attached yet. The listener runs on the
 * platform owner, so it must not call back into the browser synchronously. Listener method names and signatures are
 * part of the NativeCookieBrowser JNI contract and must match the Kotlin declarations.
 */
class BrowserCallbacks {
public:
    BrowserCallbacks(JNIEnv* env, jobject listener) {
        env->GetJavaVM(&vm_);
        if (!listener) return;
        target_ = env->NewGlobalRef(listener);
        jclass type = env->GetObjectClass(listener);
        done_ = env->GetMethodID(type, "onDone", "()V");
        cookies_ = env->GetMethodID(type, "onCookies", "(J[[Ljava/lang/String;)V");
        closed_ = env->GetMethodID(type, "onClosed", "(Z)V");
        env->DeleteLocalRef(type);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            done_ = nullptr;
            cookies_ = nullptr;
            closed_ = nullptr;
        }
    }

    ~BrowserCallbacks() {
        if (target_) withEnv([this](JNIEnv* env) { env->DeleteGlobalRef(target_); });
    }

    BrowserCallbacks(const BrowserCallbacks&) = delete;
    BrowserCallbacks& operator=(const BrowserCallbacks&) = delete;

    bool valid() const { return target_ != nullptr && done_ != nullptr && cookies_ != nullptr && closed_ != nullptr; }

    void notifyDone() {
        withEnv([this](JNIEnv* env) { env->CallVoidMethod(target_, done_); });
    }

    /* A null rows pointer reports a failed or oversized read; the Kotlin side treats it as unavailable. */
    void notifyCookies(jlong requestId, const std::vector<CookieRow>* rows) {
        withEnv([&](JNIEnv* env) {
            jobjectArray result = rows ? buildRows(env, *rows) : nullptr;
            env->CallVoidMethod(target_, cookies_, requestId, result);
            if (result) env->DeleteLocalRef(result);
        });
    }

    void notifyClosed(bool failed) {
        withEnv([&](JNIEnv* env) { env->CallVoidMethod(target_, closed_, failed ? JNI_TRUE : JNI_FALSE); });
    }

private:
    template <typename Work>
    void withEnv(Work work) const {
        if (!vm_ || !target_) return;
        JNIEnv* env = nullptr;
        bool attached = false;
        const jint status = vm_->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_8);
        if (status == JNI_EDETACHED) {
            if (vm_->AttachCurrentThread(reinterpret_cast<void**>(&env), nullptr) != JNI_OK) return;
            attached = true;
        } else if (status != JNI_OK) {
            return;
        }
        work(env);
        if (env->ExceptionCheck()) env->ExceptionClear();
        if (attached) vm_->DetachCurrentThread();
    }

    static jobjectArray buildRows(JNIEnv* env, const std::vector<CookieRow>& rows) {
        jclass stringType = env->FindClass("java/lang/String");
        jclass rowType = env->FindClass("[Ljava/lang/String;");
        if (!stringType || !rowType) return nullptr;
        jobjectArray result = env->NewObjectArray(static_cast<jsize>(rows.size()), rowType, nullptr);
        for (std::size_t i = 0; result && i < rows.size(); ++i) {
            jobjectArray row = env->NewObjectArray(static_cast<jsize>(kCookieFields), stringType, nullptr);
            if (!row) break;
            for (std::size_t j = 0; j < kCookieFields; ++j) {
                jstring field = javaString(env, rows[i][j]);
                env->SetObjectArrayElement(row, static_cast<jsize>(j), field);
                env->DeleteLocalRef(field);
            }
            env->SetObjectArrayElement(result, static_cast<jsize>(i), row);
            env->DeleteLocalRef(row);
        }
        env->DeleteLocalRef(rowType);
        env->DeleteLocalRef(stringType);
        return result;
    }

    JavaVM* vm_ = nullptr;
    jobject target_ = nullptr;
    jmethodID done_ = nullptr;
    jmethodID cookies_ = nullptr;
    jmethodID closed_ = nullptr;
};

}  // namespace archivetune::browser
