/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 *
 * AAudio exclusive-mode float output — implementation.
 */

#include "aaudio_output.h"

#include <dlfcn.h>
#include <time.h>

#include <algorithm>

namespace archivetune::dsp {

namespace {

// AAudio is an Android 8.0 (API 26) platform library. Linking it would give
// libarchivetune_dsp a dependency that Android 7.x does not provide, and the
// whole float DSP library would fail to load there. The entry points are
// resolved with dlopen() on first use instead. Where the library or one of the
// required entry points is missing, open() reports AAUDIO_ERROR_UNAVAILABLE and
// the caller keeps the standard output.
struct AAudioApi {
    aaudio_result_t (*createStreamBuilder)(AAudioStreamBuilder**);
    void (*builderSetDirection)(AAudioStreamBuilder*, aaudio_direction_t);
    void (*builderSetFormat)(AAudioStreamBuilder*, aaudio_format_t);
    void (*builderSetSampleRate)(AAudioStreamBuilder*, int32_t);
    void (*builderSetChannelCount)(AAudioStreamBuilder*, int32_t);
    void (*builderSetSharingMode)(AAudioStreamBuilder*, aaudio_sharing_mode_t);
    void (*builderSetPerformanceMode)(AAudioStreamBuilder*, aaudio_performance_mode_t);
    void (*builderSetDeviceId)(AAudioStreamBuilder*, int32_t);
    void (*builderSetBufferCapacityInFrames)(AAudioStreamBuilder*, int32_t);
    aaudio_result_t (*builderOpenStream)(AAudioStreamBuilder*, AAudioStream**);
    aaudio_result_t (*builderDelete)(AAudioStreamBuilder*);
    aaudio_result_t (*streamClose)(AAudioStream*);
    aaudio_result_t (*streamWrite)(AAudioStream*, const void*, int32_t, int64_t);
    aaudio_result_t (*streamRequestStart)(AAudioStream*);
    aaudio_result_t (*streamRequestPause)(AAudioStream*);
    aaudio_result_t (*streamRequestFlush)(AAudioStream*);
    aaudio_result_t (*streamRequestStop)(AAudioStream*);
    aaudio_result_t (*streamGetTimestamp)(AAudioStream*, clockid_t, int64_t*, int64_t*);
    int32_t (*streamGetSampleRate)(AAudioStream*);
    int32_t (*streamGetChannelCount)(AAudioStream*);
    aaudio_sharing_mode_t (*streamGetSharingMode)(AAudioStream*);
    int32_t (*streamGetBufferCapacityInFrames)(AAudioStream*);
    int32_t (*streamGetBufferSizeInFrames)(AAudioStream*);
    int32_t (*streamSetBufferSizeInFrames)(AAudioStream*, int32_t);
    // Optional entries: the usage/content-type setters and the session id are
    // API 28+, and a missing xrun counter reads as zero.
    void (*builderSetUsage)(AAudioStreamBuilder*, aaudio_usage_t);
    void (*builderSetContentType)(AAudioStreamBuilder*, aaudio_content_type_t);
    int32_t (*streamGetSessionId)(AAudioStream*);
    int32_t (*streamGetXRunCount)(AAudioStream*);
};

template <typename Fn>
bool bindSymbol(void* lib, const char* name, Fn& slot) {
    slot = reinterpret_cast<Fn>(dlsym(lib, name));
    return slot != nullptr;
}

bool bindAAudio(AAudioApi* api) {
    void* lib = dlopen("libaaudio.so", RTLD_NOW | RTLD_LOCAL);
    if (lib == nullptr) return false;
    const bool required =
        bindSymbol(lib, "AAudio_createStreamBuilder", api->createStreamBuilder) &&
        bindSymbol(lib, "AAudioStreamBuilder_setDirection", api->builderSetDirection) &&
        bindSymbol(lib, "AAudioStreamBuilder_setFormat", api->builderSetFormat) &&
        bindSymbol(lib, "AAudioStreamBuilder_setSampleRate", api->builderSetSampleRate) &&
        bindSymbol(lib, "AAudioStreamBuilder_setChannelCount", api->builderSetChannelCount) &&
        bindSymbol(lib, "AAudioStreamBuilder_setSharingMode", api->builderSetSharingMode) &&
        bindSymbol(lib, "AAudioStreamBuilder_setPerformanceMode", api->builderSetPerformanceMode) &&
        bindSymbol(lib, "AAudioStreamBuilder_setDeviceId", api->builderSetDeviceId) &&
        bindSymbol(lib, "AAudioStreamBuilder_setBufferCapacityInFrames",
                   api->builderSetBufferCapacityInFrames) &&
        bindSymbol(lib, "AAudioStreamBuilder_openStream", api->builderOpenStream) &&
        bindSymbol(lib, "AAudioStreamBuilder_delete", api->builderDelete) &&
        bindSymbol(lib, "AAudioStream_close", api->streamClose) &&
        bindSymbol(lib, "AAudioStream_write", api->streamWrite) &&
        bindSymbol(lib, "AAudioStream_requestStart", api->streamRequestStart) &&
        bindSymbol(lib, "AAudioStream_requestPause", api->streamRequestPause) &&
        bindSymbol(lib, "AAudioStream_requestFlush", api->streamRequestFlush) &&
        bindSymbol(lib, "AAudioStream_requestStop", api->streamRequestStop) &&
        bindSymbol(lib, "AAudioStream_getTimestamp", api->streamGetTimestamp) &&
        bindSymbol(lib, "AAudioStream_getSampleRate", api->streamGetSampleRate) &&
        bindSymbol(lib, "AAudioStream_getChannelCount", api->streamGetChannelCount) &&
        bindSymbol(lib, "AAudioStream_getSharingMode", api->streamGetSharingMode) &&
        bindSymbol(lib, "AAudioStream_getBufferCapacityInFrames",
                   api->streamGetBufferCapacityInFrames) &&
        bindSymbol(lib, "AAudioStream_getBufferSizeInFrames", api->streamGetBufferSizeInFrames) &&
        bindSymbol(lib, "AAudioStream_setBufferSizeInFrames", api->streamSetBufferSizeInFrames);
    bindSymbol(lib, "AAudioStreamBuilder_setUsage", api->builderSetUsage);
    bindSymbol(lib, "AAudioStreamBuilder_setContentType", api->builderSetContentType);
    bindSymbol(lib, "AAudioStream_getSessionId", api->streamGetSessionId);
    bindSymbol(lib, "AAudioStream_getXRunCount", api->streamGetXRunCount);
    return required;
}

// The resolved table, or nullptr where AAudio is unavailable. The statics make
// the one-time resolution thread-safe.
const AAudioApi* aaudio() {
    static AAudioApi table{};
    static const bool loaded = bindAAudio(&table);
    return loaded ? &table : nullptr;
}

}  // namespace

AaudioExclusiveStream::~AaudioExclusiveStream() {
    close();
}

int32_t AaudioExclusiveStream::open(int32_t sampleRate,
                                     int32_t channels,
                                     int32_t deviceId,
                                     bool exclusive,
                                     int32_t usage,
                                     int32_t contentType,
                                     int32_t bufferCapacityFrames) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (open_) {
        closeLocked();
    }

    const AAudioApi* api = aaudio();
    if (api == nullptr) {
        return AAUDIO_ERROR_UNAVAILABLE;
    }

    AAudioStreamBuilder* builder = nullptr;
    aaudio_result_t result = api->createStreamBuilder(&builder);
    if (result != AAUDIO_OK || builder == nullptr) {
        return result != AAUDIO_OK ? result : AAUDIO_ERROR_INTERNAL;
    }

    api->builderSetDirection(builder, AAUDIO_DIRECTION_OUTPUT);
    api->builderSetFormat(builder, AAUDIO_FORMAT_PCM_FLOAT);
    api->builderSetSampleRate(builder, sampleRate);
    api->builderSetChannelCount(builder, channels);
    api->builderSetSharingMode(builder,
                               exclusive ? AAUDIO_SHARING_MODE_EXCLUSIVE
                                         : AAUDIO_SHARING_MODE_SHARED);
    // LowLatency routes through the FastMixer path when the HAL grants it;
    // exclusive already bypasses the mixer entirely on most devices.
    api->builderSetPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    if (deviceId > 0) {
        api->builderSetDeviceId(builder, deviceId);
    }
#if __ANDROID_API__ >= 28
    // Usage/content-type setters are API 28+; below that the stream routes
    // with the framework's media defaults, which match anyway. The NDK platform
    // level decides whether this block is compiled (AGP takes it from minSdk),
    // not the device's own API level.
    if (usage >= 0 && api->builderSetUsage != nullptr) {
        api->builderSetUsage(builder, static_cast<aaudio_usage_t>(usage));
    }
    if (contentType >= 0 && api->builderSetContentType != nullptr) {
        api->builderSetContentType(builder, static_cast<aaudio_content_type_t>(contentType));
    }
#endif
    if (bufferCapacityFrames > 0) {
        api->builderSetBufferCapacityInFrames(builder, bufferCapacityFrames);
    }

    AAudioStream* stream = nullptr;
    result = api->builderOpenStream(builder, &stream);
    api->builderDelete(builder);
    if (result != AAUDIO_OK) {
        return result;
    }

    // Low-latency streams open with a buffer of a burst or two, but Media3 feeds
    // the output from its playback loop roughly every 10 ms. Latency does not
    // matter for music playback, so use the whole capacity to ride out the gaps.
    const int32_t capacity = api->streamGetBufferCapacityInFrames(stream);
    if (capacity > 0) {
        api->streamSetBufferSizeInFrames(stream, capacity);
    }

    stream_ = stream;
    sampleRate_ = api->streamGetSampleRate(stream);
    channels_ = api->streamGetChannelCount(stream);
    exclusiveGranted_ = api->streamGetSharingMode(stream) == AAUDIO_SHARING_MODE_EXCLUSIVE;
    framesWritten_ = 0;
    open_ = true;

    if (exclusive && !exclusiveGranted_) {
        // The caller asked for exclusive and the HAL demoted us to shared:
        // report failure so the caller falls back to the standard sink
        // instead of silently running through the mixer anyway.
        closeLocked();
        return AAUDIO_ERROR_INVALID_STATE;
    }
    return AAUDIO_OK;
}

int64_t AaudioExclusiveStream::write(const float* data, size_t frames, int32_t channels, int64_t timeoutMs) {
    std::lock_guard<std::mutex> lock(mutex_);
    const AAudioApi* api = aaudio();
    if (!open_ || stream_ == nullptr || api == nullptr) {
        return AAUDIO_ERROR_DISCONNECTED;
    }
    // The caller's interleave governs the pointer stride. A stream whose
    // granted layout disagrees with the buffer's cannot be written safely —
    // striding by the stream's own count would read past the caller's buffer.
    if (channels <= 0 || channels != channels_) {
        return AAUDIO_ERROR_ILLEGAL_ARGUMENT;
    }
    int64_t written = 0;
    while (written < static_cast<int64_t>(frames)) {
        aaudio_result_t result = api->streamWrite(
            stream_,
            data + written * static_cast<size_t>(channels),
            static_cast<int32_t>(frames - static_cast<size_t>(written)),
            static_cast<int64_t>(timeoutMs) * 1000000LL);
        if (result < 0) {
            return result;
        }
        if (result == 0) {
            // Timeout with nothing accepted — surface as a short write so the
            // caller can distinguish a stall from an error.
            return written;
        }
        written += result;
        framesWritten_ += result;
    }
    return written;
}

int32_t AaudioExclusiveStream::requestStart() {
    std::lock_guard<std::mutex> lock(mutex_);
    const AAudioApi* api = aaudio();
    return open_ && stream_ != nullptr && api != nullptr
               ? api->streamRequestStart(stream_)
               : AAUDIO_ERROR_INVALID_STATE;
}

int32_t AaudioExclusiveStream::requestPause() {
    std::lock_guard<std::mutex> lock(mutex_);
    const AAudioApi* api = aaudio();
    return open_ && stream_ != nullptr && api != nullptr
               ? api->streamRequestPause(stream_)
               : AAUDIO_ERROR_INVALID_STATE;
}

int32_t AaudioExclusiveStream::requestFlush() {
    std::lock_guard<std::mutex> lock(mutex_);
    // AAudio flushes the buffer of a PAUSED stream; requesting flush on a
    // running stream is an error, so the pause-then-flush idiom is the only
    // correct one (the caller drives pause() first).
    const AAudioApi* api = aaudio();
    return open_ && stream_ != nullptr && api != nullptr
               ? api->streamRequestFlush(stream_)
               : AAUDIO_ERROR_INVALID_STATE;
}

int32_t AaudioExclusiveStream::requestStop() {
    std::lock_guard<std::mutex> lock(mutex_);
    const AAudioApi* api = aaudio();
    return open_ && stream_ != nullptr && api != nullptr
               ? api->streamRequestStop(stream_)
               : AAUDIO_ERROR_INVALID_STATE;
}

void AaudioExclusiveStream::close() {
    std::lock_guard<std::mutex> lock(mutex_);
    closeLocked();
}

void AaudioExclusiveStream::closeLocked() {
    if (stream_ != nullptr) {
        if (const AAudioApi* api = aaudio()) {
            api->streamClose(stream_);
        }
        stream_ = nullptr;
    }
    open_ = false;
    exclusiveGranted_ = false;
}

AaudioExclusiveStream::Timestamp AaudioExclusiveStream::timestamp() const {
    std::lock_guard<std::mutex> lock(mutex_);
    Timestamp ts{0, 0, false};
    const AAudioApi* api = aaudio();
    if (!open_ || stream_ == nullptr || api == nullptr) {
        return ts;
    }
    int64_t frames = 0;
    int64_t nanos = 0;
    aaudio_result_t result =
        api->streamGetTimestamp(stream_, CLOCK_MONOTONIC, &frames, &nanos);
    ts.valid = result == AAUDIO_OK;
    ts.framePosition = frames;
    ts.timeNanos = nanos;
    return ts;
}

int32_t AaudioExclusiveStream::sampleRate() const {
    return sampleRate_;
}

int32_t AaudioExclusiveStream::channelCount() const {
    return channels_;
}

int32_t AaudioExclusiveStream::sessionId() const {
    std::lock_guard<std::mutex> lock(mutex_);
#if __ANDROID_API__ >= 28
    const AAudioApi* api = aaudio();
    return open_ && stream_ != nullptr && api != nullptr && api->streamGetSessionId != nullptr
               ? api->streamGetSessionId(stream_)
               : 0;
#else
    return 0;
#endif
}

int64_t AaudioExclusiveStream::framesWritten() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return framesWritten_;
}

int32_t AaudioExclusiveStream::xRunCount() const {
    std::lock_guard<std::mutex> lock(mutex_);
    const AAudioApi* api = aaudio();
    return open_ && stream_ != nullptr && api != nullptr && api->streamGetXRunCount != nullptr
               ? api->streamGetXRunCount(stream_)
               : 0;
}

int64_t AaudioExclusiveStream::bufferCapacityFrames() const {
    std::lock_guard<std::mutex> lock(mutex_);
    const AAudioApi* api = aaudio();
    return open_ && stream_ != nullptr && api != nullptr
               ? api->streamGetBufferCapacityInFrames(stream_)
               : 0;
}

int64_t AaudioExclusiveStream::bufferSizeFrames() const {
    std::lock_guard<std::mutex> lock(mutex_);
    const AAudioApi* api = aaudio();
    return open_ && stream_ != nullptr && api != nullptr
               ? api->streamGetBufferSizeInFrames(stream_)
               : 0;
}

bool AaudioExclusiveStream::isExclusive() const {
    return exclusiveGranted_;
}

bool AaudioExclusiveStream::isOpen() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return open_;
}

}  // namespace archivetune::dsp
