#ifndef ARCHIVETUNE_OUTPUT_H
#define ARCHIVETUNE_OUTPUT_H
#include <stdint.h>
enum ae_pcm_type { AE_PCM_UNKNOWN = 0, AE_PCM_U8 = 1, AE_PCM_S16 = 2, AE_PCM_S24 = 3, AE_PCM_S32 = 4, AE_PCM_F32 = 5 };
typedef struct ae_pcm_format { uint32_t rate, channels, format, bits; } ae_pcm_format;
typedef struct ae_device_description { char id[512], name[256]; } ae_device_description;
typedef struct ae_device_state { ae_pcm_format pcm; int exclusive; char name[256], backend[32]; } ae_device_state;
#endif
