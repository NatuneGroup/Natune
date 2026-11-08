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
/* Owns one manual portable decoder and its AVAudioEngine. Activates the shared session before validating the route.
 * All graph operations catch NSException. NULL returns a sanitized, NUL-terminated error, never an Apple exception. */
at_audio_output *at_audio_create(char *error, size_t error_len);
/* One bounded retry without pitch/EQ, using the same canonical non-interleaved float format and source conversion. */
at_audio_output *at_audio_create_plain(char *error, size_t error_len);
void at_audio_destroy(at_audio_output *output);
uint64_t at_audio_load(at_audio_output *output, const char *source, uint64_t start_us, int play);
/* Header-bearing progressive audio: the engine reads the host lease. It copies io, and it closes the lease exactly once. */
uint64_t at_audio_load_stream(at_audio_output *output, const ae_stream_io *io, uint64_t start_us, int play);
/* 0 when the output failed to start, 1 when it is playing, AT_AUDIO_PLAY_DEFERRED when the system still refuses an
 * interrupted session: the play intent is kept and output starts only on an approved interruption end. */
#define AT_AUDIO_PLAY_DEFERRED 2
int at_audio_play(at_audio_output *output);
void at_audio_pause(at_audio_output *output);
void at_audio_seek(at_audio_output *output, uint64_t position_us);
void at_audio_speed(at_audio_output *output, float speed);
/* Pitch shift in cents on the rendered output, clamped to the unit's +-2400 range; 0 is unshifted. Tempo stays in
 * the engine, so the unit's rate never changes. A call before the first load still applies to every later load.
 * The manual pull carries decoder PCM before this stage, so a blend applies its own pitch unit. */
void at_audio_pitch(at_audio_output *output, float cents);
/* One band of the output's in-graph equalizer, in the same order as the shared band layout the host passes. */
typedef enum at_eq_filter {
    AT_EQ_PEAKING = 0,
    AT_EQ_LOW_SHELF = 1,
    AT_EQ_HIGH_SHELF = 2,
    AT_EQ_HIGH_PASS = 3,
} at_eq_filter;
typedef struct at_eq_band {
    uint32_t type;           /* One at_eq_filter value; unknown values are treated as peaking. */
    float frequency_hz;      /* Center or corner frequency, clamped into the rendered range. */
    float bandwidth_octaves; /* Filter width in octaves, clamped to a safe range. */
    float gain_db;           /* Boost or cut in decibels, clamped to the unit's range. */
} at_eq_band;
#define AT_AUDIO_EQ_MAX_BANDS 24
/* Applies up to AT_AUDIO_EQ_MAX_BANDS bands; the rest stay bypassed. enabled == 0 bypasses every band while keeping
 * the parameters, so a later enable is instant. A level that arrives before the first load still applies to every
 * later load. The manual pull carries decoder PCM before this stage, so a blend applies its own equalizer. */
void at_audio_equalizer(at_audio_output *output, const at_eq_band *bands, uint32_t count, int enabled);
/* The output's preamp on the in-graph equalizer's global gain, in decibels, clamped to +-24; 0 is unity. It is the
 * same unit the bands run on, so a plain fallback graph drops it with the tone stage. A level that arrives before
 * the first load still applies to every later load. A blend applies its own preamp to each deck for the same reason
 * as the equalizer. */
void at_audio_preamp(at_audio_output *output, float gain_db);
/* The app's output level, 0 to 1, applied by the engine's mixer node on top of decoder output. It scales what the
 * listener hears, like the desktop port's volume, while the hardware buttons keep owning the session route volume.
 * A level that arrives before the first load still applies to every later load. Values outside 0..1 are clamped. */
void at_audio_volume(at_audio_output *output, float volume);
void at_audio_stop(at_audio_output *output);
void at_audio_snapshot(at_audio_output *output, ae_snapshot *snapshot);
/* Main-thread handoff to an AVAudioPlayerNode pump. The decoder, lease and shared session claim stay on this
 * output. Begin drains the source-node callback before any manual pull; end requires the player node stopped.
 * Pull is nonwaiting and returns only decoded frames. Normal playback never enters this mode. */
int at_audio_begin_manual_output(at_audio_output *output);
uint32_t at_audio_pull_manual_output(at_audio_output *output, float *samples, uint32_t frames);
void at_audio_end_manual_output(at_audio_output *output);
void at_audio_manual_output_failed(at_audio_output *output);
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
