/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
#import "ATNativeAudio.h"
#import <AVFoundation/AVFoundation.h>
#import <QuartzCore/QuartzCore.h>
#include <algorithm>
#include <atomic>
#include <cstring>
#include <memory>
#include <mutex>
#include <unistd.h>
#include <vector>

/* Uses the declared realtime ABI from the SAME portable core (M8 bee7a838).
 * A stale core/header must fail the build/link, never fall back to the waiting renderer. */

struct at_audio_output;

/* Identity of one native output lifetime, shared by the output and by every queued callback. Destroy clears `output`
 * under `mutex` before the output is freed, and a callback holds `mutex` while it uses the output, so no callback can
 * reach freed memory or a replacement output. A replacement always gets a new gate. A callback keeps its gate alive,
 * so the gate's address cannot be reused while a stale callback still exists. */
struct output_gate {
    std::mutex mutex;
    at_audio_output *output = nullptr; // Guarded by mutex; cleared before the output is deleted.
};

struct at_audio_output {
    ae_engine *core = nullptr;
    std::shared_ptr<output_gate> gate; // Asynchronous callbacks capture this gate, never the output itself.
    AVAudioEngine *__strong graph = nil;
    AVAudioSourceNode *__strong source = nil;
    id __strong interruption = nil;
    id __strong route = nil;
    id __strong reset = nil;
    id __strong configuration = nil;
    std::atomic<bool> running{false};
    std::atomic<bool> wants_play{false};
    std::atomic<bool> interrupted{false};
    std::atomic<bool> consuming{false};
    std::atomic<bool> closing{false};
    std::atomic<int> render_active{0};
    std::atomic<double> last_render{0};
    uint64_t output_error = 0;
    bool output_failed = false;
    bool requires_recreation = false;
    bool playback_claimed = false; // Guarded by g_session_lock: this output currently holds a playback claim.
    char error[256] = {};
};

/* Brackets one render callback so destroy can drain it before freeing the decoder. Never allocates or waits. */
struct render_scope {
    explicit render_scope(at_audio_output *output) : p(output) { p->render_active.fetch_add(1); }
    ~render_scope() { p->render_active.fetch_sub(1); }
    render_scope(const render_scope &) = delete;
    render_scope &operator=(const render_scope &) = delete;
    at_audio_output *p;
};
static void require_main() { NSCAssert([NSThread isMainThread], @"Audio control requires main thread"); }

/* One process-wide policy for the shared AVAudioSession. A playback output holds a claim while it should be audible,
 * and each microphone recording window claims the session for play-and-record. Every category or activation change
 * happens under g_session_lock, from the main thread (playback) or the recognition thread (capture). The render
 * callback never takes this lock, so a session change cannot wait inside it. An output gate is always taken before
 * g_session_lock, never after it. */
static std::mutex g_session_lock;
static int g_recording_windows = 0;   // Guarded by g_session_lock.
static int g_playback_claims = 0;     // Guarded by g_session_lock.
static bool g_snapshot_taken = false; // Guarded by g_session_lock: the category from before the first open window.
static NSString *g_snapshot_category = nil;
static NSString *g_snapshot_mode = nil;
static AVAudioSessionCategoryOptions g_snapshot_options = (AVAudioSessionCategoryOptions)0;
static AVAudioSessionRouteSharingPolicy g_snapshot_policy = AVAudioSessionRouteSharingPolicyDefault;
static std::vector<std::shared_ptr<output_gate>> g_outputs; // Guarded by g_session_lock; changed on the main thread only.

static AVAudioSessionCategoryOptions recording_options(void) {
    // A2DP stays available beside the headset input, so a playback route does not fall back to HFP or the speaker.
    return (AVAudioSessionCategoryOptions)(AVAudioSessionCategoryOptionDefaultToSpeaker |
                                           AVAudioSessionCategoryOptionAllowBluetooth |
                                           AVAudioSessionCategoryOptionAllowBluetoothA2DP);
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
/* Caller holds g_session_lock. A window keeps play-and-record; playback asks for Playback only when no window is open. */
static bool configure_playback_locked(NSError **error) {
    AVAudioSession *session = [AVAudioSession sharedInstance];
    if (g_recording_windows == 0 &&
        ![session setCategory:AVAudioSessionCategoryPlayback mode:AVAudioSessionModeDefault
           routeSharingPolicy:AVAudioSessionRouteSharingPolicyLongFormAudio
                      options:(AVAudioSessionCategoryOptions)0 error:error]) {
        return false;
    }
    return [session setActive:YES error:error];
}
/* Caller holds g_session_lock and has just closed the last window. Current playback intent wins over the category from
 * before the window, so a stale snapshot never replaces the category that playback started in the window needs. */
static void settle_session_locked(void) {
    AVAudioSession *session = [AVAudioSession sharedInstance];
    NSError *error = nil;
    if (g_playback_claims > 0) {
        [session setCategory:AVAudioSessionCategoryPlayback mode:AVAudioSessionModeDefault
          routeSharingPolicy:AVAudioSessionRouteSharingPolicyLongFormAudio
                     options:(AVAudioSessionCategoryOptions)0 error:&error];
    } else if (g_snapshot_taken) {
        [session setCategory:g_snapshot_category ? g_snapshot_category : AVAudioSessionCategoryPlayback
                        mode:g_snapshot_mode ? g_snapshot_mode : AVAudioSessionModeDefault
          routeSharingPolicy:g_snapshot_policy
                     options:g_snapshot_options error:&error];
    }
    g_snapshot_taken = false;
    g_snapshot_category = nil;
    g_snapshot_mode = nil;
    g_snapshot_options = (AVAudioSessionCategoryOptions)0;
    g_snapshot_policy = AVAudioSessionRouteSharingPolicyDefault;
}
static void output_failure(at_audio_output *p) {
    p->running.store(false);
    p->output_failed = true;
    ++p->output_error;
    release_playback(p); // A failed output gives its claim back; the next start claims the session again.
    // Deliberately do not expose decoder URLs, HTTP headers, or arbitrary NSError descriptions.
    strlcpy(p->error, "AVAudioSession or AVAudioEngine output failed", sizeof(p->error));
}
static bool start_output(at_audio_output *p) {
    if (p->interrupted.load() || p->requires_recreation) return false;
    NSError *error = nil;
    bool started = false;
    {
        // Claim, category and engine start form one step under the lock, so a window that closes meanwhile sees this intent.
        std::lock_guard<std::mutex> guard(g_session_lock);
        claim_playback_locked(p);
        started = configure_playback_locked(&error);
        if (started && ![p->graph isRunning]) started = [p->graph startAndReturnError:&error];
        if (!started) release_playback_locked(p);
    }
    if (!started) {
        output_failure(p);
        return false;
    }
    p->running.store(true);
    p->output_failed = false;
    return true;
}
/* Restarts a claimed output that the system stopped, such as after a category or hardware change. Main thread only.
 * The gate is held across the check and the restart, so a destroy that has begun cannot be raced. */
static void resume_gate(const std::shared_ptr<output_gate> &gate) {
    std::lock_guard<std::mutex> guard(gate->mutex);
    at_audio_output *p = gate->output;
    if (!p || !p->graph || p->closing.load() || p->interrupted.load() || p->requires_recreation) return;
    bool claimed = false;
    {
        std::lock_guard<std::mutex> session(g_session_lock);
        claimed = p->playback_claimed;
    }
    if (claimed && ![p->graph isRunning]) start_output(p);
}
/* Caller holds g_session_lock. A category change can stop a running engine, and the engine is restarted on the main
 * thread. The gates are captured now, so the restart reaches only outputs that existed when the session changed. */
static void schedule_playback_resume_locked(void) {
    std::vector<std::shared_ptr<output_gate>> gates = g_outputs;
    dispatch_async(dispatch_get_main_queue(), ^{
        for (const std::shared_ptr<output_gate> &gate : gates) resume_gate(gate);
    });
}

at_audio_output *at_audio_create(char *error, size_t error_len) {
    require_main();
    auto *p = new at_audio_output();
    ae_config config = {AE_ABI_VERSION, AE_OUTPUT_MANUAL, AE_BACKEND_DEFAULT, 48000, 2000, 120};
    p->core = ae_engine_create(&config, error, error_len);
    if (!p->core) { delete p; return nullptr; }
    p->gate = std::make_shared<output_gate>();
    p->gate->output = p;
    const std::shared_ptr<output_gate> gate = p->gate; // Asynchronous callbacks capture this lifetime, never p.
    p->graph = [[AVAudioEngine alloc] init];
    AVAudioFormat *format = [[AVAudioFormat alloc] initWithCommonFormat:AVAudioPCMFormatFloat32
                                                          sampleRate:48000 channels:2 interleaved:YES];
    p->source = [[AVAudioSourceNode alloc] initWithFormat:format
        renderBlock:^OSStatus(BOOL *silent, const AudioTimeStamp *, AVAudioFrameCount frames,
                              AudioBufferList *buffers) {
        // Entirely native callback: no Kotlin, allocation, decoder waits, network or UI work.
        render_scope active(p); // Destroy waits for this scope before the decoder can be released.
        for (UInt32 i = 0; i < buffers->mNumberBuffers; ++i) {
            if (buffers->mBuffers[i].mData)
                memset(buffers->mBuffers[i].mData, 0, buffers->mBuffers[i].mDataByteSize);
        }
        if (p->closing.load() || !p->running.load() || p->interrupted.load() || buffers->mNumberBuffers != 1 ||
            !buffers->mBuffers[0].mData ||
            buffers->mBuffers[0].mNumberChannels != 2 ||
            buffers->mBuffers[0].mDataByteSize < frames * 2 * sizeof(float)) {
            *silent = YES;
            p->consuming.store(false);
            return noErr;
        }
        uint32_t decoded = ae_engine_render_realtime(p->core,
            static_cast<float *>(buffers->mBuffers[0].mData), frames);
        p->last_render.store(CACurrentMediaTime());
        p->consuming.store(decoded > 0);
        *silent = decoded == 0;
        return noErr;
    }];
    [p->graph attachNode:p->source];
    [p->graph connect:p->source to:p->graph.mainMixerNode format:format];
    [p->graph prepare];
    NSNotificationCenter *center = [NSNotificationCenter defaultCenter];
    p->interruption = [center addObserverForName:AVAudioSessionInterruptionNotification object:nil
        queue:[NSOperationQueue mainQueue] usingBlock:^(NSNotification *note) {
        std::lock_guard<std::mutex> guard(gate->mutex);
        at_audio_output *output = gate->output;
        if (!output) return; // Destroyed after this notification was queued; it must not act on anything else.
        NSUInteger type = [note.userInfo[AVAudioSessionInterruptionTypeKey] unsignedIntegerValue];
        if (type == AVAudioSessionInterruptionTypeBegan) {
            output->interrupted.store(true);
            output->running.store(false);
            [output->graph pause]; // Preserve intent and decoder position, but never extrapolate.
            release_playback(output); // An interrupted output does not hold the session; its resume claims it again.
        } else {
            output->interrupted.store(false);
            NSUInteger options = [note.userInfo[AVAudioSessionInterruptionOptionKey] unsignedIntegerValue];
            if (output->wants_play.load() && (options & AVAudioSessionInterruptionOptionShouldResume)) {
                start_output(output);
            }
        }
    }];
    p->route = [center addObserverForName:AVAudioSessionRouteChangeNotification object:nil
        queue:[NSOperationQueue mainQueue] usingBlock:^(NSNotification *note) {
        std::lock_guard<std::mutex> guard(gate->mutex);
        at_audio_output *output = gate->output;
        if (!output) return;
        if ([note.userInfo[AVAudioSessionRouteChangeReasonKey] unsignedIntegerValue] ==
            AVAudioSessionRouteChangeReasonOldDeviceUnavailable) at_audio_pause(output);
    }];
    p->reset = [center addObserverForName:AVAudioSessionMediaServicesWereResetNotification object:nil
        queue:[NSOperationQueue mainQueue] usingBlock:^(NSNotification *) {
        std::lock_guard<std::mutex> guard(gate->mutex);
        at_audio_output *output = gate->output;
        if (!output) return;
        output->wants_play.store(false);
        ae_engine_pause(output->core);
        [output->graph stop];
        output->requires_recreation = true;
        output_failure(output); // Recreate adapter explicitly; an old graph is not a recovered route.
    }];
    // The engine stops itself when its configuration changes, for example when a recording window changes the session.
    // Only a claimed output is restarted here; a paused, interrupted or failed output stays stopped.
    p->configuration = [center addObserverForName:AVAudioEngineConfigurationChangeNotification object:p->graph
        queue:[NSOperationQueue mainQueue] usingBlock:^(NSNotification *) {
        resume_gate(gate);
    }];
    {
        std::lock_guard<std::mutex> guard(g_session_lock);
        g_outputs.push_back(gate);
    }
    return p;
}

void at_audio_destroy(at_audio_output *p) {
    require_main();
    if (!p) return;
    {
        // Invalidates this lifetime before any other teardown. A callback queued earlier still runs later, but it sees the
        // gate cleared and returns without touching the output, even if the allocator reuses this address afterwards.
        std::lock_guard<std::mutex> guard(p->gate->mutex);
        p->gate->output = nullptr;
    }
    NSNotificationCenter *center = [NSNotificationCenter defaultCenter];
    [center removeObserver:p->interruption];
    [center removeObserver:p->route];
    [center removeObserver:p->reset];
    [center removeObserver:p->configuration];
    {
        std::lock_guard<std::mutex> guard(g_session_lock);
        release_playback_locked(p);
        g_outputs.erase(std::remove(g_outputs.begin(), g_outputs.end(), p->gate), g_outputs.end());
    }
    p->closing.store(true); // Published before the drain: a late callback observes it and stays silent.
    p->running.store(false);
    __weak AVAudioSourceNode *released = p->source;
    [p->graph stop];        // Stops new render cycles; an in-flight one is drained below.
    [p->graph detachNode:p->source];
    p->source = nil;
    p->graph = nil;
    // The node owns the render block that holds p, so it must deallocate as well as leave its render
    // call. Only this main-thread caller waits; the callback never blocks. A bound that is exceeded keeps
    // the decoder allocated (a bounded leak) rather than freeing memory a late callback may still read.
    bool drained = false;
    for (int attempt = 0; attempt < 2000 && !drained; ++attempt) {
        drained = p->render_active.load() == 0 && released == nil;
        if (!drained) usleep(1000);
    }
    {
        std::lock_guard<std::mutex> guard(g_session_lock);
        // Deactivation is the playback host's last act. It never happens while a recording window or other playback
        // holds the shared session, because that would stop the window or the other output.
        if (g_recording_windows == 0 && g_playback_claims == 0) {
            [[AVAudioSession sharedInstance] setActive:NO
                withOptions:AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation error:nil];
        }
    }
    if (drained) {
        ae_engine_destroy(p->core);
        delete p;
    }
}
uint64_t at_audio_load(at_audio_output *p, const char *source, uint64_t start, int play) {
    require_main();
    p->wants_play.store(play != 0);
    if (!play) release_playback(p); // A paused load gives up the playback claim, as pause does.
    uint64_t generation = ae_engine_load(p->core, source, start, play);
    if (generation && play) start_output(p);
    return generation;
}
uint64_t at_audio_load_stream(at_audio_output *p, const ae_stream_io *io, uint64_t start, int play) {
    require_main();
    p->wants_play.store(play != 0);
    if (!play) release_playback(p); // A paused load gives up the playback claim, as pause does.
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
    [p->graph pause];
    release_playback(p);
}
void at_audio_seek(at_audio_output *p, uint64_t position) { require_main(); ae_engine_seek(p->core, position); }
void at_audio_speed(at_audio_output *p, float speed) { require_main(); ae_engine_set_speed(p->core, speed); }
void at_audio_volume(at_audio_output *p, float volume) {
    require_main();
    if (!(volume >= 0.0f)) volume = 0.0f;
    if (volume > 1.0f) volume = 1.0f;
    p->graph.mainMixerNode.outputVolume = volume;
}
void at_audio_stop(at_audio_output *p) {
    at_audio_pause(p);
    ae_engine_stop(p->core);
}
void at_audio_snapshot(at_audio_output *p, ae_snapshot *snapshot) {
    require_main();
    ae_engine_snapshot(p->core, snapshot);
    // Core counters count PCM consumed by the output callback, not a timer. A dead graph must not
    // retain its last advancing bit. Physical DAC presentation latency remains unmeasured.
    if (!p->running.load() || !p->consuming.load() || p->interrupted.load() ||
        CACurrentMediaTime() - p->last_render.load() > 0.15) snapshot->flags &= ~AE_FLAG_ADVANCING;
    snapshot->error_sequence += p->output_error;
    if (p->output_failed) {
        snapshot->error_code = AE_ERROR_OUTPUT;
        snapshot->flags &= ~AE_FLAG_ADVANCING;
    }
}
size_t at_audio_last_error(at_audio_output *p, char *buffer, size_t length) {
    require_main();
    if (!p->output_failed) return ae_engine_last_error(p->core, buffer, length);
    if (length) strlcpy(buffer, p->error, length);
    return strlen(p->error);
}

/* Microphone capture calls these from its own thread. The policy is described in ATNativeAudio.h. */
int at_audio_session_begin_recording(void) {
    std::lock_guard<std::mutex> guard(g_session_lock);
    AVAudioSession *session = [AVAudioSession sharedInstance];
    if (g_recording_windows == 0) {
        // Only the first window records the category from before recording. A later window must not replace it.
        g_snapshot_category = session.category;
        g_snapshot_mode = session.mode;
        g_snapshot_options = session.categoryOptions;
        g_snapshot_policy = session.routeSharingPolicy;
        g_snapshot_taken = true;
    }
    ++g_recording_windows;
    NSError *error = nil;
    int status = 0;
    if (![session setCategory:AVAudioSessionCategoryPlayAndRecord mode:AVAudioSessionModeDefault
           routeSharingPolicy:AVAudioSessionRouteSharingPolicyDefault
                      options:recording_options() error:&error]) {
        status = 1;
    } else if (![session setActive:YES error:&error]) {
        status = 2;
    }
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
