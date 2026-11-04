#import "ATAudioGraph.h"
#import "ATAudioException.h"
#import "ATAudioFormat.h"
#include <algorithm>
#include <cmath>

static id graph_new(id (^create)(void)) {
    __block id result = nil;
    if (!audio_try(^BOOL { result = create(); return result != nil; })) return nil;
    return result;
}
AVAudioEngine *at_graph_create(void) { return graph_new(^id { return [[AVAudioEngine alloc] init]; }); }
AVAudioPlayerNode *at_graph_player_create(void) { return graph_new(^id { return [[AVAudioPlayerNode alloc] init]; }); }
AVAudioMixerNode *at_graph_mixer_create(void) { return graph_new(^id { return [[AVAudioMixerNode alloc] init]; }); }
AVAudioUnitTimePitch *at_graph_pitch_create(void) { return graph_new(^id { return [[AVAudioUnitTimePitch alloc] init]; }); }
AVAudioUnitEQ *at_graph_eq_create(uint32_t bands) {
    if (bands > 16) return nil;
    return graph_new(^id { return [[AVAudioUnitEQ alloc] initWithNumberOfBands:bands]; });
}
AVAudioFormat *at_graph_pcm_format(double rate, uint32_t channels) {
    at_audio_format validated = {};
    if (!at_audio_canonical_format(rate, channels, 0, &validated)) return nil;
    return graph_new(^id { return [[AVAudioFormat alloc] initStandardFormatWithSampleRate:rate channels:channels]; });
}
AVAudioFormat *at_graph_output_format(AVAudioEngine *graph) {
    return graph_new(^id {
        AVAudioFormat *hardware = [graph.outputNode outputFormatForBus:0];
        at_audio_format target = {};
        const uint32_t layout_channels = hardware.channelLayout ? hardware.channelLayout.channelCount : 0;
        if (!at_audio_canonical_format(hardware.sampleRate, hardware.channelCount, layout_channels, &target)) return nil;
        return [[AVAudioFormat alloc] initStandardFormatWithSampleRate:target.sample_rate channels:target.channels];
    });
}
AVAudioMixerNode *at_graph_main_mixer(AVAudioEngine *graph) {
    return graph_new(^id { return graph.mainMixerNode; });
}
double at_graph_latency(AVAudioEngine *graph) {
    __block double latency = -1.0;
    audio_try(^BOOL {
        const double session = [AVAudioSession sharedInstance].outputLatency;
        const double presentation = graph.outputNode.presentationLatency;
        if (std::isfinite(session) && session >= 0.0) latency = session;
        if (std::isfinite(presentation) && presentation >= 0.0) latency = std::max(latency, presentation);
        return YES;
    });
    return latency;
}
AVAudioPCMBuffer *at_graph_buffer_create(AVAudioFormat *format, uint32_t frames) {
    if (!frames) return nil;
    return graph_new(^id { return [[AVAudioPCMBuffer alloc] initWithPCMFormat:format frameCapacity:frames]; });
}
int at_graph_attach_node(AVAudioEngine *graph, AVAudioNode *node) {
    return audio_try(^BOOL { [graph attachNode:node]; return YES; });
}
int at_graph_connect(AVAudioEngine *graph, AVAudioNode *from, AVAudioNode *to, AVAudioFormat *format) {
    return audio_try(^BOOL {
        if (!format || !std::isfinite(format.sampleRate) || format.sampleRate <= 0.0 ||
            format.channelCount < 1 || format.channelCount > 2 || format.isInterleaved ||
            format.commonFormat != AVAudioPCMFormatFloat32) return NO;
        [graph connect:from to:to format:format];
        return YES;
    });
}
int at_graph_detach_node(AVAudioEngine *graph, AVAudioNode *node) {
    return audio_try(^BOOL { [graph detachNode:node]; return YES; });
}
int at_graph_prepare(AVAudioEngine *graph) { return audio_try(^BOOL { [graph prepare]; return YES; }); }
int at_graph_start(AVAudioEngine *graph) {
    return audio_try(^BOOL { NSError *error = nil; return [graph isRunning] || [graph startAndReturnError:&error]; });
}
int at_graph_pause(AVAudioEngine *graph) { return audio_try(^BOOL { [graph pause]; return YES; }); }
int at_graph_stop(AVAudioEngine *graph) { return audio_try(^BOOL { [graph stop]; return YES; }); }
static float bounded(float value, float low, float high) { return !(value >= low) ? low : std::min(value, high); }
int at_graph_volume(AVAudioMixerNode *mixer, float volume) {
    return audio_try(^BOOL { mixer.outputVolume = bounded(volume, 0.0f, 1.0f); return YES; });
}
int at_graph_pitch(AVAudioUnitTimePitch *unit, float cents) {
    return audio_try(^BOOL { unit.rate = 1.0f; unit.pitch = bounded(cents, -2400.0f, 2400.0f); return YES; });
}
int at_graph_eq_band(AVAudioUnitEQ *unit, uint32_t index, uint32_t type,
                     float frequency, float bandwidth, float gain, int enabled, double rate) {
    return audio_try(^BOOL {
        if (index >= unit.bands.count || !std::isfinite(rate) || rate <= 0.0) return NO;
        AVAudioUnitEQFilterParameters *band = unit.bands[index];
        if (!enabled) { band.bypass = YES; return YES; }
        const float high = (float)std::min(23900.0, rate * 0.49);
        band.frequency = bounded(frequency, std::min(20.0f, high), high);
        band.bandwidth = bounded(bandwidth, 0.05f, 5.0f);
        band.gain = bounded(gain, -24.0f, 24.0f);
        band.filterType = type == 1 ? AVAudioUnitEQFilterTypeLowShelf :
                          type == 2 ? AVAudioUnitEQFilterTypeHighShelf : AVAudioUnitEQFilterTypeParametric;
        band.bypass = NO;
        return YES;
    });
}
int at_graph_schedule(AVAudioPlayerNode *player, AVAudioPCMBuffer *buffer, AVAudioTime *time) {
    return audio_try(^BOOL {
        [player scheduleBuffer:buffer atTime:time options:(AVAudioPlayerNodeBufferOptions)0 completionHandler:nil];
        return YES;
    });
}
int at_graph_player_play(AVAudioPlayerNode *player, AVAudioTime *time) {
    return audio_try(^BOOL { if (time) [player playAtTime:time]; else [player play]; return YES; });
}
int at_graph_player_pause(AVAudioPlayerNode *player) { return audio_try(^BOOL { [player pause]; return YES; }); }
int at_graph_player_stop(AVAudioPlayerNode *player) { return audio_try(^BOOL { [player stop]; return YES; }); }
