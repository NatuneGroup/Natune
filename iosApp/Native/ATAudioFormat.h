#ifndef AT_AUDIO_FORMAT_H
#define AT_AUDIO_FORMAT_H

#include <math.h>
#include <stdint.h>

#define AT_AUDIO_PCM_RATE 48000u
#define AT_AUDIO_RENDER_CHUNK 1024u

/* Requests are opt-in and USB-only. The accepted session rate, not the request, configures the decoder.
 * The retained core supports at most 192 kHz; a higher device rate can convert from that full-rate PCM. */
static inline int at_audio_usb_request_valid(uint32_t rate) {
    return rate == 0 || rate == 48000 || rate == 88200 || rate == 96000 || rate == 176400 || rate == 192000;
}
static inline uint32_t at_audio_usb_pcm_rate(uint32_t requested, int usb, double accepted) {
    if (!at_audio_usb_request_valid(requested) || !requested || !usb ||
        !isfinite(accepted) || accepted < 8000.0 || accepted > 768000.0) return AT_AUDIO_PCM_RATE;
    return (uint32_t)llround(fmin(accepted, 192000.0));
}

typedef struct at_audio_format {
    double sample_rate;
    uint32_t channels;
} at_audio_format;

/* Hardware can expose a multichannel layout, but the source and tone units always use standard mono/stereo PCM.
 * A missing route is an error, not a reason to guess a rate from the asset or an inactive session. */
static inline int at_audio_canonical_format(double sample_rate, uint32_t channels,
                                           uint32_t layout_channels, at_audio_format *format) {
    if (!format || !isfinite(sample_rate) || sample_rate <= 0.0 || channels == 0 ||
        (channels > 2 && layout_channels != channels)) return 0;
    format->sample_rate = sample_rate;
    format->channels = channels > 2 ? 2 : channels;
    return 1;
}

/* The portable ABI is interleaved stereo. Audio Units require planar float PCM; conversion never allocates. */
static inline void at_audio_deinterleave(const float *source, float *left, float *right, uint32_t frames) {
    for (uint32_t frame = 0; frame < frames; ++frame) {
        left[frame] = source[frame * 2];
        right[frame] = source[frame * 2 + 1];
    }
}

#endif
