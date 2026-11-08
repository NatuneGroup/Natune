#ifndef ARCHIVETUNE_OUTPUT_H
#define ARCHIVETUNE_OUTPUT_H
#include <stdint.h>
enum ae_pcm_type { AE_PCM_UNKNOWN = 0, AE_PCM_U8 = 1, AE_PCM_S16 = 2, AE_PCM_S24 = 3, AE_PCM_S32 = 4, AE_PCM_F32 = 5 };
/* FFmpeg native/WAVE speaker mask, in ascending bit order. Zero means unknown/custom order. */
typedef struct ae_pcm_format { uint32_t rate, channels, format, bits; uint64_t channel_mask; } ae_pcm_format;
static inline int ae_pcm_channel_mask_valid(const ae_pcm_format *pcm) {
    if (!pcm->channel_mask || (pcm->channel_mask >> 18) || pcm->channels < 1 || pcm->channels > 8) return 0;
    uint64_t mask = pcm->channel_mask;
    uint32_t count = 0;
    while (mask) { count++; mask &= mask - 1u; }
    return count == pcm->channels;
}
typedef struct ae_device_description { char id[512], name[256]; } ae_device_description;
typedef struct ae_device_state { ae_pcm_format pcm; int exclusive; char name[256], backend[32]; } ae_device_state;
#endif
