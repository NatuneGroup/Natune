/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

#include "archivetune_device.h"

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
    ae_device_pull pull;
    void *user;
};

static void device_data_callback(ma_device *device, void *output, const void *input, ma_uint32 frames) {
    ae_device *d = (ae_device *)device->pUserData;
    (void)input;
    d->pull(d->user, (float *)output, (uint32_t)frames);
}

static void set_error(char *error, size_t error_len, const char *message) {
    if (error && error_len > 0) {
        snprintf(error, error_len, "%s", message);
    }
}

ae_device *ae_device_open(uint32_t rate, int null_backend, ae_device_pull pull, void *user, char *error,
                          size_t error_len) {
    ae_device *d = calloc(1, sizeof *d);
    if (!d) {
        set_error(error, error_len, "out of memory");
        return NULL;
    }
    d->pull = pull;
    d->user = user;

    ma_device_config config = ma_device_config_init(ma_device_type_playback);
    config.playback.format = ma_format_f32;
    config.playback.channels = 2;
    config.sampleRate = rate;
    config.dataCallback = device_data_callback;
    config.pUserData = d;

    ma_result result;
    if (null_backend) {
        ma_backend backends[] = {ma_backend_null};
        result = ma_device_init_ex(backends, 1, NULL, &config, &d->device);
    } else {
        result = ma_device_init(NULL, &config, &d->device);
    }
    if (result != MA_SUCCESS) {
        char message[256];
        snprintf(message, sizeof message, "could not open the audio output: %s", ma_result_description(result));
        set_error(error, error_len, message);
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
        free(d);
        return NULL;
    }

    result = ma_device_start(&d->device);
    if (result != MA_SUCCESS) {
        char message[256];
        snprintf(message, sizeof message, "could not start the audio output: %s", ma_result_description(result));
        set_error(error, error_len, message);
        ma_device_uninit(&d->device);
        free(d);
        return NULL;
    }
    return d;
}

void ae_device_close(ae_device *d) {
    if (!d) {
        return;
    }
    ma_device_uninit(&d->device);
    free(d);
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
