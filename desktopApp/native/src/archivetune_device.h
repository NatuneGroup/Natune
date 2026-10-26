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

typedef struct ae_device ae_device;

/* Called on the audio thread. It must fill exactly frames interleaved stereo float frames. */
typedef void (*ae_device_pull)(void *user, float *out, uint32_t frames);

/*
 * Opens and starts a stereo float playback device at rate. null_backend selects miniaudio's silent
 * backend, which runs on a clock and needs no audio hardware. Returns NULL with a message in error.
 */
ae_device *ae_device_open(uint32_t rate, int null_backend, ae_device_pull pull, void *user, char *error,
                          size_t error_len);
void ae_device_close(ae_device *device);

#endif /* ARCHIVETUNE_DEVICE_H */
