#include "archivetune_coreaudio_exclusive.h"

#if defined(__APPLE__)
#include <TargetConditionals.h>
#if !TARGET_OS_IPHONE
#include <CoreAudio/CoreAudio.h>
#include <CoreFoundation/CoreFoundation.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>

struct ae_coreaudio_owner {
    AudioDeviceID device;
    pid_t previous_owner;
    Float64 previous_rate;
    AudioStreamID stream;
    AudioStreamBasicDescription previous_format;
    int rate_changed, format_changed, acquired;
};

static AudioObjectPropertyAddress property(AudioObjectPropertySelector selector, AudioObjectPropertyScope scope) {
    AudioObjectPropertyAddress address = {selector, scope, kAudioObjectPropertyElementMain};
    return address;
}

static int physical_pcm_matches(const AudioStreamBasicDescription *format, const ae_pcm_format *pcm) {
    uint32_t bytes = pcm->format == AE_PCM_U8 ? 1u : pcm->format == AE_PCM_S16 ? 2u : pcm->format == AE_PCM_S24 ? 3u : 4u;
    int floating = (format->mFormatFlags & kAudioFormatFlagIsFloat) != 0;
    int signed_integer = (format->mFormatFlags & kAudioFormatFlagIsSignedInteger) != 0;
    /* The direct lane supplies little-endian samples with meaningful integer bits left-aligned. */
    return format->mFormatID == kAudioFormatLinearPCM && format->mChannelsPerFrame == pcm->channels &&
        format->mBitsPerChannel >= pcm->bits && format->mBitsPerChannel <= bytes * 8u &&
        format->mBytesPerFrame == bytes * pcm->channels && format->mFramesPerPacket == 1 &&
        format->mBytesPerPacket == format->mBytesPerFrame && floating == (pcm->format == AE_PCM_F32) &&
        (floating || signed_integer == (pcm->format != AE_PCM_U8)) &&
        !(format->mFormatFlags & kAudioFormatFlagIsBigEndian) &&
        (format->mBitsPerChannel == bytes * 8u || (format->mFormatFlags & kAudioFormatFlagIsAlignedHigh));
}

void ae_coreaudio_release(ae_coreaudio_owner *owner) {
    if (!owner) return;
    AudioObjectPropertyAddress hog = property(kAudioDevicePropertyHogMode, kAudioObjectPropertyScopeGlobal);
    pid_t current = -1;
    UInt32 size = sizeof current;
    /* Do not overwrite another process's settings if ownership changed while the device was disconnected. */
    if (owner->acquired && AudioObjectGetPropertyData(owner->device, &hog, 0, NULL, &size, &current) == noErr && current == getpid()) {
        if (owner->format_changed) {
            AudioObjectPropertyAddress format = property(kAudioStreamPropertyPhysicalFormat, kAudioObjectPropertyScopeGlobal);
            AudioObjectSetPropertyData(owner->stream, &format, 0, NULL, sizeof owner->previous_format, &owner->previous_format);
        }
        if (owner->rate_changed) {
            AudioObjectPropertyAddress rate = property(kAudioDevicePropertyNominalSampleRate, kAudioObjectPropertyScopeGlobal);
            AudioObjectSetPropertyData(owner->device, &rate, 0, NULL, sizeof owner->previous_rate, &owner->previous_rate);
        }
        if (owner->previous_owner != getpid()) {
            pid_t released = -1;
            AudioObjectSetPropertyData(owner->device, &hog, 0, NULL, sizeof released, &released);
        }
    }
    free(owner);
}

uint32_t ae_coreaudio_device_id(const ae_coreaudio_owner *owner) { return owner ? owner->device : 0; }

ae_coreaudio_owner *ae_coreaudio_acquire(const char *uid, const ae_pcm_format *pcm, char *error, size_t error_len) {
    ae_coreaudio_owner *owner = calloc(1, sizeof *owner);
    const char *reason = "Core Audio exclusive output could not be configured";
    if (!owner) { snprintf(error, error_len, "out of memory"); return NULL; }
    AudioObjectPropertyAddress address;
    UInt32 size;
    if (uid && uid[0]) {
        CFStringRef value = CFStringCreateWithCString(kCFAllocatorDefault, uid, kCFStringEncodingUTF8);
        if (!value) goto refused;
        AudioValueTranslation translation = {(void *)&value, sizeof value, &owner->device, sizeof owner->device};
        address = property(kAudioHardwarePropertyDeviceForUID, kAudioObjectPropertyScopeGlobal);
        size = sizeof translation;
        OSStatus result = AudioObjectGetPropertyData(kAudioObjectSystemObject, &address, 0, NULL, &size, &translation);
        CFRelease(value);
        if (result != noErr || owner->device == kAudioObjectUnknown) goto refused;
    } else {
        address = property(kAudioHardwarePropertyDefaultOutputDevice, kAudioObjectPropertyScopeGlobal);
        size = sizeof owner->device;
        if (AudioObjectGetPropertyData(kAudioObjectSystemObject, &address, 0, NULL, &size, &owner->device) != noErr ||
            owner->device == kAudioObjectUnknown) goto refused;
    }
    address = property(kAudioDevicePropertyHogMode, kAudioObjectPropertyScopeGlobal);
    size = sizeof owner->previous_owner;
    if (AudioObjectGetPropertyData(owner->device, &address, 0, NULL, &size, &owner->previous_owner) != noErr) goto refused;
    if (owner->previous_owner != -1 && owner->previous_owner != getpid()) {
        reason = "Core Audio device is busy (hogged by another process)"; goto refused;
    }
    pid_t pid = getpid(), actual_pid = -1;
    if (AudioObjectSetPropertyData(owner->device, &address, 0, NULL, sizeof pid, &pid) != noErr) goto refused;
    owner->acquired = 1;
    size = sizeof actual_pid;
    if (AudioObjectGetPropertyData(owner->device, &address, 0, NULL, &size, &actual_pid) != noErr || actual_pid != pid) goto refused;
    address = property(kAudioDevicePropertyNominalSampleRate, kAudioObjectPropertyScopeGlobal);
    size = sizeof owner->previous_rate;
    if (AudioObjectGetPropertyData(owner->device, &address, 0, NULL, &size, &owner->previous_rate) != noErr) goto refused;
    Float64 requested_rate = pcm->rate, actual_rate = 0;
    owner->rate_changed = fabs(owner->previous_rate - requested_rate) > 0.001;
    if (owner->rate_changed && AudioObjectSetPropertyData(owner->device, &address, 0, NULL, sizeof requested_rate, &requested_rate) != noErr) {
        reason = "Core Audio device refused the track sample rate"; goto refused;
    }
    size = sizeof actual_rate;
    if (AudioObjectGetPropertyData(owner->device, &address, 0, NULL, &size, &actual_rate) != noErr || fabs(actual_rate - requested_rate) > 0.001) {
        reason = "Core Audio device did not latch the track sample rate"; goto refused;
    }
    /* Choose a physical integer/float PCM format, rather than infer the DAC format from an AudioUnit client format. */
    AudioStreamID streams[32];
    address = property(kAudioDevicePropertyStreams, kAudioObjectPropertyScopeOutput);
    size = sizeof streams;
    if (AudioObjectGetPropertyData(owner->device, &address, 0, NULL, &size, streams) != noErr || size < sizeof streams[0]) goto refused;
    owner->stream = streams[0];
    address = property(kAudioStreamPropertyPhysicalFormat, kAudioObjectPropertyScopeGlobal);
    size = sizeof owner->previous_format;
    if (AudioObjectGetPropertyData(owner->stream, &address, 0, NULL, &size, &owner->previous_format) != noErr) goto refused;
    AudioStreamRangedDescription formats[128];
    address = property(kAudioStreamPropertyAvailablePhysicalFormats, kAudioObjectPropertyScopeGlobal);
    size = sizeof formats;
    if (AudioObjectGetPropertyData(owner->stream, &address, 0, NULL, &size, formats) != noErr) goto refused;
    int found = 0;
    for (UInt32 i = 0; i < size / sizeof formats[0]; i++) {
        AudioStreamBasicDescription format = formats[i].mFormat;
        if (!physical_pcm_matches(&format, pcm) ||
            requested_rate < formats[i].mSampleRateRange.mMinimum || requested_rate > formats[i].mSampleRateRange.mMaximum) continue;
        format.mSampleRate = requested_rate;
        address = property(kAudioStreamPropertyPhysicalFormat, kAudioObjectPropertyScopeGlobal);
        if (AudioObjectSetPropertyData(owner->stream, &address, 0, NULL, sizeof format, &format) == noErr) {
            owner->format_changed = 1;
            AudioStreamBasicDescription actual_format = {0};
            UInt32 actual_size = sizeof actual_format;
            if (AudioObjectGetPropertyData(owner->stream, &address, 0, NULL, &actual_size, &actual_format) == noErr &&
                physical_pcm_matches(&actual_format, pcm) && fabs(actual_format.mSampleRate - requested_rate) <= 0.001) {
                found = 1; break;
            }
            reason = "Core Audio device did not latch the native PCM format";
        }
    }
    if (!found) {
        if (!owner->format_changed) reason = "Core Audio device refused the native PCM format";
        goto refused;
    }
    return owner;
refused:
    snprintf(error, error_len, "%s", reason);
    ae_coreaudio_release(owner);
    return NULL;
}
#endif
#endif
