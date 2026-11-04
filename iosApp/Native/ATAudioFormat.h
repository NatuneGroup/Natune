#ifndef AT_AUDIO_FORMAT_H
#define AT_AUDIO_FORMAT_H

#include <math.h>
#include <stdint.h>

#define AT_AUDIO_PCM_RATE 48000u
#define AT_AUDIO_RENDER_CHUNK 1024u

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
