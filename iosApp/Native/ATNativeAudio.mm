/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
#import "ATNativeAudio.h"
#import "ATAudioFormat.h"
#import "ATAudioException.h"
#import <AVFoundation/AVFoundation.h>
#import <QuartzCore/QuartzCore.h>
#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstring>
#include <memory>
#include <mutex>
#include <unistd.h>
#include <vector>

/* Uses the declared realtime ABI from the SAME portable core (M8 bee7a838).
 * A stale core/header must fail the build/link, never fall back to the waiting renderer. */

struct at_audio_output;

/* A queued callback owns this lifetime, never a raw output. Destroy clears output before freeing the decoder.
 * Gate -> session is the only lock order; notifications dispatch before taking either lock to avoid reentrancy. */
struct output_gate {
    std::mutex mutex;
    at_audio_output *output = nullptr;
};

struct at_audio_output {
    ae_engine *core = nullptr;
    std::shared_ptr<output_gate> gate;
    AVAudioEngine *__strong graph = nil;
    AVAudioSourceNode *__strong source = nil;
    AVAudioSourceNode *__weak retired_source = nil;
    AVAudioMixerNode *__strong converter = nil;
    AVAudioUnitTimePitch *__strong time_pitch = nil;
    AVAudioUnitEQ *__strong equalizer = nil;
    id __strong interruption = nil;
    id __strong route = nil;
    id __strong reset = nil;
    id __strong configuration = nil;
    std::atomic<bool> running{false};
    std::atomic<bool> wants_play{false};
    std::atomic<bool> interrupted{false};
    std::atomic<bool> consuming{false};
    std::atomic<bool> closing{false};
    std::atomic<bool> manual_output{false};
    std::atomic<int> render_active{0};
    std::atomic<double> last_render{0};
    // One serial source callback uses fixed scratch. The manual consumer starts only after that callback drains.
    float scratch[AT_AUDIO_RENDER_CHUNK * 2] = {};
    float volume = 1.0f;
    float pitch_cents = 0.0f;
    at_eq_band eq_bands[AT_AUDIO_EQ_MAX_BANDS] = {};
    uint32_t eq_count = 0;
    bool eq_enabled = false;
    float preamp_db = 0.0f;
    double graph_rate = 0.0;
    uint64_t graph_generation = 0;
    uint64_t output_error = 0;
    bool output_failed = false;
    bool requires_recreation = false;
    bool graph_dirty = true;
    bool rebuild_queued = false;
    bool plain_graph = false;
    bool playback_claimed = false; // Guarded by g_session_lock.
    char error[256] = {};
};

struct render_scope {
    explicit render_scope(at_audio_output *output) : p(output) { p->render_active.fetch_add(1); }
    ~render_scope() { p->render_active.fetch_sub(1); }
    render_scope(const render_scope &) = delete;
    render_scope &operator=(const render_scope &) = delete;
    at_audio_output *p;
};
static void require_main() { NSCAssert([NSThread isMainThread], @"Audio control requires main thread"); }

/* Recording windows and audible playback share one session. The realtime callback never takes this lock. */
static std::mutex g_session_lock;
static int g_recording_windows = 0;
static int g_playback_claims = 0;
static bool g_snapshot_taken = false;
static NSString *g_snapshot_category = nil;
static NSString *g_snapshot_mode = nil;
static AVAudioSessionCategoryOptions g_snapshot_options = (AVAudioSessionCategoryOptions)0;
static AVAudioSessionRouteSharingPolicy g_snapshot_policy = AVAudioSessionRouteSharingPolicyDefault;
static std::vector<std::shared_ptr<output_gate>> g_outputs;

static AVAudioSessionCategoryOptions recording_options(void) {
    return (AVAudioSessionCategoryOptions)(AVAudioSessionCategoryOptionDefaultToSpeaker |
                                           AVAudioSessionCategoryOptionAllowBluetooth |
                                           AVAudioSessionCategoryOptionAllowBluetoothA2DP |
                                           AVAudioSessionCategoryOptionAllowAirPlay);
}
static void claim_playback_locked(at_audio_output *p) {
    if (p->playback_claimed) return;
    p->playback_claimed = true;
    ++g_playback_claims;
}
static void release_playback_locked(at_audio_output *p) {
    if (!p->playback_claimed) return;
    p->playback_claimed = false;
    --g_playback_claims;
}
static void release_playback(at_audio_output *p) {
    std::lock_guard<std::mutex> guard(g_session_lock);
    release_playback_locked(p);
}
/* Caller holds g_session_lock. Activate BEFORE querying hardware or mutating the graph, including paused creation. */
static bool configure_playback_locked(void) {
    return audio_try(^BOOL {
        AVAudioSession *session = [AVAudioSession sharedInstance];
        NSError *error = nil;
        if (g_recording_windows == 0 &&
            ![session setCategory:AVAudioSessionCategoryPlayback mode:AVAudioSessionModeDefault
               routeSharingPolicy:AVAudioSessionRouteSharingPolicyLongFormAudio
                          options:(AVAudioSessionCategoryOptions)0 error:&error]) return NO;
        return [session setActive:YES error:&error];
    });
}
static void settle_session_locked(void) {
    audio_try(^BOOL {
        AVAudioSession *session = [AVAudioSession sharedInstance];
        NSError *error = nil;
        if (g_playback_claims > 0) {
            [session setCategory:AVAudioSessionCategoryPlayback mode:AVAudioSessionModeDefault
              routeSharingPolicy:AVAudioSessionRouteSharingPolicyLongFormAudio
                         options:(AVAudioSessionCategoryOptions)0 error:&error];
        } else if (g_snapshot_taken) {
            [session setCategory:g_snapshot_category ? g_snapshot_category : AVAudioSessionCategoryPlayback
                            mode:g_snapshot_mode ? g_snapshot_mode : AVAudioSessionModeDefault
              routeSharingPolicy:g_snapshot_policy options:g_snapshot_options error:&error];
        }
        return YES;
    });
    g_snapshot_taken = false;
    g_snapshot_category = nil;
    g_snapshot_mode = nil;
    g_snapshot_options = (AVAudioSessionCategoryOptions)0;
    g_snapshot_policy = AVAudioSessionRouteSharingPolicyDefault;
}
static void output_failure(at_audio_output *p) {
    p->running.store(false);
    p->consuming.store(false);
    if (!p->output_failed) ++p->output_error;
    p->output_failed = true;
    release_playback(p);
    strlcpy(p->error, "iOS audio session or canonical PCM graph is unavailable", sizeof(p->error));
}
static float clamped(float value, float low, float high) {
    if (!(value >= low)) return low;
    return value > high ? high : value;
}
/* Caller is inside audio_try. Stored controls survive graph replacement and the plain-graph fallback. */
static void apply_tone(at_audio_output *p) {
    p->time_pitch.rate = 1.0f; // Tempo stays in the portable core.
    p->time_pitch.pitch = p->pitch_cents;
    const float high = (float)std::min(23900.0, p->graph_rate * 0.49);
    const float low = std::min(20.0f, high);
    NSArray<AVAudioUnitEQFilterParameters *> *units = p->equalizer.bands;
    for (NSUInteger i = 0; i < units.count; ++i) {
        AVAudioUnitEQFilterParameters *unit = units[i];
        if (i >= p->eq_count) { unit.bypass = YES; continue; }
        const at_eq_band band = p->eq_bands[i];
        unit.frequency = clamped(band.frequency_hz, low, high);
        unit.bandwidth = clamped(band.bandwidth_octaves, 0.05f, 5.0f);
        unit.gain = clamped(band.gain_db, -24.0f, 24.0f);
        switch (band.type) {
            case AT_EQ_LOW_SHELF: unit.filterType = AVAudioUnitEQFilterTypeLowShelf; break;
            case AT_EQ_HIGH_SHELF: unit.filterType = AVAudioUnitEQFilterTypeHighShelf; break;
            default: unit.filterType = AVAudioUnitEQFilterTypeParametric; break;
        }
        unit.bypass = !p->eq_enabled;
    }
    // The preamp belongs to the same unit, so it survives a graph replacement with the bands. The gain is global to
    // the EQ, which is where the shared chain applies its user preamp before the band cascade.
    p->equalizer.globalGain = clamped(p->preamp_db, -24.0f, 24.0f);
    p->graph.mainMixerNode.outputVolume = p->volume;
}

/* A stopped graph can still own an in-flight callback. Do not replace it, reuse scratch, or free the decoder until
 * BOTH the callback and its source node retire. If this bound expires, retain the decoder rather than cause a UAF. */
static bool discard_graph(at_audio_output *p) {
    p->running.store(false);
    p->consuming.store(false);
    @autoreleasepool {
        if (p->configuration) {
            [[NSNotificationCenter defaultCenter] removeObserver:p->configuration];
            p->configuration = nil;
        }
        if (p->source) p->retired_source = p->source;
        audio_try(^BOOL { [p->graph stop]; return YES; });
        audio_try(^BOOL { if (p->source) [p->graph detachNode:p->source]; return YES; });
        audio_try(^BOOL { if (p->time_pitch) [p->graph detachNode:p->time_pitch]; return YES; });
        audio_try(^BOOL { if (p->equalizer) [p->graph detachNode:p->equalizer]; return YES; });
        audio_try(^BOOL { if (p->converter) [p->graph detachNode:p->converter]; return YES; });
        audio_try(^BOOL { p->source = nil; p->time_pitch = nil; p->equalizer = nil;
                          p->converter = nil; p->graph = nil; return YES; });
    }
    for (int attempt = 0; attempt < 2000; ++attempt) {
        if (p->render_active.load() == 0 && p->retired_source == nil) return true;
        usleep(1000);
    }
    p->requires_recreation = true;
    return false;
}

static bool start_output(at_audio_output *p);
static void invalidate_graph(at_audio_output *p) {
    p->graph_dirty = true;
    // A blend's separate consumer still owns the decoder. Rebuild this dormant source graph on handback, not mid-pull.
    if (!p->manual_output.load()) {
        p->running.store(false);
        p->consuming.store(false);
    }
    if (p->rebuild_queued) return;
    p->rebuild_queued = true;
    const std::shared_ptr<output_gate> gate = p->gate;
    dispatch_async(dispatch_get_main_queue(), ^{
        std::lock_guard<std::mutex> guard(gate->mutex);
        at_audio_output *output = gate->output;
        if (!output) return;
        output->rebuild_queued = false;
        if (output->graph_dirty && !output->closing.load() && !output->manual_output.load() &&
            !output->interrupted.load() && output->wants_play.load() && !output->output_failed) start_output(output);
    });
}
static void observe_configuration(at_audio_output *p) {
    const std::shared_ptr<output_gate> gate = p->gate;
    const uint64_t generation = p->graph_generation;
    p->configuration = [[NSNotificationCenter defaultCenter]
        addObserverForName:AVAudioEngineConfigurationChangeNotification object:p->graph
        queue:[NSOperationQueue mainQueue] usingBlock:^(NSNotification *) {
        dispatch_async(dispatch_get_main_queue(), ^{
            std::lock_guard<std::mutex> guard(gate->mutex);
            at_audio_output *output = gate->output;
            // A configuration notification queued by the retired graph must not invalidate its replacement.
            if (output && output->graph_generation == generation) invalidate_graph(output);
        });
    }];
}

static bool build_graph(at_audio_output *p, bool plain) {
    p->plain_graph = plain; // Even a failed plain attempt consumes this graph's single optional-unit retry.
    bool built = false;
    @autoreleasepool {
        built = audio_try(^BOOL {
            p->graph = [[AVAudioEngine alloc] init];
            AVAudioFormat *hardware = [p->graph.outputNode outputFormatForBus:0];
            at_audio_format target = {};
            const uint32_t layout_channels = hardware.channelLayout ? hardware.channelLayout.channelCount : 0;
            if (!at_audio_canonical_format(hardware.sampleRate, hardware.channelCount, layout_channels, &target)) return NO;
            AVAudioFormat *pcm = [[AVAudioFormat alloc] initStandardFormatWithSampleRate:AT_AUDIO_PCM_RATE channels:2];
            AVAudioFormat *canonical = [[AVAudioFormat alloc] initStandardFormatWithSampleRate:target.sample_rate
                                                                                     channels:target.channels];
            if (!pcm || !canonical || pcm.isInterleaved || canonical.isInterleaved ||
                canonical.commonFormat != AVAudioPCMFormatFloat32) return NO;
            p->graph_rate = target.sample_rate;
            p->source = [[AVAudioSourceNode alloc] initWithFormat:pcm
                renderBlock:^OSStatus(BOOL *silent, const AudioTimeStamp *, AVAudioFrameCount frames,
                                      AudioBufferList *buffers) {
                render_scope active(p);
                for (UInt32 i = 0; i < buffers->mNumberBuffers; ++i) {
                    if (buffers->mBuffers[i].mData)
                        memset(buffers->mBuffers[i].mData, 0, buffers->mBuffers[i].mDataByteSize);
                }
                if (p->closing.load() || p->manual_output.load() || !p->running.load() || p->interrupted.load() ||
                    buffers->mNumberBuffers != 2 || !buffers->mBuffers[0].mData || !buffers->mBuffers[1].mData ||
                    buffers->mBuffers[0].mNumberChannels != 1 || buffers->mBuffers[1].mNumberChannels != 1 ||
                    buffers->mBuffers[0].mDataByteSize < (uint64_t)frames * sizeof(float) ||
                    buffers->mBuffers[1].mDataByteSize < (uint64_t)frames * sizeof(float)) {
                    *silent = YES;
                    p->consuming.store(false);
                    return noErr;
                }
                float *left = static_cast<float *>(buffers->mBuffers[0].mData);
                float *right = static_cast<float *>(buffers->mBuffers[1].mData);
                uint32_t decoded = 0;
                for (uint32_t offset = 0; offset < frames;) {
                    const uint32_t count = std::min(AT_AUDIO_RENDER_CHUNK, frames - offset);
                    const uint32_t taken = ae_engine_render_realtime(p->core, p->scratch, count);
                    at_audio_deinterleave(p->scratch, left + offset, right + offset, count);
                    decoded += taken;
                    offset += count;
                    if (taken < count) break; // The remaining buffers were already zeroed; silence never consumes media time.
                }
                p->last_render.store(CACurrentMediaTime());
                p->consuming.store(decoded > 0);
                *silent = decoded == 0;
                return noErr;
            }];
            // Convert at the source boundary. The portable core and blend ABI stay 48 kHz stereo; the tone chain uses
            // canonical planar float at the active hardware rate. Never force an asset format onto the output node.
            p->converter = [[AVAudioMixerNode alloc] init];
            [p->graph attachNode:p->source];
            [p->graph attachNode:p->converter];
            [p->graph connect:p->source to:p->converter format:pcm];
            if (!plain) {
                p->time_pitch = [[AVAudioUnitTimePitch alloc] init];
                p->equalizer = [[AVAudioUnitEQ alloc] initWithNumberOfBands:AT_AUDIO_EQ_MAX_BANDS];
                [p->graph attachNode:p->time_pitch];
                [p->graph attachNode:p->equalizer];
                [p->graph connect:p->converter to:p->time_pitch format:canonical];
                [p->graph connect:p->time_pitch to:p->equalizer format:canonical];
                [p->graph connect:p->equalizer to:p->graph.mainMixerNode format:canonical];
            } else {
                [p->graph connect:p->converter to:p->graph.mainMixerNode format:canonical];
            }
            apply_tone(p);
            [p->graph prepare];
            return YES;
        });
    }
    if (!built) return false;
    p->graph_dirty = false;
    ++p->graph_generation;
    observe_configuration(p);
    return true;
}
static bool rebuild_graph(at_audio_output *p, bool plain) {
    if (!discard_graph(p)) return false;
    if (build_graph(p, plain)) return true;
    // Exactly one basic-graph retry, with the same validated canonical format and no optional Audio Units.
    if (plain || !discard_graph(p)) return false;
    return build_graph(p, true);
}
static bool start_graph(at_audio_output *p) {
    return audio_try(^BOOL {
        NSError *error = nil;
        return [p->graph isRunning] || [p->graph startAndReturnError:&error];
    });
}
static bool start_output(at_audio_output *p) {
    if (p->interrupted.load() || p->requires_recreation || p->closing.load()) return false;
    bool started = false;
    {
        std::lock_guard<std::mutex> guard(g_session_lock);
        claim_playback_locked(p);
        started = configure_playback_locked();
        if (started && !p->manual_output.load()) {
            if (p->graph_dirty || !p->graph) started = rebuild_graph(p, p->plain_graph);
            if (started) started = start_graph(p);
            if (!started && !p->plain_graph && !p->requires_recreation) {
                started = rebuild_graph(p, true) && start_graph(p);
            }
        }
        if (!started) release_playback_locked(p);
    }
    if (!started) { output_failure(p); return false; }
    p->running.store(true);
    p->output_failed = false;
    p->error[0] = '\0';
    return true;
}
static void resume_gate(const std::shared_ptr<output_gate> &gate) {
    std::lock_guard<std::mutex> guard(gate->mutex);
    at_audio_output *p = gate->output;
    if (!p || p->closing.load() || p->interrupted.load() || p->requires_recreation) return;
    bool claimed = false;
    {
        std::lock_guard<std::mutex> session(g_session_lock);
        claimed = p->playback_claimed;
    }
    if (claimed) invalidate_graph(p);
}
static void schedule_playback_resume_locked(void) {
    std::vector<std::shared_ptr<output_gate>> gates = g_outputs;
    dispatch_async(dispatch_get_main_queue(), ^{
        for (const std::shared_ptr<output_gate> &gate : gates) resume_gate(gate);
    });
}

static at_audio_output *create_output(char *error, size_t error_len, bool plain) {
    require_main();
    if (error && error_len) error[0] = '\0';
    auto *p = new at_audio_output();
    p->gate = std::make_shared<output_gate>();
    p->gate->output = p;
    ae_config config = {AE_ABI_VERSION, AE_OUTPUT_MANUAL, AE_BACKEND_DEFAULT, AT_AUDIO_PCM_RATE, 2000, 120};
    p->core = ae_engine_create(&config, error, error_len);
    if (!p->core) { delete p; return nullptr; }
    bool configured = false;
    {
        std::lock_guard<std::mutex> guard(g_session_lock);
        configured = configure_playback_locked() && build_graph(p, plain);
    }
    if (!configured) {
        if (error && error_len) strlcpy(error, "iOS audio output rejected the canonical PCM format", error_len);
        at_audio_destroy(p);
        return nullptr;
    }
    const std::shared_ptr<output_gate> gate = p->gate;
    NSNotificationCenter *center = [NSNotificationCenter defaultCenter];
    p->interruption = [center addObserverForName:AVAudioSessionInterruptionNotification object:nil
        queue:[NSOperationQueue mainQueue] usingBlock:^(NSNotification *note) {
        const NSUInteger type = [note.userInfo[AVAudioSessionInterruptionTypeKey] unsignedIntegerValue];
        const NSUInteger options = [note.userInfo[AVAudioSessionInterruptionOptionKey] unsignedIntegerValue];
        dispatch_async(dispatch_get_main_queue(), ^{
            std::lock_guard<std::mutex> guard(gate->mutex);
            at_audio_output *output = gate->output;
            if (!output) return;
            if (type == AVAudioSessionInterruptionTypeBegan) {
                output->interrupted.store(true);
                output->running.store(false);
                output->consuming.store(false);
                if (!audio_try(^BOOL { [output->graph pause]; return YES; })) output_failure(output);
                release_playback(output);
            } else {
                output->interrupted.store(false);
                output->graph_dirty = true;
                if (output->wants_play.load() && (options & AVAudioSessionInterruptionOptionShouldResume)) start_output(output);
            }
        });
    }];
    p->route = [center addObserverForName:AVAudioSessionRouteChangeNotification object:nil
        queue:[NSOperationQueue mainQueue] usingBlock:^(NSNotification *note) {
        const NSUInteger reason = [note.userInfo[AVAudioSessionRouteChangeReasonKey] unsignedIntegerValue];
        dispatch_async(dispatch_get_main_queue(), ^{
            std::lock_guard<std::mutex> guard(gate->mutex);
            at_audio_output *output = gate->output;
            if (!output) return;
            // Actual removal pauses. Other changes rebuild at the new rate without changing listener intent or leases.
            if (reason == AVAudioSessionRouteChangeReasonOldDeviceUnavailable) at_audio_pause(output);
            invalidate_graph(output);
        });
    }];
    p->reset = [center addObserverForName:AVAudioSessionMediaServicesWereResetNotification object:nil
        queue:[NSOperationQueue mainQueue] usingBlock:^(NSNotification *) {
        dispatch_async(dispatch_get_main_queue(), ^{
            std::lock_guard<std::mutex> guard(gate->mutex);
            at_audio_output *output = gate->output;
            if (!output) return;
            output->wants_play.store(false);
            ae_engine_pause(output->core);
            audio_try(^BOOL { [output->graph stop]; return YES; });
            output->requires_recreation = true;
            output_failure(output);
        });
    }];
    {
        std::lock_guard<std::mutex> guard(g_session_lock);
        g_outputs.push_back(gate);
    }
    return p;
}
at_audio_output *at_audio_create(char *error, size_t error_len) { return create_output(error, error_len, false); }
at_audio_output *at_audio_create_plain(char *error, size_t error_len) { return create_output(error, error_len, true); }

void at_audio_destroy(at_audio_output *p) {
    require_main();
    if (!p) return;
    {
        std::lock_guard<std::mutex> guard(p->gate->mutex);
        p->gate->output = nullptr;
    }
    NSNotificationCenter *center = [NSNotificationCenter defaultCenter];
    if (p->interruption) [center removeObserver:p->interruption];
    if (p->route) [center removeObserver:p->route];
    if (p->reset) [center removeObserver:p->reset];
    {
        std::lock_guard<std::mutex> guard(g_session_lock);
        release_playback_locked(p);
        g_outputs.erase(std::remove(g_outputs.begin(), g_outputs.end(), p->gate), g_outputs.end());
    }
    p->closing.store(true);
    const bool drained = discard_graph(p);
    {
        std::lock_guard<std::mutex> guard(g_session_lock);
        if (g_recording_windows == 0 && g_playback_claims == 0) {
            audio_try(^BOOL {
                return [[AVAudioSession sharedInstance] setActive:NO
                    withOptions:AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation error:nil];
            });
        }
    }
    if (drained) { ae_engine_destroy(p->core); delete p; }
}
uint64_t at_audio_load(at_audio_output *p, const char *source, uint64_t start, int play) {
    require_main();
    p->wants_play.store(play != 0);
    if (!play) release_playback(p);
    uint64_t generation = ae_engine_load(p->core, source, start, play);
    if (generation && play) start_output(p);
    return generation;
}
uint64_t at_audio_load_stream(at_audio_output *p, const ae_stream_io *io, uint64_t start, int play) {
    require_main();
    p->wants_play.store(play != 0);
    if (!play) release_playback(p);
    uint64_t generation = ae_engine_load_stream(p->core, io, start, play);
    if (generation && play) start_output(p);
    return generation;
}
int at_audio_play(at_audio_output *p) {
    require_main();
    p->wants_play.store(true);
    ae_engine_play(p->core);
    return start_output(p) ? 1 : 0;
}
void at_audio_pause(at_audio_output *p) {
    require_main();
    p->wants_play.store(false);
    ae_engine_pause(p->core);
    p->running.store(false);
    p->consuming.store(false);
    if (!audio_try(^BOOL { [p->graph pause]; return YES; })) output_failure(p);
    release_playback(p);
}
void at_audio_seek(at_audio_output *p, uint64_t position) { require_main(); ae_engine_seek(p->core, position); }
void at_audio_speed(at_audio_output *p, float speed) { require_main(); ae_engine_set_speed(p->core, speed); }
void at_audio_pitch(at_audio_output *p, float cents) {
    require_main();
    p->pitch_cents = clamped(cents, -2400.0f, 2400.0f);
    if (!audio_try(^BOOL { apply_tone(p); return YES; })) { p->plain_graph = true; invalidate_graph(p); }
}
void at_audio_equalizer(at_audio_output *p, const at_eq_band *bands, uint32_t count, int enabled) {
    require_main();
    p->eq_count = bands ? std::min(count, (uint32_t)AT_AUDIO_EQ_MAX_BANDS) : 0;
    if (p->eq_count) memcpy(p->eq_bands, bands, p->eq_count * sizeof(at_eq_band));
    p->eq_enabled = enabled != 0;
    if (!audio_try(^BOOL { apply_tone(p); return YES; })) { p->plain_graph = true; invalidate_graph(p); }
}
void at_audio_preamp(at_audio_output *p, float gain_db) {
    require_main();
    p->preamp_db = clamped(gain_db, -24.0f, 24.0f);
    if (!audio_try(^BOOL { apply_tone(p); return YES; })) { p->plain_graph = true; invalidate_graph(p); }
}
void at_audio_volume(at_audio_output *p, float volume) {
    require_main();
    p->volume = clamped(volume, 0.0f, 1.0f);
    if (!audio_try(^BOOL { p->graph.mainMixerNode.outputVolume = p->volume; return YES; })) output_failure(p);
}
void at_audio_stop(at_audio_output *p) { at_audio_pause(p); ae_engine_stop(p->core); }
void at_audio_snapshot(at_audio_output *p, ae_snapshot *snapshot) {
    require_main();
    ae_engine_snapshot(p->core, snapshot);
    if (!p->running.load() || !p->consuming.load() || p->interrupted.load() ||
        CACurrentMediaTime() - p->last_render.load() > 0.15) snapshot->flags &= ~AE_FLAG_ADVANCING;
    snapshot->error_sequence += p->output_error;
    if (p->output_failed) { snapshot->error_code = AE_ERROR_OUTPUT; snapshot->flags &= ~AE_FLAG_ADVANCING; }
}
int at_audio_begin_manual_output(at_audio_output *p) {
    require_main();
    if (p->manual_output.exchange(true)) return 1;
    if (!audio_try(^BOOL { [p->graph pause]; return YES; })) {
        p->manual_output.store(false);
        output_failure(p);
        return 0;
    }
    for (int attempt = 0; attempt < 100 && p->render_active.load() != 0; ++attempt) usleep(1000);
    if (p->render_active.load() != 0) {
        p->manual_output.store(false);
        if (p->wants_play.load()) start_output(p);
        return 0;
    }
    p->consuming.store(false);
    return 1;
}
uint32_t at_audio_pull_manual_output(at_audio_output *p, float *samples, uint32_t frames) {
    require_main();
    if (!p->manual_output.load() || !p->running.load() || p->interrupted.load() || p->closing.load()) return 0;
    uint32_t decoded = ae_engine_render_realtime(p->core, samples, frames);
    p->last_render.store(CACurrentMediaTime());
    p->consuming.store(decoded > 0);
    return decoded;
}
void at_audio_end_manual_output(at_audio_output *p) {
    require_main();
    p->manual_output.store(false);
    p->consuming.store(false);
    if (p->wants_play.load()) start_output(p);
}
void at_audio_manual_output_failed(at_audio_output *p) {
    require_main();
    p->requires_recreation = true;
    p->wants_play.store(false);
    ae_engine_pause(p->core);
    output_failure(p);
}
double at_audio_output_latency(at_audio_output *p) {
    require_main();
    if (!p || !p->graph) return -1.0;
    __block double latency = -1.0;
    audio_try(^BOOL {
        const double session = [AVAudioSession sharedInstance].outputLatency;
        const double presentation = p->graph.outputNode.presentationLatency;
        if (std::isfinite(session) && session >= 0.0) latency = session;
        if (std::isfinite(presentation) && presentation >= 0.0) latency = std::max(latency, presentation);
        return YES;
    });
    return latency;
}
size_t at_audio_last_error(at_audio_output *p, char *buffer, size_t length) {
    require_main();
    if (!p->output_failed) return ae_engine_last_error(p->core, buffer, length);
    if (length) strlcpy(buffer, p->error, length);
    return strlen(p->error);
}

int at_audio_session_begin_recording(void) {
    std::lock_guard<std::mutex> guard(g_session_lock);
    AVAudioSession *session = [AVAudioSession sharedInstance];
    if (g_recording_windows == 0) {
        g_snapshot_category = session.category;
        g_snapshot_mode = session.mode;
        g_snapshot_options = session.categoryOptions;
        g_snapshot_policy = session.routeSharingPolicy;
        g_snapshot_taken = true;
    }
    ++g_recording_windows;
    __block int status = 1;
    audio_try(^BOOL {
        NSError *error = nil;
        if (![session setCategory:AVAudioSessionCategoryPlayAndRecord mode:AVAudioSessionModeDefault
               routeSharingPolicy:AVAudioSessionRouteSharingPolicyDefault options:recording_options() error:&error]) {
            status = 1;
        } else if (![session setActive:YES error:&error]) {
            status = 2;
        } else {
            status = 0;
        }
        return status == 0;
    });
    if (status != 0) {
        --g_recording_windows;
        if (g_recording_windows == 0) settle_session_locked();
        return status;
    }
    if (g_playback_claims > 0) schedule_playback_resume_locked();
    return 0;
}
void at_audio_session_end_recording(void) {
    std::lock_guard<std::mutex> guard(g_session_lock);
    if (g_recording_windows == 0) return;
    --g_recording_windows;
    if (g_recording_windows > 0) return;
    settle_session_locked();
    if (g_playback_claims > 0) schedule_playback_resume_locked();
}
