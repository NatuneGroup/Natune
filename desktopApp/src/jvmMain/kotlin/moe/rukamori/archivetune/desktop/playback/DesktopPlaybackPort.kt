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
import kotlin.math.ln
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
import moe.rukamori.archivetune.shared.data.stream.NativeRouteOutcome
import moe.rukamori.archivetune.shared.domain.dsp.bitperfect.BitPerfectGate
import moe.rukamori.archivetune.shared.domain.dsp.bitperfect.BitPerfectRouteFacts
import moe.rukamori.archivetune.shared.domain.dsp.bitperfect.SoftwareStagePlan
import moe.rukamori.archivetune.shared.domain.dsp.bitperfect.SoftwareStageRequest
import moe.rukamori.archivetune.shared.domain.dsp.eq.EqualizerBand
import moe.rukamori.archivetune.shared.domain.dsp.normalization.NormalizationGain
import moe.rukamori.archivetune.shared.domain.dsp.spectral.SpectralVerdict
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
 */
class DesktopPlaybackPort(
    private val backend: EngineBackend = EngineBackend.DEFAULT,
    private val clock: MonotonicClock = SystemMonotonicClock,
    pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
    private val accounts: AcceptedAccountReader? = null,
    private val cookies: ScopedCookieSource? = null,
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

    /** Confined to [executor]. Null until the first load opens the output device. */
    private var mixer: NativeTwoDeckMixer? = null

    /** Confined to [executor]. The last mixer commit this port has adopted. */
    private var committedSequence = 0L

    /** Confined to [executor]. The software stages the user asked for, before the bit-perfect gate narrows them. */
    private var stages = StageConfig()

    /** Confined to [executor]. The listener's output volume, applied on top of each deck's normalization gain. */
    private var outputVolume = 1f

    /*
     * The spectral check owns its own IO and default dispatchers and never waits on [executor], so it is driven
     * from the caller's coroutine. Only conclusive results are cached, and only under the source they measured.
     */
    private val spectral = DesktopSpectralAuthenticity()
    private val spectralCache = SourceBoundSpectralCache(SPECTRAL_CACHE_CAPACITY)

    /**
     * The signed-in Telegram session's TDLib calls and accepted-account store, read on the playback thread when a Telegram
     * track opens. Null until the host binds a session, so a Telegram track is refused rather than read through another route.
     */
    @Volatile
    private var telegramFiles: TelegramFileProvider? = null

    override val snapshots: StateFlow<PlaybackSnapshot?> = publishedSnapshot.asStateFlow()
    override val failures: SharedFlow<PlaybackFailure> = publishedFailures.asSharedFlow()

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
        submitTask { applyCommand(command) }
    }

    /**
     * Sets the output volume, 0 to 1, for every live deck and for any deck loaded later. It scales the deck gain, so a
     * transition fade and normalization keep their own shape under it. While the bit-perfect route bypasses the mixer
     * the level does not alter samples, as on Android, where the device volume controls that route.
     */
    fun setVolume(volume: Float) {
        require(volume.isFinite() && volume in 0f..1f) { "volume must be between 0 and 1, was $volume" }
        submitTask {
            outputVolume = volume
            applyStages()
        }
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

    /**
     * Turns the equalizer on or off and sets its bands, for the playing occurrence and every later one. The other
     * software stages and the route facts stay as [configureSoftwareStages] left them. A live drag calls this once per
     * move: the band count stays fixed, so the mixer keeps its filter history and a drag through 0 dB does not click.
     */
    fun setEqualizer(enabled: Boolean, bands: List<EqualizerBand>) {
        require(bands.size <= NativeTwoDeckMixer.MAX_BANDS) {
            "the equalizer has ${bands.size} bands; the mixer holds ${NativeTwoDeckMixer.MAX_BANDS}"
        }
        submitTask {
            stages = stages.copy(request = stages.request.copy(equalizerEnabled = enabled), bands = bands)
            applyStages()
        }
    }

    /**
     * Opt-in spectral evidence for the source at [locator], measured from that source's own decoded PCM at
     * [startPositionMs]. It never analyses engine output, which is resampled to the device rate, and it never
     * touches a deck, the mixer or the playback executor, so it cannot change the audible output or the bit-perfect
     * state.
     *
     * A conclusive verdict is cached under the engine source and start position, so it applies only to that exact
     * source. An inconclusive one is not cached, so a later request retries it. Cancelling the caller stops the
     * native read, and a cancelled check caches nothing.
     */
    suspend fun assessSpectrum(locator: PlaybackLocator, startPositionMs: Long = 0L): DesktopSpectralEvidence {
        val source = engineSourceOf(locator) ?: return spectral.assess(locator, startPositionMs)
        val key = SpectralCacheKey(source, startPositionMs)
        spectralCache.get(key)?.let { return it }
        val evidence = spectral.assess(locator, startPositionMs)
        if (evidence.assessment.verdict != SpectralVerdict.UNKNOWN) spectralCache.put(key, evidence)
        return evidence
    }

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
            is PlaybackCommand.Load -> load(command)
            PlaybackCommand.Play -> activeEngine()?.play()
            PlaybackCommand.Pause -> {
                settleArmed()
                activeEngine()?.pause()
            }
            is PlaybackCommand.SeekTo -> {
                settleArmed()
                activeEngine()?.seek(command.positionMs * MICROS_PER_MILLI)
            }
            is PlaybackCommand.SetSpeed -> {
                stages = stages.copy(request = stages.request.copy(speed = command.speed.toDouble()))
                applyStages()
            }
            is PlaybackCommand.SetPitch -> {
                stages = stages.copy(
                    request = stages.request.copy(
                        pitchSemitones = 12.0 * ln(command.pitch.toDouble()) / ln(2.0)
                    )
                )
                applyStages()
            }
            PlaybackCommand.Stop -> {
                // The shared scheduler promotes the incoming deck on stop, so the stopped occurrence is the one
                // the listener now hears, not the fade's outgoing deck.
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
        val generation = mix.deck(index).load(source, command.startPositionMs * MICROS_PER_MILLI, command.playWhenReady)
        if (generation == REJECTED_LOAD) {
            stopSession()
            fail(request, PlaybackFailureReason.OPEN_FAILED, "the engine rejected the source")
            return
        }
        sessions[index] = DeckSession(request, generation)
        failureMappers[index].beginRequest(request, generation)
        mapper.beginRequest(request, generation)
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
        sessions[incomingIndex] = DeckSession(next, generation, trackGainDb, trackPeak)
        failureMappers[incomingIndex].beginRequest(next, generation)
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
    }

    private fun openMixer(request: PlaybackRequest): NativeTwoDeckMixer? =
        try {
            NativeTwoDeckMixer.create(backend).also {
                mixer = it
                committedSequence = it.snapshot().commitSequence
            }
        } catch (error: IllegalStateException) {
            fail(request, PlaybackFailureReason.OUTPUT_FAILED, error.message ?: "the audio output did not start")
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
        mix.setEqualizer(equalizerCoefficients(mix.sampleRateHz, if (plan.equalizer) stages.bands else emptyList()))
        for (index in 0 until DECK_COUNT) {
            if (sessions[index] != null) applySession(mix, index, plan)
        }
    }

    private fun applySession(mix: NativeTwoDeckMixer, index: Int, plan: SoftwareStagePlan) {
        val session = sessions[index] ?: return
        mix.setDeckGain(index, gainFor(plan, session.trackGainDb, session.trackPeak))
        mix.deck(index).setSpeed(effectiveSpeed(plan))
    }

    /**
     * The stages that run now. The bit-perfect gate is given what this port actually does to samples, so a
     * request cannot bypass the software chain while an equalizer or normalization is engaged.
     */
    private fun currentPlan(): SoftwareStagePlan {
        val config = stages
        val equalizerEngaged = config.request.equalizerEnabled && config.bands.any { it.gainDb != 0.0 }
        val samplesAltered = equalizerEngaged ||
            config.request.normalizationEnabled ||
            config.request.speed != 1.0 ||
            config.request.pitchSemitones != 0.0
        val decision = BitPerfectGate.evaluate(config.routeFacts.copy(softwareDspEngaged = samplesAltered))
        return SoftwareStagePlan.resolve(decision, config.request)
    }

    /** The engine's tempo is the playback speed, and the shared plan keeps tempo off whenever the route is requested. */
    private fun effectiveSpeed(plan: SoftwareStagePlan): Float =
        if (plan.tempoPitch) stages.request.speed.toFloat() else 1f

    private fun gainFor(plan: SoftwareStagePlan, trackGainDb: Double?, trackPeak: Double?): Float =
        outputVolume * if (plan.normalization) NormalizationGain.linearGain(trackGainDb, trackPeak) else 1f

    private fun activeIndex(): Int = scheduler.activeDeck.ordinal

    private fun activeEngine(): NativePlaybackEngine? = mixer?.deck(activeIndex())

    private fun submitTask(task: () -> Unit) {
        if (closed.get()) return
        try {
            executor.execute {
                task()
                // A command can start playback, so read at once and return to the fast rate without waiting out an idle delay.
                pollTick()
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
            val now = clock.nowNanos()
            mapper.observe(mix.deck(activeIndex()).snapshot(), now)?.let { publishedSnapshot.value = it }
            var busy = false
            for (index in 0 until DECK_COUNT) {
                val engine = mix.deck(index)
                val state = engine.snapshot()
                failureMappers[index].failure(state) { engine.lastError() }
                    ?.let { publishedFailures.tryEmit(it) }
                if (state.hasSource && (state.isBuffering || (state.playWhenReady && !state.isEnded))) busy = true
            }
            busy
        } catch (error: RuntimeException) {
            System.err.println("playback poll skipped: ${error.message}")
            true
        }
    }

    private fun fail(request: PlaybackRequest, reason: PlaybackFailureReason, message: String) {
        publishedFailures.tryEmit(PlaybackFailure(request.occurrenceId, reason, message, attemptId = request.attemptId))
    }

    /**
     * Why [locator] would not open. A prepared view that is spent, expired or from another host is an open failure,
     * because the remedy is to prepare the occurrence again. Any other locator is one this engine does not take.
     */
    private fun refusalOf(locator: PlaybackLocator): Pair<PlaybackFailureReason, String> =
        if (locator is PlaybackLocator.PreparedPlaintext) {
            PlaybackFailureReason.OPEN_FAILED to
                "the prepared audio is spent, expired or not from this host; prepare it again"
        } else {
            PlaybackFailureReason.UNSUPPORTED_LOCATOR to "the engine cannot open this kind of source"
        }

    private companion object {
        const val DEFAULT_POLL_INTERVAL_MS = 20L

        /* Paused or idle: a few reads a second still catch a preload failure or an external stop promptly. */
        const val IDLE_POLL_INTERVAL_MS = 250L
        const val FAILURE_BUFFER = 16
        const val DECK_COUNT = 2
        const val MICROS_PER_MILLI = 1_000L
        const val MILLIS_PER_SECOND = 1_000.0
        const val MICROS_PER_SECOND = 1_000_000.0
        const val REJECTED_LOAD = 0L

        /* Long enough for a normal device teardown, short enough that a stuck backend cannot hold window shutdown. */
        const val CLOSE_TIMEOUT_MS = 2_000L

        /* Each entry is one conclusive verdict. The bound keeps memory fixed across a long session. */
        const val SPECTRAL_CACHE_CAPACITY = 32
    }
}

/**
 * The occurrence a deck plays. [trackGainDb] and [trackPeak] describe it for normalization, and are null
 * when the caller has not supplied them, which leaves the track at unity gain.
 */
private data class DeckSession(
    val request: PlaybackRequest,
    val generation: Long,
    val trackGainDb: Double? = null,
    val trackPeak: Double? = null,
)

/**
 * Plain playback runs no software stage. The route is unverified, so bit-perfect is never claimed for it.
 * The native output reports no route facts, so the sample rate here is only a placeholder that yields no
 * resampler stage; the engine resamples every source to its output rate inside its own graph.
 */
private data class StageConfig(
    val request: SoftwareStageRequest = PLAIN_REQUEST,
    val bands: List<EqualizerBand> = emptyList(),
    val routeFacts: BitPerfectRouteFacts = UNVERIFIED_ROUTE,
)

private val PLAIN_REQUEST = SoftwareStageRequest(
    normalizationEnabled = false,
    equalizerEnabled = false,
    speed = 1.0,
    pitchSemitones = 0.0,
    silenceSkippingEnabled = false,
    twoDeckMixingEnabled = true,
)

private val UNVERIFIED_ROUTE = BitPerfectRouteFacts(
    bitPerfectRequested = false,
    softwareDspEngaged = false,
    usbExclusive = false,
    directPlaybackQueried = false,
    directPlaybackSupported = false,
    inputSampleRateHz = 48_000,
    inputBitDepth = 32,
)
