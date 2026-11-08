#ifndef ARCHIVETUNE_COREAUDIO_EXCLUSIVE_H
#define ARCHIVETUNE_COREAUDIO_EXCLUSIVE_H
#include "archivetune_device.h"
typedef struct ae_coreaudio_owner ae_coreaudio_owner;
ae_coreaudio_owner *ae_coreaudio_acquire(const char *uid, const ae_pcm_format *pcm, char *error, size_t error_len);
uint32_t ae_coreaudio_device_id(const ae_coreaudio_owner *owner);
void ae_coreaudio_release(ae_coreaudio_owner *owner);
#endif
