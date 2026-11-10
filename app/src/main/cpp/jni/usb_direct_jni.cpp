/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 *
 * JNI bridge for the direct USB audio output (native/usbdirect).
 *
 * Kotlin host:
 *  - moe.rukamori.archivetune.playback.usbdirect.UsbDirectNative (object)
 *
 * Every entry point reports failure as a negative int (negative errno or the
 * kError* values in usb_direct_output.h) instead of throwing, so the audio
 * thread never pays for an exception or a class lookup on the error path.
 * Handles are keys into a table rather than pointers: a write racing a close
 * then finds nothing instead of freed memory.
 */

#include <jni.h>

#include <algorithm>
#include <cstdint>
#include <memory>
#include <mutex>
#include <new>
#include <unordered_map>
#include <vector>

#include "usbdirect/usb_direct_output.h"

#define JNI_FUNC_USB(name) \
    Java_moe_rukamori_archivetune_playback_usbdirect_UsbDirectNative_##name

using natune::usbdirect::Candidate;
using natune::usbdirect::Output;

namespace {

std::mutex gHandlesMutex;
std::unordered_map<jlong, std::shared_ptr<Output>> gHandles;
jlong gNextHandle = 1;

std::shared_ptr<Output> find(jlong handle) {
    std::lock_guard<std::mutex> lock(gHandlesMutex);
    const auto it = gHandles.find(handle);
    return it == gHandles.end() ? nullptr : it->second;
}

}  // namespace

// result receives [error or chosen candidate index, capacity in frames].
// Returns a handle, or 0 with the error in result[0].
extern "C" JNIEXPORT jlong JNICALL JNI_FUNC_USB(nativeOpen)(
    JNIEnv *env, jobject, jint fd, jintArray candidates, jintArray result) {
    if (candidates == nullptr || result == nullptr || env->GetArrayLength(result) < 2 ||
        static_cast<size_t>(env->GetArrayLength(candidates)) > natune::usbdirect::kMaxCandidates * natune::usbdirect::kCandidateInts) return 0;
    jint out[2] = {natune::usbdirect::kErrorInvalidArgument, 0};
    jlong handle = 0;
    try {
        const jsize length = candidates != nullptr ? env->GetArrayLength(candidates) : 0;
        std::vector<jint> values(static_cast<size_t>(length));
        if (length > 0) env->GetIntArrayRegion(candidates, 0, length, values.data());
        std::vector<Candidate> decoded;
        if (natune::usbdirect::decodeCandidates(reinterpret_cast<const int32_t *>(values.data()),
                                                values.size(), &decoded)) {
            std::shared_ptr<Output> output;
            const int chosen = Output::open(fd, std::move(decoded), &output);
            out[0] = chosen;
            if (chosen >= 0 && output) {
                out[1] = static_cast<jint>(output->capacityFrames());
                std::lock_guard<std::mutex> lock(gHandlesMutex);
                handle = gNextHandle++;
                gHandles.emplace(handle, std::move(output));
            }
        }
    } catch (const std::bad_alloc &) {
        out[0] = natune::usbdirect::kErrorNoMemory;
        handle = 0;
    }
    if (result != nullptr && env->GetArrayLength(result) >= 2) env->SetIntArrayRegion(result, 0, 2, out);
    return handle;
}

// Writes [offset, offset + count) of a direct buffer. Returns bytes taken.
extern "C" JNIEXPORT jint JNICALL JNI_FUNC_USB(nativeWrite)(
    JNIEnv *env, jobject, jlong handle, jobject buffer, jint offset, jint count) {
    const auto output = find(handle);
    if (!output) return natune::usbdirect::kErrorClosed;
    auto *base = static_cast<uint8_t *>(env->GetDirectBufferAddress(buffer));
    const jlong capacity = env->GetDirectBufferCapacity(buffer);
    if (base == nullptr || offset < 0 || count < 0 || static_cast<jlong>(offset) + count > capacity) {
        return natune::usbdirect::kErrorInvalidArgument;
    }
    return output->write(base + offset, static_cast<size_t>(count));
}

extern "C" JNIEXPORT jint JNICALL JNI_FUNC_USB(nativeSetPlaying)(
    JNIEnv *, jobject, jlong handle, jboolean playing) {
    const auto output = find(handle);
    return output ? output->setPlaying(playing != JNI_FALSE) : natune::usbdirect::kErrorClosed;
}

extern "C" JNIEXPORT jint JNICALL JNI_FUNC_USB(nativeFlush)(JNIEnv *, jobject, jlong handle) {
    const auto output = find(handle);
    return output ? output->flush() : natune::usbdirect::kErrorClosed;
}

extern "C" JNIEXPORT jlong JNICALL JNI_FUNC_USB(nativePlayedFrames)(JNIEnv *, jobject, jlong handle) {
    const auto output = find(handle);
    return output ? static_cast<jlong>(output->playedFrames()) : natune::usbdirect::kErrorClosed;
}

// Class request to an interface; for input requests data receives the reply.
// Returns bytes transferred or a negative error.
extern "C" JNIEXPORT jint JNICALL JNI_FUNC_USB(nativeControl)(
    JNIEnv *env, jobject, jlong handle, jboolean input, jint request, jint value, jint index, jbyteArray data) {
    const auto output = find(handle);
    if (!output) return natune::usbdirect::kErrorClosed;
    const jsize length = data != nullptr ? env->GetArrayLength(data) : 0;
    if (length > 1024 || request < 0 || request > 0xff || value < 0 || value > 0xffff || index < 0 ||
        index > 0xffff) {
        return natune::usbdirect::kErrorInvalidArgument;
    }
    try {
        std::vector<uint8_t> bytes(static_cast<size_t>(length));
        if (length > 0) env->GetByteArrayRegion(data, 0, length, reinterpret_cast<jbyte *>(bytes.data()));
        const int result = output->control(input != JNI_FALSE, static_cast<uint8_t>(request),
                                           static_cast<uint16_t>(value), static_cast<uint16_t>(index),
                                           bytes.data(), static_cast<uint16_t>(length));
        if (result > 0 && input != JNI_FALSE) {
            env->SetByteArrayRegion(data, 0, std::min<jsize>(result, length),
                                    reinterpret_cast<const jbyte *>(bytes.data()));
        }
        return result;
    } catch (const std::bad_alloc &) {
        return natune::usbdirect::kErrorNoMemory;
    }
}

extern "C" JNIEXPORT void JNICALL JNI_FUNC_USB(nativeClose)(JNIEnv *, jobject, jlong handle) {
    std::shared_ptr<Output> output;
    {
        std::lock_guard<std::mutex> lock(gHandlesMutex);
        const auto it = gHandles.find(handle);
        if (it == gHandles.end()) return;
        output = std::move(it->second);
        gHandles.erase(it);
    }
    output->close();
}
