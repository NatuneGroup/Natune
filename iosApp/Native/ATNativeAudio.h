/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
#ifndef AT_NATIVE_AUDIO_H
#define AT_NATIVE_AUDIO_H
#include "archivetune_engine.h"
#ifdef __cplusplus
extern "C" {
#endif
typedef struct at_audio_output at_audio_output;
/* Owns a manual portable engine and its AVAudioSourceNode. Never opens a second audio device. */
at_audio_output *at_audio_create(char *error, size_t error_len);
void at_audio_destroy(at_audio_output *output);
uint64_t at_audio_load(at_audio_output *output, const char *source, uint64_t start_us, int play);
/* Header-bearing progressive audio: the engine reads the host lease. It copies io, and it closes the lease exactly once. */
uint64_t at_audio_load_stream(at_audio_output *output, const ae_stream_io *io, uint64_t start_us, int play);
int at_audio_play(at_audio_output *output);
void at_audio_pause(at_audio_output *output);
void at_audio_seek(at_audio_output *output, uint64_t position_us);
void at_audio_speed(at_audio_output *output, float speed);
/* The app's output level, 0 to 1, applied by the engine's mixer node on top of decoder output. It scales what the
 * listener hears, like the desktop port's volume, while the hardware buttons keep owning the session route volume.
 * A level that arrives before the first load still applies to every later load. Values outside 0..1 are clamped. */
void at_audio_volume(at_audio_output *output, float volume);
void at_audio_stop(at_audio_output *output);
void at_audio_snapshot(at_audio_output *output, ae_snapshot *snapshot);
/* Main-thread OS estimate in seconds: max(session output latency, output-node presentation latency), since these
 * overlap rather than form additive stages. Negative means unavailable. Never read from the realtime callback. */
double at_audio_output_latency(at_audio_output *output);
size_t at_audio_last_error(at_audio_output *output, char *buffer, size_t length);
/* Shared session policy for one microphone recording window. Playback outputs and recording windows coordinate the one
 * AVAudioSession here, never inside the render callback. An open window keeps play-and-record, and playback started in
 * it keeps that category instead of forcing Playback. The last release restores the category for the current playback
 * intent, or the one from before the first window. Returns 0 when the window is claimed, 1 when the category is refused
 * and 2 when activation is refused. A claim that returned 0 must be released exactly once. A window never deactivates
 * the session. */
int at_audio_session_begin_recording(void);
void at_audio_session_end_recording(void);
#ifdef __cplusplus
}
#endif
#endif
