#ifndef AT_AUDIO_GRAPH_H
#define AT_AUDIO_GRAPH_H
#import <AVFAudio/AVFAudio.h>
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif

/* The blend pump never calls AVAudioEngine mutations directly: Kotlin cannot catch an Objective-C NSException.
 * NULL/0 means the operation was refused (NSError or NSException); caller retains playback/error policy. */
AVAudioEngine * _Nullable at_graph_create(void);
AVAudioPlayerNode * _Nullable at_graph_player_create(void);
AVAudioMixerNode * _Nullable at_graph_mixer_create(void);
AVAudioUnitTimePitch * _Nullable at_graph_pitch_create(void);
AVAudioUnitEQ * _Nullable at_graph_eq_create(uint32_t bands);
AVAudioFormat * _Nullable at_graph_pcm_format(double rate, uint32_t channels);
AVAudioFormat * _Nullable at_graph_output_format(AVAudioEngine * _Nonnull graph);
AVAudioMixerNode * _Nullable at_graph_main_mixer(AVAudioEngine * _Nonnull graph);
double at_graph_latency(AVAudioEngine * _Nonnull graph);
AVAudioPCMBuffer * _Nullable at_graph_buffer_create(AVAudioFormat * _Nonnull format, uint32_t frames);
int at_graph_attach_node(AVAudioEngine * _Nonnull graph, AVAudioNode * _Nonnull node);
int at_graph_connect(AVAudioEngine * _Nonnull graph, AVAudioNode * _Nonnull from,
                     AVAudioNode * _Nonnull to, AVAudioFormat * _Nonnull format);
int at_graph_detach_node(AVAudioEngine * _Nonnull graph, AVAudioNode * _Nonnull node);
int at_graph_prepare(AVAudioEngine * _Nonnull graph);
int at_graph_start(AVAudioEngine * _Nonnull graph);
int at_graph_pause(AVAudioEngine * _Nonnull graph);
int at_graph_stop(AVAudioEngine * _Nonnull graph);
int at_graph_volume(AVAudioMixerNode * _Nonnull mixer, float volume);
int at_graph_pitch(AVAudioUnitTimePitch * _Nonnull unit, float cents);
int at_graph_eq_band(AVAudioUnitEQ * _Nonnull unit, uint32_t index, uint32_t type,
                     float frequency, float bandwidth, float gain, int enabled, double rate);
int at_graph_schedule(AVAudioPlayerNode * _Nonnull player, AVAudioPCMBuffer * _Nonnull buffer,
                      AVAudioTime * _Nullable time);
int at_graph_player_play(AVAudioPlayerNode * _Nonnull player, AVAudioTime * _Nullable time);
int at_graph_player_pause(AVAudioPlayerNode * _Nonnull player);
int at_graph_player_stop(AVAudioPlayerNode * _Nonnull player);

#ifdef __cplusplus
}
#endif
#endif
