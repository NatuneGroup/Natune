/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

#include "archivetune_device.h"
#include "archivetune_coreaudio_exclusive.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#if defined(__APPLE__)
#include <TargetConditionals.h>
#endif

/* Decoding and encoding are unused here, and the resource manager and engine stay out of the build.
   Core Audio is the only real Apple backend. On iOS it needs AVFoundation's Objective-C headers, which
   this C translation unit cannot carry, so the mobile build drops it and opens the portable core's own
   output. macOS keeps it: there it is plain C, and without it ma_device_init(NULL, ...) silently falls
   through to the null backend and the whole app renders to nowhere. */
#define MA_NO_DECODING
#define MA_NO_ENCODING
#define MA_NO_GENERATION
#define MA_NO_RESOURCE_MANAGER
#define MA_NO_ENGINE
#if defined(__APPLE__) && TARGET_OS_IPHONE
#define MA_NO_COREAUDIO
#endif
#define MINIAUDIO_IMPLEMENTATION
#include "miniaudio.h"

struct ae_device {
    ma_device device;
    ma_context context;
    int owns_context;
    ae_device_pull pull;
    ae_device_raw_pull raw_pull;
    void *user;
    uint64_t channel_mask;
#if defined(__APPLE__) && !TARGET_OS_IPHONE
    ae_coreaudio_owner *hog;
#endif
};

static void device_data_callback(ma_device *device, void *output, const void *input, ma_uint32 frames) {
    ae_device *d = (ae_device *)device->pUserData;
    (void)input;
    if (d->raw_pull) d->raw_pull(d->user, output, (uint32_t)frames);
    else d->pull(d->user, (float *)output, (uint32_t)frames);
}

static void set_error(char *error, size_t error_len, const char *message) {
    if (error && error_len > 0) {
        snprintf(error, error_len, "%s", message);
    }
}

static ma_backend platform_backend(void) {
#if defined(_WIN32)
    return ma_backend_wasapi;
#elif defined(__APPLE__) && !TARGET_OS_IPHONE
    return ma_backend_coreaudio;
#else
    return ma_backend_alsa;
#endif
}

static void encode_id(ma_backend backend, const ma_device_id *id, char *out, size_t size) {
    if (backend == ma_backend_wasapi) {
        size_t position = 7;
        snprintf(out, size, "wasapi:");
        for (size_t i = 0; i < 64 && id->wasapi[i] && position + 4 < size; i++, position += 4)
            snprintf(out + position, size - position, "%04x", (unsigned)id->wasapi[i]);
    } else if (backend == ma_backend_coreaudio) snprintf(out, size, "coreaudio:%s", id->coreaudio);
    else if (backend == ma_backend_alsa) snprintf(out, size, "alsa:%s", id->alsa);
}

static int decode_id(ma_backend backend, const char *text, ma_device_id *id) {
    memset(id, 0, sizeof *id);
    if (backend == ma_backend_wasapi && strncmp(text, "wasapi:", 7) == 0) {
        const char *value = text + 7;
        size_t length = strlen(value);
        if (!length || length % 4 || length / 4 >= 64) return 0;
        for (size_t i = 0; i < length; i += 4) {
            unsigned unit = 0;
            for (size_t j = 0; j < 4; j++) {
                char c = value[i + j];
                int digit = c >= '0' && c <= '9' ? c - '0' : c >= 'a' && c <= 'f' ? c - 'a' + 10 : -1;
                if (digit < 0) return 0;
                unit = unit * 16u + (unsigned)digit;
            }
            if (unit == 0) return 0;
            id->wasapi[i / 4] = (ma_wchar_win32)unit;
        }
        return 1;
    }
    if (backend == ma_backend_alsa && strncmp(text, "alsa:", 5) == 0 && strlen(text + 5) < sizeof id->alsa && text[5]) {
        snprintf(id->alsa, sizeof id->alsa, "%s", text + 5); return 1;
    }
    if (backend == ma_backend_coreaudio && strncmp(text, "coreaudio:", 10) == 0 &&
        strlen(text + 10) < sizeof id->coreaudio && text[10]) {
        snprintf(id->coreaudio, sizeof id->coreaudio, "%s", text + 10); return 1;
    }
    return 0;
}

uint32_t ae_device_list(ae_device_description *out, uint32_t capacity, char *error, size_t error_len) {
    ma_context context;
    ma_backend backend = platform_backend();
    ma_result result = ma_context_init(&backend, 1, NULL, &context);
    if (result != MA_SUCCESS) { set_error(error, error_len, ma_result_description(result)); return 0; }
    ma_device_info *devices = NULL;
    ma_uint32 count = 0;
    result = ma_context_get_devices(&context, &devices, &count, NULL, NULL);
    uint32_t written = 0;
    if (result == MA_SUCCESS && out) {
        for (ma_uint32 i = 0; i < count && written < capacity; i++) {
            encode_id(backend, &devices[i].id, out[written].id, sizeof out[written].id);
            snprintf(out[written].name, sizeof out[written].name, "%s", devices[i].name);
            written++;
        }
    } else set_error(error, error_len, ma_result_description(result));
    ma_context_uninit(&context);
    return written;
}

static void release_platform(ae_device *d) {
#if defined(__APPLE__) && !TARGET_OS_IPHONE
    ae_coreaudio_release(d->hog);
    d->hog = NULL;
#endif
    if (d->owns_context) { ma_context_uninit(&d->context); d->owns_context = 0; }
}

static void pcm_channel_map(const ae_pcm_format *pcm, ma_channel *map) {
    static const ma_channel speakers[] = {
        MA_CHANNEL_FRONT_LEFT, MA_CHANNEL_FRONT_RIGHT, MA_CHANNEL_FRONT_CENTER, MA_CHANNEL_LFE,
        MA_CHANNEL_BACK_LEFT, MA_CHANNEL_BACK_RIGHT, MA_CHANNEL_FRONT_LEFT_CENTER, MA_CHANNEL_FRONT_RIGHT_CENTER,
        MA_CHANNEL_BACK_CENTER, MA_CHANNEL_SIDE_LEFT, MA_CHANNEL_SIDE_RIGHT, MA_CHANNEL_TOP_CENTER,
        MA_CHANNEL_TOP_FRONT_LEFT, MA_CHANNEL_TOP_FRONT_CENTER, MA_CHANNEL_TOP_FRONT_RIGHT,
        MA_CHANNEL_TOP_BACK_LEFT, MA_CHANNEL_TOP_BACK_CENTER, MA_CHANNEL_TOP_BACK_RIGHT
    };
    if (pcm->channels == 1 && pcm->channel_mask == 4) { map[0] = MA_CHANNEL_MONO; return; }
    uint32_t channel = 0;
    for (uint32_t bit = 0; bit < 18; bit++) if (pcm->channel_mask & (UINT64_C(1) << bit)) map[channel++] = speakers[bit];
}

static ae_device *open_device(const ae_device_request *request, ae_device_pull pull, ae_device_raw_pull raw_pull,
                             void *user, char *error, size_t error_len) {
    uint32_t rate = request->pcm.rate;
    int null_backend = request->null_backend;
    ae_device *d = calloc(1, sizeof *d);
    if (!d) {
        set_error(error, error_len, "out of memory");
        return NULL;
    }
    d->pull = pull;
    d->raw_pull = raw_pull;
    d->user = user;

    ma_device_config config = ma_device_config_init(ma_device_type_playback);
    config.playback.format = (ma_format)request->pcm.format;
    config.playback.channels = request->pcm.channels;
    config.sampleRate = rate;
    config.dataCallback = device_data_callback;
    config.pUserData = d;
    config.noClip = MA_TRUE;
    ma_channel channel_map[8];
    if (request->exclusive) {
        pcm_channel_map(&request->pcm, channel_map);
        config.playback.pChannelMap = channel_map;
        d->channel_mask = request->pcm.channel_mask;
    }

    ma_backend backend = platform_backend();
    ma_device_id selected;
    int selected_id = request->device_id && request->device_id[0];
    if (request->exclusive || selected_id) {
        if (null_backend) { set_error(error, error_len, "exclusive output requires a real device"); free(d); return NULL; }
        if (selected_id && !decode_id(backend, request->device_id, &selected)) {
            set_error(error, error_len, "the selected output device is invalid for this platform"); free(d); return NULL;
        }
        ma_result context_result = ma_context_init(&backend, 1, NULL, &d->context);
        if (context_result != MA_SUCCESS) { set_error(error, error_len, ma_result_description(context_result)); free(d); return NULL; }
        d->owns_context = 1;
        if (request->exclusive && backend == ma_backend_alsa) {
            if (!selected_id) {
                ma_device_info *devices = NULL;
                ma_uint32 count = 0;
                if (ma_context_get_devices(&d->context, &devices, &count, NULL, NULL) == MA_SUCCESS) {
                    for (ma_uint32 i = 0; i < count; i++) if (strncmp(devices[i].id.alsa, "hw:", 3) == 0) {
                        selected = devices[i].id; selected_id = 1; break;
                    }
                }
            }
            if (!selected_id || strncmp(selected.alsa, "hw:", 3) != 0) {
                set_error(error, error_len, "ALSA exclusive output requires a direct hw: device; select a hardware output");
                release_platform(d); free(d); return NULL;
            }
            config.alsa.noAutoFormat = MA_TRUE;
            config.alsa.noAutoChannels = MA_TRUE;
            config.alsa.noAutoResample = MA_TRUE;
        }
        if (selected_id) config.playback.pDeviceID = &selected;
        config.playback.shareMode = request->exclusive ? ma_share_mode_exclusive : ma_share_mode_shared;
#if defined(__APPLE__) && !TARGET_OS_IPHONE
        if (request->exclusive) {
            d->hog = ae_coreaudio_acquire(selected_id ? selected.coreaudio : NULL, &request->pcm, error, error_len);
            if (!d->hog) { release_platform(d); free(d); return NULL; }
            /* The helper owns exclusion; miniaudio explicitly refuses a Core Audio exclusive shareMode. */
            config.playback.shareMode = ma_share_mode_shared;
            config.coreaudio.allowNominalSampleRateChange = MA_FALSE;
        }
#endif
    }

    ma_result result;
    if (null_backend) {
        ma_backend backends[] = {ma_backend_null};
        result = ma_device_init_ex(backends, 1, NULL, &config, &d->device);
    } else {
        result = ma_device_init(d->owns_context ? &d->context : NULL, &config, &d->device);
    }
    if (result != MA_SUCCESS) {
        char message[256];
        snprintf(message, sizeof message, "could not open the audio output: %s", ma_result_description(result));
        set_error(error, error_len, message);
        release_platform(d);
        free(d);
        return NULL;
    }

    /* The null backend opens without hardware, delivers silence and reports success on init and start alike. A
       device that fell through to it is a broken output, not a working one, so it is refused instead of started.
       The message names what a user can act on: the host has no reachable sound backend, so the application cannot
       make a sound until one exists. miniaudio on Linux reaches ALSA by dlopen-ing libasound.so.2, so a container,
       a sandbox, a VM without a passed-through device or a session with no ALSA bridge lands here. */
    if (!null_backend && d->device.pContext && d->device.pContext->backend == ma_backend_null) {
        set_error(error, error_len,
                  "no audio output backend is available: the silent null backend was refused. This host exposes no "
                  "usable sound device to the application (on Linux that usually means libasound.so.2 is missing or "
                  "no PipeWire/PulseAudio device is published; in a container or virtual machine it usually means no "
                  "sound device was passed through).");
        ma_device_uninit(&d->device);
        release_platform(d);
        free(d);
        return NULL;
    }

    if (request->exclusive && (d->device.playback.internalFormat != config.playback.format ||
        d->device.playback.internalChannels != config.playback.channels || d->device.playback.internalSampleRate != rate ||
        !ma_channel_map_is_equal(channel_map, d->device.playback.internalChannelMap, config.playback.channels))) {
        set_error(error, error_len, "the exclusive device negotiated a different PCM rate, format or speaker layout");
        ma_device_uninit(&d->device); release_platform(d); free(d); return NULL;
    }
#if defined(__APPLE__) && !TARGET_OS_IPHONE
    if (request->exclusive && d->device.coreaudio.deviceObjectIDPlayback != ae_coreaudio_device_id(d->hog)) {
        set_error(error, error_len, "the Core Audio output route changed during hog-mode acquisition");
        ma_device_uninit(&d->device); release_platform(d); free(d); return NULL;
    }
#endif

    result = ma_device_start(&d->device);
    if (result != MA_SUCCESS) {
        char message[256];
        snprintf(message, sizeof message, "could not start the audio output: %s", ma_result_description(result));
        set_error(error, error_len, message);
        ma_device_uninit(&d->device);
        release_platform(d);
        free(d);
        return NULL;
    }
    return d;
}

ae_device *ae_device_open(uint32_t rate, int null_backend, ae_device_pull pull, void *user, char *error, size_t error_len) {
    ae_device_request request = {{rate, 2, AE_PCM_F32, 32, 3}, 0, null_backend, NULL};
    return open_device(&request, pull, NULL, user, error, error_len);
}

ae_device *ae_device_open_pcm(const ae_device_request *request, ae_device_raw_pull pull, void *user,
                             char *error, size_t error_len) {
    if (!request || !pull || request->pcm.rate < 8000 || request->pcm.rate > 384000 ||
        request->pcm.channels < 1 || request->pcm.channels > 8 || request->pcm.format < AE_PCM_U8 || request->pcm.format > AE_PCM_F32) {
        set_error(error, error_len, "unsupported native PCM output configuration"); return NULL;
    }
    if (request->exclusive && !ae_pcm_channel_mask_valid(&request->pcm)) {
        set_error(error, error_len, "exclusive output requires a known native PCM speaker layout"); return NULL;
    }
    return open_device(request, NULL, pull, user, error, error_len);
}

void ae_device_state_read(ae_device *d, ae_device_state *state) {
    memset(state, 0, sizeof *state);
    if (!d) return;
    state->pcm = (ae_pcm_format){d->device.playback.internalSampleRate, d->device.playback.internalChannels,
        d->device.playback.internalFormat, ma_get_bytes_per_sample(d->device.playback.internalFormat) * 8u, d->channel_mask};
    state->exclusive = d->device.playback.shareMode == ma_share_mode_exclusive;
#if defined(__APPLE__) && !TARGET_OS_IPHONE
    if (d->hog) state->exclusive = 1;
#endif
    snprintf(state->name, sizeof state->name, "%s", d->device.playback.name);
    snprintf(state->backend, sizeof state->backend, "%s", ma_get_backend_name(d->device.pContext->backend));
}

void ae_device_close(ae_device *d) {
    if (!d) {
        return;
    }
    ma_device_uninit(&d->device);
    release_platform(d);
    free(d);
}

uint32_t ae_device_buffer_frames(ae_device *d) {
    if (!d) return 0;
    uint64_t frames = (uint64_t)d->device.playback.internalPeriodSizeInFrames * d->device.playback.internalPeriods;
#if defined(_WIN32)
    if (d->device.pContext->backend == ma_backend_wasapi && d->device.wasapi.actualBufferSizeInFramesPlayback > frames)
        frames = d->device.wasapi.actualBufferSizeInFramesPlayback;
#endif
    /* Missing backend period metadata gets a conservative 200 ms tail, rather than immediate uninit. */
    if (!frames) frames = d->device.playback.internalSampleRate / 5u;
    return frames > UINT32_MAX ? UINT32_MAX : (uint32_t)frames;
}

/* A probe renders silence; it only asks the platform whether a device can be opened and started at all. */
static void silence_pull(void *user, float *out, uint32_t frames) {
    (void)user;
    memset(out, 0, (size_t)frames * 2u * sizeof(float));
}

int ae_device_probe(uint32_t rate, char *error, size_t error_len) {
    ae_device *d = ae_device_open(rate, 0, silence_pull, NULL, error, error_len);
    if (!d) {
        return -1;
    }
    ae_device_close(d);
    return 0;
}
