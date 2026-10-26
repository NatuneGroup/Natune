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

/* Decoding and encoding are unused here, and the resource manager and engine stay out of the build.
   Only the null backend is ever opened, and Core Audio is Apple's only backend: disabling it keeps
   AVFoundation's Objective-C headers out of this C translation unit on iOS and macOS. */
#define MA_NO_DECODING
#define MA_NO_ENCODING
#define MA_NO_GENERATION
#define MA_NO_RESOURCE_MANAGER
#define MA_NO_ENGINE
#define MA_NO_COREAUDIO
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
