/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

/*
 * Private miniaudio wrapper used by the playback engine. The only translation unit that includes
 * miniaudio's implementation is archivetune_device.c.
 */
#ifndef ARCHIVETUNE_DEVICE_H
#define ARCHIVETUNE_DEVICE_H

#include <stddef.h>
#include <stdint.h>
#include "archivetune_output.h"

typedef struct ae_device ae_device;

typedef struct ae_device_request {
    ae_pcm_format pcm;
    int exclusive;
    int null_backend;
    const char *device_id; /* backend-prefixed identifier returned by ae_device_list; NULL selects the default. */
} ae_device_request;
typedef void (*ae_device_raw_pull)(void *user, void *out, uint32_t frames);

/* Exact PCM open. Exclusive requests never silently fall back or retain a converted device. */
ae_device *ae_device_open_pcm(const ae_device_request *request, ae_device_raw_pull pull, void *user,
                             char *error, size_t error_len);
void ae_device_state_read(ae_device *device, ae_device_state *state);
uint32_t ae_device_list(ae_device_description *out, uint32_t capacity, char *error, size_t error_len);

/* Called on the audio thread. It must fill exactly frames interleaved stereo float frames. */
typedef void (*ae_device_pull)(void *user, float *out, uint32_t frames);

/*
 * Opens and starts a stereo float playback device at rate. null_backend selects miniaudio's silent
 * backend, which runs on a clock and needs no audio hardware. Returns NULL with a message in error.
 */
ae_device *ae_device_open(uint32_t rate, int null_backend, ae_device_pull pull, void *user, char *error,
                          size_t error_len);
void ae_device_close(ae_device *device);

/*
 * Opens and immediately closes a real output device at rate, rendering nothing. This is the startup self-check: it
 * answers whether the host can make a sound at all before the first play, and writes the same refusal
 * ae_device_open would give into error. Returns 0 when a device opened and started, -1 otherwise.
 */
int ae_device_probe(uint32_t rate, char *error, size_t error_len);

#endif /* ARCHIVETUNE_DEVICE_H */
