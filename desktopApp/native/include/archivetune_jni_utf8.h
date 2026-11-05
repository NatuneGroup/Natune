/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
#pragma once

#include <jni.h>
#include <stddef.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

/*
 * JNI's GetStringUTFChars/NewStringUTF exchange modified UTF-8: a supplementary character arrives as
 * two encoded surrogates (CESU-8), which is not valid UTF-8. File paths and FFmpeg error text are
 * standard UTF-8, so the bindings convert through UTF-16 with the codecs below instead.
 */

#define AE_UTF8_INVALID ((size_t)-1)

/*
 * Encodes UTF-16 code units as standard UTF-8. Unpaired surrogates and embedded NUL become U+FFFD,
 * because a C string cannot carry a NUL and a path cannot carry either. The result is NUL-terminated
 * when it fits; returns the byte length without that terminator, or AE_UTF8_INVALID when it does not fit.
 */
static inline size_t ae_utf16_to_utf8(const jchar *chars, size_t length, char *out, size_t capacity) {
    size_t used = 0;
    for (size_t i = 0; i < length; ++i) {
        uint32_t code = (uint32_t)chars[i];
        if (code >= 0xd800u && code <= 0xdbffu && i + 1 < length &&
            chars[i + 1] >= 0xdc00u && chars[i + 1] <= 0xdfffu) {
            code = 0x10000u + ((code - 0xd800u) << 10) + ((uint32_t)chars[++i] - 0xdc00u);
        } else if ((code >= 0xd800u && code <= 0xdfffu) || code == 0u) {
            code = 0xfffdu;
        }
        size_t needed = code < 0x80u ? 1u : code < 0x800u ? 2u : code < 0x10000u ? 3u : 4u;
        if (used > capacity || needed + 1u > capacity - used) return AE_UTF8_INVALID;
        if (needed == 1u) {
            out[used++] = (char)code;
        } else if (needed == 2u) {
            out[used++] = (char)(0xc0u | (code >> 6));
            out[used++] = (char)(0x80u | (code & 0x3fu));
        } else if (needed == 3u) {
            out[used++] = (char)(0xe0u | (code >> 12));
            out[used++] = (char)(0x80u | ((code >> 6) & 0x3fu));
            out[used++] = (char)(0x80u | (code & 0x3fu));
        } else {
            out[used++] = (char)(0xf0u | (code >> 18));
            out[used++] = (char)(0x80u | ((code >> 12) & 0x3fu));
            out[used++] = (char)(0x80u | ((code >> 6) & 0x3fu));
            out[used++] = (char)(0x80u | (code & 0x3fu));
        }
    }
    out[used] = '\0';
    return used;
}

/*
 * Decodes standard UTF-8 into UTF-16 code units. Malformed or truncated sequences become U+FFFD, so a
 * native error string still reaches Kotlin. Returns the unit count, or AE_UTF8_INVALID when it does not fit.
 */
static inline size_t ae_utf8_to_utf16(const char *text, size_t length, jchar *out, size_t capacity) {
    size_t used = 0;
    size_t i = 0;
    while (i < length) {
        uint32_t code;
        unsigned char first = (unsigned char)text[i++];
        if (first < 0x80u) {
            code = first;
        } else if ((first & 0xe0u) == 0xc0u && i < length && ((unsigned char)text[i] & 0xc0u) == 0x80u) {
            code = ((uint32_t)(first & 0x1fu) << 6) | ((uint32_t)(unsigned char)text[i++] & 0x3fu);
            if (code < 0x80u) code = 0xfffdu;
        } else if ((first & 0xf0u) == 0xe0u && i + 1 < length &&
                   ((unsigned char)text[i] & 0xc0u) == 0x80u && ((unsigned char)text[i + 1] & 0xc0u) == 0x80u) {
            code = ((uint32_t)(first & 0x0fu) << 12) |
                   (((uint32_t)(unsigned char)text[i] & 0x3fu) << 6) |
                   ((uint32_t)(unsigned char)text[i + 1] & 0x3fu);
            i += 2;
            if (code < 0x800u || (code >= 0xd800u && code <= 0xdfffu)) code = 0xfffdu;
        } else if ((first & 0xf8u) == 0xf0u && i + 2 < length &&
                   ((unsigned char)text[i] & 0xc0u) == 0x80u && ((unsigned char)text[i + 1] & 0xc0u) == 0x80u &&
                   ((unsigned char)text[i + 2] & 0xc0u) == 0x80u) {
            code = ((uint32_t)(first & 0x07u) << 18) |
                   (((uint32_t)(unsigned char)text[i] & 0x3fu) << 12) |
                   (((uint32_t)(unsigned char)text[i + 1] & 0x3fu) << 6) |
                   ((uint32_t)(unsigned char)text[i + 2] & 0x3fu);
            i += 3;
            if (code < 0x10000u || code > 0x10ffffu) code = 0xfffdu;
        } else {
            code = 0xfffdu;
        }
        if (code >= 0x10000u) {
            if (used + 2u > capacity) return AE_UTF8_INVALID;
            code -= 0x10000u;
            out[used++] = (jchar)(0xd800u + (code >> 10));
            out[used++] = (jchar)(0xdc00u + (code & 0x3ffu));
        } else {
            if (used + 1u > capacity) return AE_UTF8_INVALID;
            out[used++] = (jchar)code;
        }
    }
    return used;
}

#ifndef __cplusplus
/* C bindings use these wrappers. C++ callers keep their own JNI calls because JNIEnv is a class there. */

static inline char *jni_utf8_from_string(JNIEnv *env, jstring value) {
    if (!value) return NULL;
    const jchar *chars = (*env)->GetStringChars(env, value, NULL);
    if (!chars) return NULL;
    jsize length = (*env)->GetStringLength(env, value);
    if ((size_t)length > (SIZE_MAX - 1u) / 4u) {
        (*env)->ReleaseStringChars(env, value, chars);
        return NULL;
    }
    char *result = (char *)malloc((size_t)length * 4u + 1u);
    if (result && ae_utf16_to_utf8(chars, (size_t)length, result, (size_t)length * 4u + 1u) == AE_UTF8_INVALID) {
        free(result);
        result = NULL;
    }
    (*env)->ReleaseStringChars(env, value, chars);
    return result;
}

static inline jstring jni_string_from_utf8(JNIEnv *env, const char *value) {
    if (!value) return NULL;
    size_t length = strlen(value);
    if (length > (size_t)0x7fffffffu || length > SIZE_MAX / sizeof(jchar) - 1u) return NULL;
    jchar *units = (jchar *)malloc((length + 1u) * sizeof(jchar));
    if (!units) return NULL;
    size_t used = ae_utf8_to_utf16(value, length, units, length + 1u);
    if (used == AE_UTF8_INVALID) {
        free(units);
        return NULL;
    }
    jstring result = (*env)->NewString(env, units, (jsize)used);
    free(units);
    return result;
}

#endif
