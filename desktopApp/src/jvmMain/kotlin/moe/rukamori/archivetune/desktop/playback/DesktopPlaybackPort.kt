package moe.rukamori.archivetune.desktop.playback

import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.round
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.shared.data.bytesource.ScopedCookieSource
import moe.rukamori.archivetune.shared.data.playback.EnginePlaybackMapper
import moe.rukamori.archivetune.shared.data.playback.EnginePlaybackState
import moe.rukamori.archivetune.shared.data.playback.PlaybackProgressWatchdog
import moe.rukamori.archivetune.shared.data.stream.NativeRouteOutcome
import moe.rukamori.archivetune.shared.domain.dsp.DspSettings
import moe.rukamori.archivetune.shared.domain.dsp.bitperfect.BitPerfectGate
import moe.rukamori.archivetune.shared.domain.dsp.bitperfect.BitPerfectRouteFacts
import moe.rukamori.archivetune.shared.domain.dsp.bitperfect.SoftwareStagePlan
import moe.rukamori.archivetune.shared.domain.dsp.bitperfect.SoftwareStageRequest
import moe.rukamori.archivetune.shared.domain.dsp.eq.EqualizerBand
import moe.rukamori.archivetune.shared.domain.dsp.normalization.NormalizationGain
import moe.rukamori.archivetune.shared.domain.platform.AcceptedAccountReader
import moe.rukamori.archivetune.shared.domain.playback.MonotonicClock
import moe.rukamori.archivetune.shared.domain.playback.PlaybackCommand
import moe.rukamori.archivetune.shared.domain.playback.PlaybackFailure
import moe.rukamori.archivetune.shared.domain.playback.PlaybackFailureReason
import moe.rukamori.archivetune.shared.domain.playback.PlaybackLocator
import moe.rukamori.archivetune.shared.domain.playback.PlaybackPort
import moe.rukamori.archivetune.shared.domain.playback.PlaybackRequest
import moe.rukamori.archivetune.shared.domain.playback.PlaybackSnapshot
import moe.rukamori.archivetune.shared.domain.playback.PreparedPlaintextHandle
import moe.rukamori.archivetune.shared.domain.playback.SystemMonotonicClock
import moe.rukamori.archivetune.shared.domain.repository.PreparedAudio
import moe.rukamori.archivetune.shared.domain.transition.TransitionDeck
import moe.rukamori.archivetune.shared.domain.transition.TransitionKind
import moe.rukamori.archivetune.shared.domain.transition.TransitionPlan
import moe.rukamori.archivetune.shared.domain.transition.TwoDeckTransitionScheduler

/**
 * A [PlaybackPort] over the native FFmpeg engine and the two-deck output mixer.
 *
 * Two manual engine decks share one output device, owned by a [NativeTwoDeckMixer]. The device callback is
 * the only clock: a handoff takes effect on the output frame its schedule names, so a fade is sample-accurate
 * however the device splits its callbacks, and no second timer decides when the decks change. The occurrence
 * that is playing is the only one reported. A preloaded deck stays invisible until the mixer commits it.
 *
 * Every engine call runs on one background thread, so commands keep their arrival order and no bookkeeping
 * sees two threads. A commit is recognised only from the mixer's commit sequence, which the callback changes
 * inside the same locked step as the output, so the published occurrence never runs ahead of the audible one.
 *
 * Local files and `http`/`https` URLs open. A URL that carries request headers opens through a host lease, so its
 * headers reach only its own origin, and FFmpeg never opens a child request for it. A Deezer-encrypted track opens
 * through its decrypting lease, so its ciphertext never reaches the engine as a plain URL. A Telegram track opens
 * through a lease over its TDLib file once the host binds a signed-in session and its accepted account with
 * [bindTelegramFiles], and fails until then. A prepared plaintext view opens through its host lease, and only once:
 * [prepareNative] makes it through the shared route, and a second load or an expired view needs a fresh
 * preparation. Any other locator fails at once, and so does a relative path.
 *
 * @param backend The output backend. Tests pass [EngineBackend.NULL].
 * @param accounts The accepted-account store that admits each personal native stream. The host passes the store that
 *   commits the accounts, so every admission and cookie read sees one owner. Null refuses every native occurrence
 *   rather than read it under an account the port cannot check.
 * @param cookies The store's cookie reads, matched by each admitted entry scope. Null sends no account cookie.
 * @param commandFailureText User-facing reason for a command the playback thread could not apply; never a path or credential.
 */
class DesktopPlaybackPort(
    private val backend: EngineBackend = EngineBackend.DEFAULT,
    private val clock: MonotonicClock = SystemMonotonicClock,
    pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
    private val accounts: AcceptedAccountReader? = null,
    private val cookies: ScopedCookieSource? = null,
    private val commandFailureText: () -> String = { DEFAULT_COMMAND_FAILURE_TEXT },
) : PlaybackPort {
    private val executor = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "archivetune-playback").apply { isDaemon = true }
    }
    private val closed = AtomicBoolean(false)
    private val publishedSnapshot = MutableStateFlow<PlaybackSnapshot?>(null)
    private val publishedFailures = MutableSharedFlow<PlaybackFailure>(extraBufferCapacity = FAILURE_BUFFER)

    /** Confined to [executor]. Reports the audible occurrence; its shared tracker keeps generations monotonic. */
    private val mapper = EnginePlaybackMapper()

    /**
     * Confined to [executor]. One watcher per deck, so a preloaded deck reports its own open failure before it
     * is ever audible. The watchers never publish snapshots, so their trackers never disagree with [mapper].
     */
    private val failureMappers = Array(DECK_COUNT) { EnginePlaybackMapper() }

    /** Confined to [executor]. The occurrence each deck is playing, with that deck's load generation. */
    private val sessions = arrayOfNulls<DeckSession>(DECK_COUNT)

    /** Confined to [executor]. The shared handoff rules, kept in step with the mixer's committed state. */
    private val scheduler = TwoDeckTransitionScheduler(TransitionDeck.FIRST)

    /** Confined to [executor]. Null until the first load opens the device, and while a pause parks it. */
    private var mixer: NativeTwoDeckMixer? = null

    /** Confined to [executor]. The last mixer commit this port has adopted. */
    private var committedSequence = 0L

    /** Confined to [executor]. The paused occurrence parked by [releaseOutput]; its deck is gone. */
    private var suspendedSession: DeckSession? = null

    /** Confined to [executor]. The microsecond position [suspendedSession] reopens at. */
    private var suspendedPositionUs = 0L

    /** Confined to [executor]. The software stages the user asked for, before the bit-perfect gate narrows them. */
    private var stages = StageConfig()

    /** Confined to [executor]. The complete shared DSP document. */
    private var dspSettings = DspSettings()

    private val output = DesktopPlaybackOutput()
    internal val outputState get() = output.state
    fun configureOutput(exclusive: Boolean, deviceId: String) = submitTask {
        output.configure(mixer, exclusive, deviceId); applyStages()
    }

    /** The published suspended flag, so a lifecycle owner or a test can follow the parked output. */
    private val outputSuspendedFlow = MutableStateFlow(false)

    /**
     * Confined to [executor]. One watchdog per deck: a deck that expects progress but renders none is
     * a stall, and a preloaded deck is timed on its own reads once its handoff start frame arrives.
     */
    private val stallWatchdogs = Array(DECK_COUNT) { PlaybackProgressWatchdog() }

    /** Confined to [executor]. The last device-consumed frame count and when it last moved. */
    private var lastTimelineFrames = NO_TIMELINE
    private var timelineProgressedAtNanos = 0L

    /**
     * Confined to [executor]. The load generation the output was rebuilt for: a stall under a rebuilt
     * load goes to the failure path instead of rebuilding again.
     */
    private var rebuiltForLoadGeneration = NO_LOAD

    /*
     * The spectral check owns its own IO and default dispatchers and never waits on [executor], so it is driven
     * from the caller's coroutine. Only conclusive results are cached, and only under the source they measured.
     */
    internal val spectral = DesktopSpectralAuthenticity()
    internal val spectralCache = SourceBoundSpectralCache(SPECTRAL_CACHE_CAPACITY)

    /**
     * The signed-in Telegram session's TDLib calls and accepted-account store, read on the playback thread when a Telegram
     * track opens. Null until the host binds a session, so a Telegram track is refused rather than read through another route.
     */
    @Volatile
    private var telegramFiles: TelegramFileProvider? = null

    override val snapshots: StateFlow<PlaybackSnapshot?> = publishedSnapshot.asStateFlow()
    override val failures: SharedFlow<PlaybackFailure> = publishedFailures.asSharedFlow()

    /**
     * Whether host output is enabled: false mutes every live deck as gain zero. Mirrored off the
     * executor for the session's mute-transition policy, which reads it next to the stored volume.
     */
    val audioOutputEnabled: StateFlow<Boolean> = output.enabledState

    /** True while [releaseOutput] has parked the device; the next play or seek reopens it at the held position. */
    val outputSuspended: StateFlow<Boolean> = outputSuspendedFlow.asStateFlow()

    private val activePollIntervalMs = pollIntervalMs
    private val idlePollIntervalMs = maxOf(pollIntervalMs, IDLE_POLL_INTERVAL_MS)

    /*
     * The next engine read. It is rescheduled after every read rather than fixed-rate, so a paused or idle port reads
     * a few times a second instead of fifty and the process can stay asleep between reads.
     */
    @Volatile
    private var nextPoll: ScheduledFuture<*>? = null

    init {
        schedulePoll(pollIntervalMs)
    }

    override fun submit(command: PlaybackCommand) {
        // A failed Load names its occurrence; other commands fall back to the active session.
        val request = (command as? PlaybackCommand.Load)?.request
        submitTask(request) { applyCommand(command) }
    }

    /**
     * Sets the output volume, 0 to 1, for every live deck and for any deck loaded later. It scales the deck gain, so a
     * transition fade and normalization keep their own shape under it. While the bit-perfect route bypasses the mixer
     * the level does not alter samples, as on Android, where the device volume controls that route.
     */
    fun setVolume(volume: Float) {
        require(volume.isFinite() && volume in 0f..1f) { "volume must be between 0 and 1, was $volume" }
        submitTask {
            output.volume(volume)
            applyStages()
        }
    }

    /**
     * Mutes or unmutes the output as gain on every live deck, including the preloaded deck of an
     * armed transition, without closing the device: a muted occurrence keeps rendering and its
     * position keeps advancing, so an unmute lands mid-track exactly where the mute left it, as a
     * system-level mute behaves. The stored volume is untouched. While the bit-perfect route
     * bypasses the mixer the flag does not alter samples, as with [setVolume].
     */
    fun setAudioOutputEnabled(enabled: Boolean) {
        submitTask {
            output.enabled(mixer, enabled)
            applyStages()
        }
    }

    /**
     * Releases the output device while a paused, seekable occurrence holds it — the desktop's
     * STOPPED_ON_BACKGROUND: a session parked long enough or sent off-screen no longer holds the
     * sound card, its callback thread or its decode buffers, yet keeps its place so the next
     * [PlaybackCommand.Play] or [PlaybackCommand.SeekTo] reopens it where it stopped. Playing,
     * buffering, ended and unloaded states never park, so a transition or a live stream is never
     * interrupted; a parked locator that cannot reopen fails once on resume, like an output stall.
     */
    fun releaseOutput() {
        submitTask { suspendOutput(clock.nowNanos()) }
    }

    /**
     * Prepares [next] on the idle deck and schedules it to take over the output.
     *
     * [TransitionKind.GAPLESS] splices at the end of the playing occurrence with no overlap. Any other kind
     * fades over [overlapMs], starting so that the fade ends as the playing media ends. [trackGainDb] and
     * [trackPeak] describe [next] for normalization.
     *
     * Returns false with nothing changed when the schedule cannot be honoured: nothing is playing with a known
     * duration, a handoff is already armed, the route is bypassed, a fade is requested while two-deck mixing is
     * off, or the playing media ends before the fade could start. Blocks for one hop to the playback thread,
     * so it must not be called from a collector of [snapshots] or [failures].
     */
    fun armNext(
        next: PlaybackRequest,
        kind: TransitionKind,
        overlapMs: Long,
        startPositionMs: Long = 0L,
        trackGainDb: Double? = null,
        trackPeak: Double? = null,
    ): Boolean {
        require(overlapMs >= 0L) { "overlapMs must not be negative, was $overlapMs" }
        require((kind == TransitionKind.GAPLESS) == (overlapMs == 0L)) {
            "a gapless handoff has no overlap and a fade needs one, was $kind over $overlapMs ms"
        }
        require(startPositionMs >= 0L) { "startPositionMs must not be negative, was $startPositionMs" }
        return runOnPlaybackThread(fallback = false) {
            arm(next, kind, overlapMs, startPositionMs, trackGainDb, trackPeak)
        }
    }

    /**
     * Sets the software stages for the playing occurrence and for any handoff armed after this call.
     *
     * The equalizer, normalization and the bit-perfect route all go through [BitPerfectGate] and
     * [SoftwareStagePlan], so one rule decides which stages run. [routeFacts] is what the platform observed for
     * the output; the port never trusts it to report its own sample-altering stages. [trackGainDb] and
     * [trackPeak] describe the playing occurrence, so call this after the load that starts it.
     */
    fun configureSoftwareStages(
        request: SoftwareStageRequest,
        equalizerBands: List<EqualizerBand>,
        routeFacts: BitPerfectRouteFacts,
        trackGainDb: Double? = null,
        trackPeak: Double? = null,
    ) {
        require(equalizerBands.size <= NativeTwoDeckMixer.MAX_BANDS) {
            "the equalizer has ${equalizerBands.size} bands; the mixer holds ${NativeTwoDeckMixer.MAX_BANDS}"
        }
        submitTask {
            stages = StageConfig(request, equalizerBands, routeFacts)
            val index = activeIndex()
            sessions[index]?.let { sessions[index] = it.copy(trackGainDb = trackGainDb, trackPeak = trackPeak) }
            applyStages()
        }
    }

    /** Applies the stored DSP settings to both current and later decks. */
    fun applyDspSettings(settings: DspSettings) {
        require(settings.equalizerBands.size <= NativeTwoDeckMixer.MAX_BANDS) {
            "the equalizer has ${settings.equalizerBands.size} bands; the mixer holds ${NativeTwoDeckMixer.MAX_BANDS}"
        }
        submitTask {
            dspSettings = settings
            stages = stages.copy(
                request = stages.request.copy(
                    normalizationEnabled = settings.normalizationEnabled,
                    equalizerEnabled = settings.equalizerEnabled,
                ),
                bands = settings.equalizerBands,
            )
            applyStages()
        }
    }

    fun setSkipSilence(enabled: Boolean) = submitTask {
        stages = stages.copy(request = stages.request.copy(silenceSkippingEnabled = enabled))
        applyStages()
    }

    /**
     * The active deck's loaded request on the playback thread, which the spectral entry points in
     * [DesktopSpectralAudit] bind their audit target to. A deck that is not loaded has none.
     */
    internal fun loadedRequest(): PlaybackRequest? =
        runOnPlaybackThread(fallback = null) { sessions[activeIndex()]?.request }

    /**
     * Binds the Telegram session the host has signed in, or unbinds it with null. The binding carries the account store and
     * the scope the account was committed under, so each open admits the account again. The host owns the TDLight client and
     * closes it at session end, so this port neither creates nor closes one. The provider is consulted when a track opens, so
     * a track that is already playing keeps the file it opened.
     */
    internal fun bindTelegramFiles(provider: TelegramFileProvider?) {
        telegramFiles = provider
    }

    /**
     * Prepares one occurrence through the shared route: a recipe the engine reads through its host lease, a TIDAL
     * manifest, or a progressive stream that names a personal account. A [NativeRouteOutcome.Ready] handle becomes a
     * [PlaybackLocator.PreparedPlaintext] for a Load command.
     *
     * A [NativeRouteOutcome.Refused] sends the caller down its source chain. The caller must never turn a refused personal
     * stream into a [PlaybackLocator.Remote] for the same bytes, because that locator carries no account cookie. An
     * anonymous progressive stream and a Deezer stream do not come here: they keep their ordinary locators.
     *
     * Call it from any coroutine except the playback thread or the audio callback. The admission and the probe block on
     * socket IO, so the work runs on [Dispatchers.IO] whatever dispatcher the caller uses. Cancelling the caller releases
     * a handle that was produced at the same moment, so no transport is left open without an owner.
     *
     * Every handle that is returned ends in one of three places: a Load claims it, [releasePrepared] releases it when
     * the caller drops or replaces it, or [releasePrepared] releases it at shutdown. A handle in none of those states
     * keeps its socket open for as long as the caller holds it.
     */
    suspend fun prepareNative(prepared: PreparedAudio): NativeRouteOutcome {
        if (closed.get()) return NativeRouteOutcome.Refused("the playback port is closed")
        val produced = AtomicReference<NativeRouteOutcome?>(null)
        val outcome =
            try {
                withContext(Dispatchers.IO) {
                    prepareHostPlaintext(prepared, accounts, cookies).also { produced.set(it) }
                }
            } catch (cancelled: CancellationException) {
                // A view produced as the caller was cancelled never reaches the caller, so it is released here instead.
                (produced.getAndSet(null) as? NativeRouteOutcome.Ready)?.let { releasePrepared(it.handle) }
                throw cancelled
            }
        // A port closed during the preparation must not hand out a transport that nothing will release.
        if (closed.get() && outcome is NativeRouteOutcome.Ready) {
            releasePrepared(outcome.handle)
            return NativeRouteOutcome.Refused("the playback port is closed")
        }
        return outcome
    }

    /**
     * Closes the transport of a prepared handle that will not be loaded. The caller uses it for a handle it drops or
     * replaces, and for each handle it still holds at shutdown. A handle that a Load already took belongs to its engine
     * lease, so releasing it again does nothing, and a handle this port did not prepare is ignored.
     */
    fun releasePrepared(handle: PreparedPlaintextHandle) {
        (handle as? HostPreparedPlaintext)?.release()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        nextPoll?.cancel(false)
        val teardown = executor.submit(Runnable {
            mixer?.close()
            mixer = null
            sessions.fill(null)
        })
        executor.shutdown()
        // The native teardown stops the audio callback before it frees the decks, so waiting for it here lets the
        // caller rely on the output being released. The wait is bounded: a stuck backend must not hold shutdown,
        // and the queued teardown still completes on the executor afterwards.
        try {
            teardown.get(CLOSE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            // Still queued behind a running command. It runs to completion on the executor, not abandoned.
        } catch (error: ExecutionException) {
            System.err.println("playback teardown failed: ${error.cause?.message}")
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun applyCommand(command: PlaybackCommand) {
        syncHandoff()
        when (command) {
            is PlaybackCommand.Load -> {
                // A new occurrence replaces the parked one too; its deck died with the parked mixer.
                suspendedSession = null
                outputSuspendedFlow.value = false
                load(command)
            }
            PlaybackCommand.Play -> {
                resumeOutput()
                activeEngine()?.play()
            }
            PlaybackCommand.Pause -> {
                settleArmed()
                activeEngine()?.pause()
            }
            is PlaybackCommand.SeekTo -> {
                resumeOutput()
                settleArmed()
                activeEngine()?.seek(command.positionMs * MICROS_PER_MILLI)
            }
            is PlaybackCommand.SetSpeed -> {
                stages = stages.copy(request = stages.request.copy(speed = command.speed.toDouble()))
                applyStages()
            }
            is PlaybackCommand.SetPitch -> {
                stages = stages.copy(request = stages.request.copy(pitchSemitones = (12.0 * kotlin.math.log2(command.pitch.toDouble())).coerceIn(-24.0, 24.0)))
                applyStages()
            }
            PlaybackCommand.Stop -> {
                // The shared scheduler promotes the incoming deck on stop, so the stopped occurrence is the one
                // the listener now hears, not the fade's outgoing deck.
                suspendedSession = null
                outputSuspendedFlow.value = false
                settleArmed()
                stopSession()
            }
        }
    }

    private fun load(command: PlaybackCommand.Load) {
        val request = command.request
        val source = engineSourceOpen(request.locator, telegramFiles)
        if (source == null) {
            stopSession()
            val (reason, message) = refusalOf(request.locator)
            fail(request, reason, message)
            return
        }
        val mix = mixer ?: openMixer(request)
        if (mix == null) {
            // openMixer has already reported the failure. The source was never handed to a deck, so release it here.
            source.discard()
            return
        }
        // A new occurrence replaces whatever was queued behind the playing one, so no stale preload can commit.
        cancelArmed(mix)
        val index = activeIndex()
        output.prepareTrack(mix)
        val generation = mix.deck(index).load(source, command.startPositionMs * MICROS_PER_MILLI, command.playWhenReady)
        if (generation == REJECTED_LOAD) {
            stopSession()
            fail(request, PlaybackFailureReason.OPEN_FAILED, "the engine rejected the source")
            return
        }
        sessions[index] = DeckSession(request, generation, lease = source.leaseOrNull())
        failureMappers[index].beginRequest(request, generation)
        mapper.beginRequest(request, generation)
        stallWatchdogs[index].reset()
        applyStages()
    }

    private fun arm(
        next: PlaybackRequest,
        kind: TransitionKind,
        overlapMs: Long,
        startPositionMs: Long,
        trackGainDb: Double?,
        trackPeak: Double?,
    ): Boolean {
        syncHandoff()
        val mix = mixer ?: return false
        val active = activeIndex()
        // The shared scheduler owns which deck is incoming, so the port never derives it from index arithmetic.
        val incomingIndex = scheduler.activeDeck.other().ordinal
        if (sessions[active] == null || scheduler.armedIncomingDeck != null) return false

        val plan = currentPlan()
        // Bit-perfect bypass passes samples untouched, so no handoff may blend another deck into them.
        if (plan.bypass || (kind != TransitionKind.GAPLESS && !plan.twoDeckMixing)) return false

        val timing = mix.snapshot()
        if (!mix.deck(active).snapshot().playWhenReady || timing.activeDurationUs < 0L || timing.activeSpeed <= 0f) {
            return false
        }

        // The mixer's timeline counts output frames, while media advances at the deck speed. The fade is timed in
        // output time, so the playing media's remaining length is converted with the same speed the deck uses.
        val rate = mix.sampleRateHz.toDouble()
        val remainingOutputUs = (timing.activeDurationUs - timing.activePositionUs).toDouble() / timing.activeSpeed
        val fadeStartUs = remainingOutputUs - overlapMs * MICROS_PER_MILLI
        if (fadeStartUs < 0.0) return false
        val transition = TransitionPlan(
            kind = kind,
            startFrame = timing.timelineFrames + round(fadeStartUs * rate / MICROS_PER_SECOND).toLong(),
            overlapFrames = round(overlapMs * rate / MILLIS_PER_SECOND).toLong(),
        )
        // The source is opened only after every refusal above has passed, so a refused arm never holds a host lease.
        val source = engineSourceOpen(next.locator, telegramFiles) ?: return false
        if (!scheduler.arm(scheduler.activeDeck.other(), transition)) {
            source.discard()
            return false
        }

        // Preloading uses play intent, because a deck the mixer reads is silent without it. Its producer then fills
        // the prebuffer before the takeover, and the deck contributes nothing until its start frame.
        val incoming = mix.deck(incomingIndex)
        val generation = incoming.load(source, startPositionMs * MICROS_PER_MILLI, playWhenReady = true)
        if (generation == REJECTED_LOAD) {
            scheduler.cancel()
            fail(next, PlaybackFailureReason.OPEN_FAILED, "the engine rejected the source")
            return false
        }
        sessions[incomingIndex] = DeckSession(next, generation, trackGainDb, trackPeak, lease = source.leaseOrNull())
        failureMappers[incomingIndex].beginRequest(next, generation)
        stallWatchdogs[incomingIndex].reset()
        applySession(mix, incomingIndex, plan)

        if (!mix.arm(incomingIndex, transition.startFrame, transition.overlapFrames)) {
            scheduler.cancel()
            retire(incomingIndex)
            return false
        }
        return true
    }

    /**
     * Drops an armed handoff, so the playing occurrence keeps the output. Nothing happens when none is armed. A fade
     * that already started returns the output to the outgoing deck.
     */
    fun cancelNext() {
        submitTask {
            syncHandoff()
            mixer?.let(::cancelArmed)
        }
    }

    /**
     * A seek, pause or stop during a fade makes the incoming deck active at once, as the shared scheduler does. Before
     * the fade starts nothing of the incoming deck is audible yet, so the handoff is dropped instead: a pause or a
     * seek back must not skip the song the listener is hearing. The queue arms it again for the new position.
     */
    private fun settleArmed() {
        val mix = mixer ?: return
        val plan = scheduler.armedPlan ?: return
        if (mix.snapshot().timelineFrames < plan.startFrame) {
            cancelArmed(mix)
            return
        }
        mix.promote()
        syncHandoff()
    }

    /**
     * Adopts a handoff the device callback committed since the last check. The callback changes the active deck
     * and the sequence number in one locked step, so the port never has to infer a commit from elapsed time.
     */
    private fun syncHandoff() {
        val mix = mixer ?: return
        val state = mix.snapshot()
        if (state.commitSequence == committedSequence) return
        committedSequence = state.commitSequence
        scheduler.commitIncoming()
        val committed = state.activeDeck
        retire(1 - committed)
        sessions[committed]?.let { mapper.beginRequest(it.request, it.generation) }
        applyStages()
    }

    private fun cancelArmed(mix: NativeTwoDeckMixer) {
        val incoming = scheduler.armedIncomingDeck ?: return
        scheduler.cancel()
        mix.cancel()
        retire(incoming.ordinal)
    }

    /**
     * Stops the audible occurrence. The read before the stop seeds the mapper when no poll has observed the
     * current occurrence yet, which happens right after a load or a handoff commit. Without it the stopped
     * snapshot would be missing and the last published snapshot would keep reporting playback.
     */
    private fun stopSession() {
        val mix = mixer
        if (mix != null) {
            cancelArmed(mix)
            mapper.observe(mix.deck(activeIndex()).snapshot(), clock.nowNanos())
            retire(activeIndex())
        }
        mapper.stopped(clock.nowNanos())?.let { publishedSnapshot.value = it }
    }

    /** Stops one deck and forgets its occurrence, so it holds no decoder and contributes no audio. */
    private fun retire(index: Int) {
        val deck = mixer?.deck(index) ?: return
        deck.stop()
        failureMappers[index].stopped(clock.nowNanos())
        sessions[index] = null
        stallWatchdogs[index].reset()
    }

    private fun openMixer(request: PlaybackRequest): NativeTwoDeckMixer? =
        try {
            output.create(backend).also {
                mixer = it
                // A fresh mixer always starts on its own first deck, which a parked scheduler may not
                // still name: adopt the mixer's deck like [resumeOutput] so [activeIndex] is its deck.
                val timing = it.snapshot()
                committedSequence = timing.commitSequence
                scheduler.adoptActive(
                    TransitionDeck.entries.getOrElse(timing.activeDeck) { TransitionDeck.FIRST },
                )
            }
        } catch (error: VirtualMachineError) {
            throw error
        } catch (error: Throwable) {
            // The loader throws IllegalStateException; a missing JNI symbol throws a LinkageError. Both mean no sound.
            fail(request, PlaybackFailureReason.OUTPUT_FAILED, engineCreateFailureMessage(error))
            null
        }

    /** Applies the stages that are global to the output: bypass, equalizer, then each live deck's gain and tempo. */
    private fun applyStages() {
        val mix = mixer ?: return
        val plan = currentPlan()
        // The mixer drops an armed handoff when bypass engages, so the shared schedule must drop it too. Otherwise
        // the port would keep a handoff armed that can never commit, and refuse every later one.
        if (plan.bypass) cancelArmed(mix)
        mix.setBypass(plan.bypass)
        mix.setEqualizer(doubleArrayOf())
        for (index in 0 until DECK_COUNT) {
            if (sessions[index] != null) applySession(mix, index, plan)
        }
    }

    private fun applySession(mix: NativeTwoDeckMixer, index: Int, plan: SoftwareStagePlan) {
        val session = sessions[index] ?: return
        mix.deck(index).configurePcm(output.settings(dspSettings, stages.bands, session.trackGainDb, session.trackPeak), plan)
        mix.setDeckGain(index, output.gain())
        mix.deck(index).setSpeed(desktopEffectiveSpeed(plan, stages.request))
        mix.deck(index).setPitch(if (plan.tempoPitch) stages.request.pitchSemitones.toFloat() else 0f)
        mix.deck(index).setSkipSilence(plan.silenceSkipping)
    }

    /** The stages that run now: what this host can honour, narrowed by the shared bit-perfect gate. */
    private fun currentPlan(): SoftwareStagePlan = output.plan(stages)

    private fun activeIndex(): Int = scheduler.activeDeck.ordinal

    private fun activeEngine(): NativePlaybackEngine? = mixer?.deck(activeIndex())

    // Internal so a test can drive the guard without a native engine. Every command is guarded, and the poll is
    // rescheduled in a finally so a thrown command can never stop the clock permanently.
    internal fun submitTask(request: PlaybackRequest? = null, task: () -> Unit) {
        if (closed.get()) return
        try {
            executor.execute {
                try {
                    guardedPlaybackCommand(request, commandFailureText,
                        { sessions[activeIndex()]?.request ?: suspendedSession?.request },
                        { failed, reason, message -> fail(failed, reason, message) }, task)
                } finally {
                    // A command can start playback; read at once even when it failed.
                    pollTick()
                }
            }
        } catch (_: RejectedExecutionException) {
            // close() won the race, so the task arrived after the port stopped.
        }
    }

    private fun <T> runOnPlaybackThread(fallback: T, block: () -> T): T {
        if (closed.get()) return fallback
        return try {
            executor.submit(Callable<T> { block().also { pollTick() } }).get()
        } catch (_: RejectedExecutionException) {
            fallback
        } catch (error: ExecutionException) {
            System.err.println("playback command failed: ${error.cause?.message}")
            fallback
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            fallback
        }
    }

    /** Runs on [executor]. Replaces any pending read, so a command-driven read and the timed one never pile up. */
    private fun schedulePoll(delayMs: Long) {
        if (closed.get()) return
        nextPoll?.cancel(false)
        nextPoll = try {
            executor.schedule(::pollTick, delayMs, TimeUnit.MILLISECONDS)
        } catch (_: RejectedExecutionException) {
            null
        }
    }

    private fun pollTick() {
        val busy = pollEngine()
        schedulePoll(if (busy) activePollIntervalMs else idlePollIntervalMs)
    }

    /**
     * Runs on [executor]. A throw here would stop the reads, so errors are logged and skipped. Every deck's failure is
     * checked, because a preloaded occurrence must report its own failure.
     *
     * Returns true while a deck is playing or buffering, the states whose position, handoff and failure reads must stay
     * prompt. A failed read counts as busy, so an error never slows the next attempt.
     */
    private fun pollEngine(): Boolean {
        val mix = mixer ?: return false
        return try {
            syncHandoff()
            if (output.refresh(mix)) applyStages()
            output.failureReason()?.let { reason ->
                sessions[activeIndex()]?.let { failed ->
                    stopSession()
                    fail(failed.request, PlaybackFailureReason.OUTPUT_FAILED, reason)
                    return false
                }
            }
            val now = clock.nowNanos()
            val timing = mix.snapshot()
            trackTimeline(timing.timelineFrames, now)
            mapper.observe(mix.deck(activeIndex()).snapshot(), now)?.let { publishedSnapshot.value = it }
            var busy = false
            for (index in 0 until DECK_COUNT) {
                val engine = mix.deck(index)
                val state = engine.snapshot()
                failureMappers[index].failure(
                    state,
                    httpStatus = { sessions[index]?.lease?.failureStatus() },
                ) {
                    // The lease knows the HTTP status or expiry the native code collapsed into a generic read error.
                    val detail = sessions[index]?.lease?.failureDetail()
                    if (detail == null) engine.lastError() else "${engine.lastError()} ($detail)"
                }?.let { publishedFailures.tryEmit(it) }
                watchStall(mix, index, timing, state, now)
                // A stall recovery rebuilt the output under this poll; the snapshot's decks are dead now.
                if (mixer !== mix) return true
                if (state.hasSource && (state.isBuffering || (state.playWhenReady && !state.isEnded))) busy = true
            }
            busy
        } catch (error: VirtualMachineError) {
            throw error
        } catch (error: Throwable) {
            // A LinkageError must not stop the reads. Class name only: a native message can carry a path.
            System.err.println("playback poll skipped: ${error.javaClass.simpleName}")
            true
        }
    }

    /** The device-consumed frame count is the output's liveness; remember when it last moved. */
    private fun trackTimeline(frames: Long, nowNanos: Long) {
        if (frames != lastTimelineFrames) {
            lastTimelineFrames = frames
            timelineProgressedAtNanos = nowNanos
        }
    }

    /**
     * Feeds one deck read to its stall watchdog. The active deck is expected to make progress
     * whenever it has a source and play was requested; a preloaded deck only once the schedule
     * says its handoff has started, since the mixer never pulls it before then.
     *
     * A stalled incoming deck is dropped with its handoff: the outgoing song keeps the output and
     * the queue treats the blend as never armed. A stalled active deck goes to [onActiveStall].
     */
    private fun watchStall(
        mix: NativeTwoDeckMixer,
        index: Int,
        timing: TwoDeckMixerState,
        state: EnginePlaybackState,
        nowNanos: Long,
    ) {
        if (sessions[index] == null) return
        val active = index == activeIndex()
        val expectProgress = state.hasSource && state.playWhenReady && !state.isEnded &&
            state.errorCode == EnginePlaybackState.ERROR_NONE &&
            (active || run {
                val plan = scheduler.armedPlan ?: return@run false
                scheduler.armedIncomingDeck?.ordinal == index && timing.timelineFrames >= plan.startFrame
            })
        if (!stallWatchdogs[index].observe(state, expectProgress, nowNanos)) return
        if (active) {
            onActiveStall(mix, index, state, nowNanos)
        } else {
            System.err.println("armed handoff dropped: the incoming deck made no progress")
            cancelArmed(mix)
        }
    }

    /**
     * The audible deck rendered nothing for the whole stall window. When the device timeline also
     * stopped, the output itself is dead and the mixer is rebuilt once under this load; a frozen
     * position under a running timeline is a decode or source stall, which the queue recovery path
     * handles from the same failure it handles a failed read with.
     */
    private fun onActiveStall(
        mix: NativeTwoDeckMixer,
        index: Int,
        state: EnginePlaybackState,
        nowNanos: Long,
    ) {
        val session = sessions[index] ?: return
        val timelineStalled =
            nowNanos - timelineProgressedAtNanos >= PlaybackProgressWatchdog.DEFAULT_TIMEOUT_MS * NANOS_PER_MILLI
        if (!timelineStalled) {
            fail(
                session.request,
                PlaybackFailureReason.DECODE_FAILED,
                "the deck made no audible progress within the stall window",
            )
            return
        }
        recoverOutput(mix, index, session, state, nowNanos)
    }

    /**
     * Parks the output device: stops the mixer and keeps the paused occurrence's session and
     * position so [resumeOutput] can reopen it. Only a paused, seekable, still-loaded occurrence
     * parks; a non-seekable live stream keeps the device because its parked position could not be
     * honoured on reopen. The published snapshot stays the paused occurrence: this frees hardware.
     */
    private fun suspendOutput(nowNanos: Long) {
        if (suspendedSession != null) return
        val mix = mixer ?: return
        val index = activeIndex()
        val session = sessions[index] ?: return
        val state = mix.deck(index).snapshot()
        if (!state.hasSource || state.playWhenReady || state.isBuffering || state.isEnded || !state.isSeekable) return
        // A prepared plaintext handle is single-use: the original load already spent it, so parking it
        // would strand the resume on a source it cannot reopen. It keeps the device instead.
        if (session.request.locator is PlaybackLocator.PreparedPlaintext) return
        // A transition never outlives the device that carries it; the queue arms a fresh one on resume.
        cancelArmed(mix)
        suspendedSession = session
        suspendedPositionUs = state.positionUs.coerceAtLeast(0L)
        sessions[index] = null
        failureMappers[index].stopped(nowNanos)
        stallWatchdogs[index].reset()
        lastTimelineFrames = NO_TIMELINE
        mix.close()
        mixer = null
        outputSuspendedFlow.value = true
    }

    /**
     * Reopens the device for a parked occurrence, reloaded paused at its parked position; the
     * waking command applies right after. A device that cannot open reports once and stays parked
     * for the next attempt; a locator that cannot reopen fails like a failed load.
     */
    private fun resumeOutput() {
        val session = suspendedSession ?: return
        val positionUs = suspendedPositionUs
        val fresh = try {
            output.create(backend)
        } catch (error: VirtualMachineError) {
            throw error
        } catch (error: Throwable) {
            fail(session.request, PlaybackFailureReason.OUTPUT_FAILED, engineCreateFailureMessage(error))
            return
        }
        suspendedSession = null
        outputSuspendedFlow.value = false
        mixer = fresh
        output.prepareTrack(fresh)
        val timing = fresh.snapshot()
        committedSequence = timing.commitSequence
        scheduler.adoptActive(TransitionDeck.entries.getOrElse(timing.activeDeck) { TransitionDeck.FIRST })
        val index = activeIndex()
        val source = engineSourceOpen(session.request.locator, telegramFiles)
        if (source == null) {
            stopSession()
            val (reason, message) = refusalOf(session.request.locator)
            fail(session.request, reason, message)
            return
        }
        val generation = fresh.deck(index).load(source, positionUs, playWhenReady = false)
        if (generation == REJECTED_LOAD) {
            stopSession()
            fail(session.request, PlaybackFailureReason.OPEN_FAILED, "the engine rejected the source")
            return
        }
        sessions[index] = session.copy(generation = generation, lease = source.leaseOrNull())
        failureMappers[index].beginRequest(session.request, generation)
        mapper.beginRequest(session.request, generation)
        rebuiltForLoadGeneration = NO_LOAD
        stallWatchdogs[index].reset()
        applyStages()
    }

    /**
     * Reopens the output device and resumes the stalled occurrence where it froze. The device is
     * the only thing rebuilt: the scheduler, the session bookkeeping and the occurrence all stay,
     * so a dead or replaced output device is recovered rather than "playing" into silence forever,
     * and an output that cannot open again is reported rather than silently abandoned.
     *
     * Only the active deck is restored: an armed incoming deck is dropped by [cancelArmed], and the
     * queue arms a fresh one. One rebuild per load generation; a second stall under a rebuilt load
     * goes to the failure path, which skips or stops with a visible error.
     */
    private fun recoverOutput(
        mix: NativeTwoDeckMixer,
        index: Int,
        session: DeckSession,
        state: EnginePlaybackState,
        nowNanos: Long,
    ) {
        if (rebuiltForLoadGeneration == session.generation) {
            fail(
                session.request,
                PlaybackFailureReason.OUTPUT_FAILED,
                "the audio output still does not progress after it was reopened",
            )
            return
        }
        val positionUs = state.positionUs.coerceAtLeast(0L)
        val playWhenReady = state.playWhenReady
        cancelArmed(mix)
        mix.close()
        mixer = null
        lastTimelineFrames = NO_TIMELINE
        // The decks died with their mixer; only the bookkeeping remains, so no deck is stopped here.
        sessions[index] = null
        failureMappers[index].stopped(nowNanos)
        stallWatchdogs[index].reset()
        val fresh = try {
            output.create(backend)
        } catch (error: VirtualMachineError) {
            throw error
        } catch (error: Throwable) {
            mapper.stopped(nowNanos)?.let { publishedSnapshot.value = it }
            fail(session.request, PlaybackFailureReason.OUTPUT_FAILED, engineCreateFailureMessage(error))
            return
        }
        mixer = fresh
        val timing = fresh.snapshot()
        committedSequence = timing.commitSequence
        scheduler.adoptActive(TransitionDeck.entries.getOrElse(timing.activeDeck) { TransitionDeck.FIRST })
        val deckIndex = activeIndex()
        val source = engineSourceOpen(session.request.locator, telegramFiles)
        if (source == null) {
            stopSession()
            val (reason, message) = refusalOf(session.request.locator)
            fail(session.request, reason, message)
            return
        }
        val generation = fresh.deck(deckIndex).load(source, positionUs, playWhenReady)
        if (generation == REJECTED_LOAD) {
            stopSession()
            fail(session.request, PlaybackFailureReason.OPEN_FAILED, "the engine rejected the source")
            return
        }
        sessions[deckIndex] = session.copy(generation = generation, lease = source.leaseOrNull())
        failureMappers[deckIndex].beginRequest(session.request, generation)
        mapper.beginRequest(session.request, generation)
        rebuiltForLoadGeneration = generation
        stallWatchdogs[deckIndex].reset()
        applyStages()
        System.err.println("audio output reopened after it stalled; playback resumed")
    }

    private fun fail(request: PlaybackRequest, reason: PlaybackFailureReason, message: String) {
        publishedFailures.tryEmit(PlaybackFailure(request.occurrenceId, reason, message, attemptId = request.attemptId))
    }

    private companion object {
        const val DEFAULT_POLL_INTERVAL_MS = 20L

        /* Paused or idle: a few reads a second still catch a preload failure or an external stop promptly. */
        const val IDLE_POLL_INTERVAL_MS = 250L
        const val FAILURE_BUFFER = 16
        const val DECK_COUNT = 2
        const val MICROS_PER_MILLI = 1_000L
        const val NANOS_PER_MILLI = 1_000_000L
        const val NO_LOAD = -1L
        const val NO_TIMELINE = -1L
        const val MILLIS_PER_SECOND = 1_000.0
        const val MICROS_PER_SECOND = 1_000_000.0
        const val REJECTED_LOAD = 0L

        /* Long enough for a normal device teardown, short enough that a stuck backend cannot hold window shutdown. */
        const val CLOSE_TIMEOUT_MS = 2_000L

        /* Each entry is one conclusive verdict. The bound keeps memory fixed across a long session. */
        const val SPECTRAL_CACHE_CAPACITY = 32
    }
}
