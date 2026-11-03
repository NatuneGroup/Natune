@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package moe.rukamori.archivetune.shared.data.platform.ios

import kotlin.time.Clock
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.flow.*
import cnames.structs.at_audio_output
import moe.rukamori.archivetune.nativeaudio.*
import moe.rukamori.archivetune.shared.data.bytesource.ByteSourceAccount
import moe.rukamori.archivetune.shared.data.bytesource.ByteSourceLease
import moe.rukamori.archivetune.shared.data.bytesource.ScopedCookieSource
import moe.rukamori.archivetune.shared.data.bytesource.StreamOrigin
import moe.rukamori.archivetune.shared.data.playback.EnginePlaybackMapper
import moe.rukamori.archivetune.shared.data.playback.EnginePlaybackState
import moe.rukamori.archivetune.shared.data.playback.PlaybackProgressWatchdog
import moe.rukamori.archivetune.shared.data.playback.outputLatencyMillis
import moe.rukamori.archivetune.shared.data.stream.NativePlaintext
import moe.rukamori.archivetune.shared.data.stream.hls.HlsSegmentedSource
import moe.rukamori.archivetune.shared.data.stream.hls.isHlsPlaylistUrl
import moe.rukamori.archivetune.shared.data.stream.nativePlaintext
import moe.rukamori.archivetune.shared.domain.platform.ANONYMOUS_ACCOUNT_SCOPE
import moe.rukamori.archivetune.shared.domain.platform.AcceptedAccountReader
import moe.rukamori.archivetune.shared.domain.playback.*
import moe.rukamori.archivetune.shared.domain.repository.PreparedAudio
import moe.rukamori.archivetune.shared.domain.repository.toPlaybackLocator
import moe.rukamori.archivetune.shared.domain.source.DirectStream
import moe.rukamori.archivetune.shared.domain.source.toPlaybackLocator
import platform.Foundation.NSURL

/** The message for account-scoped audio before the account store is attached. The store is never defaulted. */
private const val ACCOUNTS_NOT_ATTACHED =
    "account-scoped audio needs the account store, which this host has not attached"

/** The lease account of an anonymous segmented stream, matching the anonymous header-bearing lease. */
private const val SEGMENTED_PROVIDER = "playback"

/** The MIME a segmented view reports before its first payload bytes are classified. */
private const val SEGMENTED_UNKNOWN_MIME = "application/octet-stream"

/**
 * One main-queue command owner over the same portable decoder used on desktop.
 * The native AVAudioSourceNode consumes real PCM; Kotlin never runs on its real-time callback.
 * Requires the cinterop/static native link recorded in the M9 integration requests.
 */
class IosPlaybackPort : PlaybackPort {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val inbox = Channel<Inbound>(64)
    private val state = MutableStateFlow<PlaybackSnapshot?>(null)
    private val errors = MutableSharedFlow<PlaybackFailure>(extraBufferCapacity = 16)
    private val mapper = EnginePlaybackMapper()
    private var output: CPointer<at_audio_output>? = null
    private var blend: IosPlaybackBlend? = null
    private var handoffEpoch = 0L
    private var request: PlaybackRequest? = null
    private var reopenOutput = false
    private var closed = false

    // Main-queue only. A source that should be rendering but has not moved is a stall: the watchdog
    // fires once per episode and [onStall] answers it with at most one re-prepare per load session.
    private val stallWatchdog = PlaybackProgressWatchdog()
    private var requestRepreparable = false
    private var stallRecoveryUsed = false

    // Main-queue only. The app-level output level applied to every output, including one recreated after a fault.
    private var volume = 1f

    // Main-queue only. The tempo every output loads with: a SetSpeed that finds no live output is held
    // here instead of being dropped, and outputForLoad applies it to whatever it creates or recreates.
    private var desiredSpeed = 1f

    // Main-queue state for host-prepared audio. Every Load, Stop and preparation advances [ticket]. A preparation whose
    // ticket no longer matches when it returns is discarded with its transport, so a superseded occurrence never plays.
    private var ticket = 0L
    private var preparing: Job? = null
    private var liveTransport: IosCiphertextOpener? = null
    private var route: IosNativeAudioRoute? = null
    private var accounts: AcceptedAccountReader? = null
    private var telegram: IosTelegramClient? = null

    // Main-queue only. Audio the queue prepared ahead of its Load, by occurrence, until that Load claims it. Insertion
    // order is the age order, so the oldest views are released first when the bound is reached.
    private val queuedViews = LinkedHashMap<String, QueuedView>()

    // Wakes the periodic read after a command, so a resume returns to the fast rate without waiting out the idle delay.
    private val pollWake = Channel<Unit>(Channel.CONFLATED)

    override val snapshots: StateFlow<PlaybackSnapshot?> = state.asStateFlow()
    override val failures: SharedFlow<PlaybackFailure> = errors.asSharedFlow()

    /**
     * Every command the loop accepted, with its origin and a monotonic id, in submit order. The
     * handoff is an unbounded channel: accepting a command can never block on a slow consumer, and
     * no listener intent is ever dropped. There is one consumer, the output-mute policy.
     */
    private val commandEventChannel = Channel<PlaybackCommandEvent>(Channel.UNLIMITED)
    val commandEvents: Flow<PlaybackCommandEvent> = commandEventChannel.receiveAsFlow()
    private var commandSequence = 0L

    /**
     * How many USER Pause/Stop/Play/Load commands the loop has dispatched. A policy submit tags the
     * count it observed, and the loop drops it when the count has moved — so a policy command can
     * never apply over a user command that was queued first.
     */
    val userIntentGeneration: Long get() = intentGeneration
    private var intentGeneration = 0L

    init {
        scope.launch {
            for (item in inbox) {
                when (item) {
                    is Inbound.Command -> {
                        // A policy command whose tagged intent generation is stale: the listener
                        // queued a Pause, Stop, Play or Load ahead of it, so it must not apply.
                        val stalePolicy = item.origin == PlaybackCommandOrigin.POLICY &&
                            outputMuteIntentIsStale(item.intentGeneration, intentGeneration)
                        if (!stalePolicy) {
                            if (item.origin == PlaybackCommandOrigin.USER && tracksUserIntent(item.command)) {
                                intentGeneration++
                            }
                            // The notice carries the generation as it stands after this dispatch:
                            // a policy tags its commands with the newest USER generation it has seen.
                            commandEventChannel.trySend(
                                PlaybackCommandEvent(item.command, item.origin, ++commandSequence, intentGeneration),
                            )
                            apply(item.command)
                        }
                    }
                    is Inbound.Prepare -> beginPreparation(item)
                    is Inbound.Prepared -> applyPrepared(item)
                    is Inbound.TelegramResolved -> applyTelegram(item)
                }
                observe()
                pollWake.trySend(Unit)
            }
        }
        scope.launch {
            while (isActive) {
                val busy = observe()
                // Paused or idle reads drop to four a second, so the main thread is not woken twenty times a second for nothing.
                val interval = if (!busy) IDLE_POLL_MS else if (blend == null) ACTIVE_POLL_MS else BLEND_POLL_MS
                withTimeoutOrNull(interval) { pollWake.receive() }
            }
        }
    }

    override fun submit(command: PlaybackCommand) =
        submit(command, PlaybackCommandOrigin.USER, intentGeneration)

    /**
     * A session policy's tagged submit: [observedIntentGeneration] is the USER intent count the
     * policy reduced against, and the loop drops the command when the count has moved — so the
     * policy's own pause and play never read as manual intent on [commandEvents] and never apply
     * over a user command that was queued first.
     */
    fun submitPolicy(command: PlaybackCommand, observedIntentGeneration: Long) =
        submit(command, PlaybackCommandOrigin.POLICY, observedIntentGeneration)

    private fun submit(command: PlaybackCommand, origin: PlaybackCommandOrigin, intentGeneration: Long) {
        val occurrence = (command as? PlaybackCommand.Load)?.request?.occurrenceId ?: state.value?.occurrenceId
        val attempt = if (command is PlaybackCommand.Load) command.request.attemptId else request?.attemptId
        enqueue(Inbound.Command(command, origin, intentGeneration), occurrence, attempt)
    }

    /** Commands that change what the listener asked the engine to do: pause, stop, play, load. */
    private fun tracksUserIntent(command: PlaybackCommand): Boolean = when (command) {
        is PlaybackCommand.Load,
        PlaybackCommand.Play, PlaybackCommand.Pause, PlaybackCommand.Stop,
        -> true
        is PlaybackCommand.SeekTo, is PlaybackCommand.SetSpeed, is PlaybackCommand.SetPitch -> false
    }

    /**
     * Sets the app-level output volume, 0 to 1, on the engine's mixer node for every live output and any output
     * created or recreated later. The system route volume (hardware buttons) stays the OS's, as on Android and
     * desktop where the device controls that route.
     */
    fun setVolume(level: Float) {
        if (!level.isFinite()) return
        scope.launch {
            if (closed) return@launch
            volume = level.coerceIn(0f, 1f)
            output?.let { at_audio_volume(it, volume) }
            blend?.setVolume(volume)
        }
    }

    /**
     * The queue has already resolved this occurrence through the usual source chain. It is admitted/opened using the
     * same leases as Load, while the outgoing decoder keeps its source, position and account-bound transport.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun armNext(next: PlaybackRequest, overlapMs: Long): Boolean {
        var armedEpoch: Long? = null
        try {
            return withContext(Dispatchers.Main) {
                val native = output ?: return@withContext false
                val playing = state.value ?: return@withContext false
                if (closed || inbox.isClosedForSend || overlapMs < 0L || blend?.hasNext == true || !playing.playWhenReady ||
                    playing.isEnded || next.occurrenceId == request?.occurrenceId
                ) return@withContext false
                val issuedAt = handoffEpoch
                val locator = next.locator
                val view = queuedViews.remove(next.occurrenceId)
                var outcome = view?.outcome
                var telegramSource: IosLeaseSource? = null
                var source: IosBlendSource? = null
                try {
                    if (view != null && view.locator != next.locator) {
                        outcome?.release()
                        outcome = null
                    }
                    if (locator is PlaybackLocator.TelegramFile) {
                        val session = telegram ?: return@withContext false
                        val reader = accounts ?: return@withContext false
                        val resolved = resolveTelegramTrack(session, reader, IOS_TELEGRAM_ACCOUNT_SCOPE, locator.track)
                        telegramSource = (resolved as? IosTelegramTrackOutcome.Ready)?.source ?: return@withContext false
                    }
                    currentCoroutineContext().ensureActive()
                    if (closed || issuedAt != handoffEpoch || output != native || blend?.hasNext == true) return@withContext false
                    source = IosBlendSource.open(next, outcome, telegramSource, playing.playbackSpeed)
                    // open consumes both resources, including on refusal.
                    outcome = null
                    telegramSource = null
                    val prepared = source ?: return@withContext false
                    val engine = blend ?: IosPlaybackBlend.open(native, volume)?.also { blend = it }
                        ?: return@withContext false
                    if (!engine.arm(prepared, overlapMs)) return@withContext false
                    source = null
                    armedEpoch = issuedAt
                    pollWake.trySend(Unit)
                    true
                } finally {
                    source?.release()
                    outcome?.release()
                    telegramSource?.close()
                }
            }
        } catch (cancelled: CancellationException) {
            // withContext can deliver cancellation after the arm committed on Main, before its caller got the answer.
            withContext(Dispatchers.Main + NonCancellable) {
                if (armedEpoch != null && armedEpoch == handoffEpoch) blend?.cancelNext(next.occurrenceId)
            }
            throw cancelled
        }
    }

    fun cancelNext() {
        scope.launch {
            handoffEpoch++
            blend?.cancelNext()
        }
    }

    /**
     * The only route from prepared provider audio into this engine. An anonymous progressive stream becomes a remote
     * locator that keeps its headers, and a header-bearing one is read through the host lease. A personal progressive
     * stream is admitted against its account before its first byte, like native audio, and is then read through the
     * lease that names that account. A Deezer stream becomes an encrypted locator, which only the lease can read
     * because it owns the chunk decryptor. A native recipe or TIDAL manifest is admitted against its account, prepared
     * to plaintext by the common route, and played through this port's own transport as a prepared-plaintext locator.
     * A refusal reports its reason for this occurrence and never falls back to a URL, so protected or unsupported audio
     * cannot reach the engine as an ordinary remote stream, and a personal stream never plays anonymously.
     */
    @OptIn(ExperimentalCoroutinesApi::class) // isClosedForSend is experimental in kotlinx.coroutines.
    fun submitPrepared(
        occurrenceId: String,
        mediaId: String,
        prepared: PreparedAudio,
        startPositionMs: Long,
        playWhenReady: Boolean,
    ) {
        if (inbox.isClosedForSend) return
        // Blank identifiers are refused before anything is queued, because the request constructor rejects them by throwing
        // and a throw inside the command loop would stop every later command.
        require(occurrenceId.isNotBlank()) { "occurrenceId must not be blank" }
        require(mediaId.isNotBlank()) { "mediaId must not be blank" }
        // Blank values are refused before conversion, because the locator constructors reject them by throwing.
        val locator: PlaybackLocator? = when (prepared) {
            // A personal stream has no anonymous locator, so it is admitted below instead of being read with no account.
            // A lease-bound HLS playlist is admitted too: the engine cannot expand segments through its lease.
            is PreparedAudio.Progressive ->
                prepared.stream.takeIf { it.uri.isNotBlank() && !it.isPersonal() && !it.needsSegmentedRoute() }
                    ?.toPlaybackLocator()
            is PreparedAudio.DeezerEncrypted -> prepared.takeIf {
                it.stream.uri.isNotBlank() && it.trackId.isNotBlank()
            }?.toPlaybackLocator()
            is PreparedAudio.Native, is PreparedAudio.TidalManifest -> null
        }
        if (locator != null) {
            submit(PlaybackCommand.Load(PlaybackRequest(occurrenceId, mediaId, locator), startPositionMs, playWhenReady))
            return
        }
        val needsAdmission = prepared is PreparedAudio.Native || prepared is PreparedAudio.TidalManifest ||
            (prepared is PreparedAudio.Progressive &&
                (prepared.stream.isPersonal() || prepared.stream.needsSegmentedRoute()))
        if (needsAdmission) {
            enqueue(Inbound.Prepare(occurrenceId, mediaId, prepared, startPositionMs, playWhenReady), occurrenceId)
            return
        }
        submit(PlaybackCommand.Stop)
        errors.tryEmit(PlaybackFailure(occurrenceId, PlaybackFailureReason.UNSUPPORTED_LOCATOR,
            "the stream has no playable address"))
    }

    /**
     * Prepares native, TIDAL or personal progressive audio for one queue occurrence ahead of its Load, under the same
     * account admission as [submitPrepared]. The answer is the locator the queue then loads for that occurrence. Only a
     * Load that names the same occurrence and the same locator claims the prepared view, so a stale or foreign locator is
     * refused exactly as before. A view that no Load claims is released when a newer one replaces it, when the bound is
     * reached, or when the port closes.
     */
    suspend fun prepareForQueue(occurrenceId: String, prepared: PreparedAudio): QueuedPreparation {
        val access = withContext(Dispatchers.Main) { route?.takeUnless { closed } }
            ?: return QueuedPreparation.Refused(ACCOUNTS_NOT_ATTACHED)
        val outcome = access.prepare(prepared)
        val locator = when (outcome) {
            is IosNativeAudioRoute.Outcome.Ready -> PlaybackLocator.PreparedPlaintext(outcome.handle, prepared.source)
            is IosNativeAudioRoute.Outcome.Streamed -> outcome.remote.locator
            is IosNativeAudioRoute.Outcome.Refused -> return QueuedPreparation.Refused(outcome.reason)
            is IosNativeAudioRoute.Outcome.Failed -> return QueuedPreparation.Refused(outcome.reason)
        }
        val accepted = withContext(Dispatchers.Main + NonCancellable) {
            if (closed) return@withContext false
            queuedViews.remove(occurrenceId)?.outcome?.release()
            queuedViews[occurrenceId] = QueuedView(locator, prepared, outcome)
            while (queuedViews.size > MAX_QUEUED_VIEWS) {
                val eldest = queuedViews.keys.first()
                queuedViews.remove(eldest)?.outcome?.release()
            }
            true
        }
        if (!accepted) {
            outcome.release()
            return QueuedPreparation.Refused("iOS playback is closed")
        }
        return QueuedPreparation.Ready(locator)
    }

    /**
     * Supplies the account reader and the scoped cookie source that personal progressive, native and TIDAL audio are
     * admitted against. It is single-shot: the root calls it once, when the vault is ready, with the one store that owns
     * the session. Until then that audio is refused, while the anonymous progressive and Deezer paths are unaffected.
     */
    fun attachAccounts(accounts: AcceptedAccountReader, cookies: ScopedCookieSource) {
        check(!closed && route == null)
        route = IosNativeAudioRoute(accounts, cookies)
        this.accounts = accounts
    }

    /**
     * Supplies the host's one Telegram session. A Telegram track then reads through that session's TDLib file once the
     * attached account store accepts its account. Without it, or before [attachAccounts], a Telegram track is refused.
     * The host owns the session and closes it, so this port never creates or closes one.
     */
    fun attachTelegram(session: IosTelegramClient) {
        check(!closed && telegram == null)
        telegram = session
    }

    override fun close() {
        // Queued results are discarded here, because cancelling the channel drops its elements without returning them. A
        // dropped preparation still holds a transport, so each one is released on the way out.
        while (true) {
            val queued = inbox.tryReceive().getOrNull() ?: break
            (queued as? Inbound.Prepared)?.outcome?.release()
            ((queued as? Inbound.TelegramResolved)?.outcome as? IosTelegramTrackOutcome.Ready)?.source?.close()
        }
        inbox.cancel() // Discard queued commands; a close must not first execute pending loads.
        scope.launch {
            if (closed) return@launch
            closed = true
            queuedViews.values.forEach { it.outcome.release() }
            queuedViews.clear()
            supersede()
            stop()
            output?.let { at_audio_destroy(it) }
            output = null
            scope.cancel()
        }
    }

    /** Queues [item]. A full queue reports the occurrence it could not take, and an unknown occurrence is dropped silently. */
    private fun enqueue(item: Inbound, occurrenceId: String?, attemptId: Long? = null) {
        val result = inbox.trySend(item)
        if (result.isFailure && !result.isClosed && occurrenceId != null) {
            errors.tryEmit(PlaybackFailure(occurrenceId, PlaybackFailureReason.OUTPUT_FAILED,
                "iOS playback command queue is full", attemptId = attemptId))
        }
    }

    private fun apply(command: PlaybackCommand) {
        if (closed) return
        when (command) {
            is PlaybackCommand.Load -> load(command)
            PlaybackCommand.Play -> output?.let {
                if (!(blend?.play() ?: (at_audio_play(it) != 0))) fail(PlaybackFailureReason.OUTPUT_FAILED)
            }
            PlaybackCommand.Pause -> {
                invalidateHandoff()
                blend?.pause() ?: output?.let { at_audio_pause(it) }
            }
            is PlaybackCommand.SeekTo -> {
                invalidateHandoff()
                blend?.seek(micros(command.positionMs)) ?: output?.let { at_audio_seek(it, micros(command.positionMs)) }
            }
            is PlaybackCommand.SetSpeed -> {
                handoffEpoch++
                desiredSpeed = command.speed
                blend?.setSpeed(command.speed) ?: output?.let { at_audio_speed(it, command.speed) }
            }
            // ae_engine only exposes an atempo stage (tempo without pitch shift), so a pitch
            // multiplier cannot be applied here; the command is rejected instead of no-opping.
            is PlaybackCommand.SetPitch -> fail(PlaybackFailureReason.OUTPUT_FAILED)
            PlaybackCommand.Stop -> {
                supersede()
                stop()
            }
        }
    }

    /**
     * Starts the preparation of one host-prepared occurrence. The current output stops at once, as a progressive load
     * does. The result is queued back to this loop, which plays it only if no newer command has superseded it meanwhile.
     */
    private fun beginPreparation(item: Inbound.Prepare) {
        supersede()
        stop()
        val access = route
        if (access == null) {
            errors.tryEmit(PlaybackFailure(item.occurrenceId, PlaybackFailureReason.UNSUPPORTED_LOCATOR,
                ACCOUNTS_NOT_ATTACHED))
            return
        }
        val issuedAt = ticket
        preparing = scope.launch {
            val outcome = access.prepare(item.prepared)
            var queued = false
            try {
                inbox.send(Inbound.Prepared(issuedAt, item, outcome))
                queued = true
            } catch (_: ClosedSendChannelException) {
                // The port closed while the preparation ran, so the transport is released below rather than played.
            } finally {
                if (!queued) outcome.release()
            }
        }
    }

    /** Plays a finished preparation, unless a newer command has superseded it, in which case its transport is released. */
    private fun applyPrepared(item: Inbound.Prepared) {
        if (closed || item.issuedAt != ticket) {
            item.outcome.release()
            return
        }
        val source = item.source
        when (val outcome = item.outcome) {
            is IosNativeAudioRoute.Outcome.Ready -> loadPrepared(source, outcome)
            is IosNativeAudioRoute.Outcome.Streamed -> loadStreamed(source, outcome)
            is IosNativeAudioRoute.Outcome.Refused ->
                errors.tryEmit(PlaybackFailure(source.occurrenceId, PlaybackFailureReason.OPEN_FAILED, outcome.reason))
            is IosNativeAudioRoute.Outcome.Failed ->
                errors.tryEmit(PlaybackFailure(source.occurrenceId, PlaybackFailureReason.OPEN_FAILED, outcome.reason))
        }
    }

    /** Loads one prepared handle into the engine. The handle is consumed here, so a second play needs a fresh preparation. */
    private fun loadPrepared(source: Inbound.Prepare, ready: IosNativeAudioRoute.Outcome.Ready) {
        val transport = ready.transport
        val handle = ready.handle
        val plaintext = handle.nativePlaintext()
        if (plaintext == null) {
            transport.close()
            errors.tryEmit(PlaybackFailure(source.occurrenceId, PlaybackFailureReason.OPEN_FAILED,
                "the prepared audio is not a native plaintext view", attemptId = source.attemptId))
            return
        }
        val expiresAt = handle.expiresAtEpochMs
        if (expiresAt != null && expiresAt <= nowEpochMs()) {
            transport.close()
            errors.tryEmit(PlaybackFailure(source.occurrenceId, PlaybackFailureReason.OPEN_FAILED,
                "the prepared audio authorization expired; prepare it again", attemptId = source.attemptId))
            return
        }
        stop()
        val playable = PlaybackRequest(
            source.occurrenceId, source.mediaId, PlaybackLocator.PreparedPlaintext(handle, source.prepared.source),
            attemptId = source.attemptId,
        )
        request = playable
        val native = outputForLoad()
        if (native == null) {
            transport.close()
            fail(PlaybackFailureReason.OUTPUT_FAILED)
            return
        }
        val generation = IosStreamLease.loadPlaintext(
            native,
            IosPlaintextSource(transport, plaintext),
            micros(source.startPositionMs),
            if (source.playWhenReady) 1 else 0,
        )
        if (generation == 0uL) {
            // An accepted lease is closed by the engine itself, and closing again is harmless. This also covers a refusal
            // that never reached the engine, so the transport is never left open.
            transport.close()
            fail(PlaybackFailureReason.OPEN_FAILED, openFailureMessage(isNetworkOpen = true))
            return
        }
        liveTransport = transport
        mapper.beginRequest(playable, generation.toLong())
    }

    /**
     * Loads one admitted personal stream. Its lease names the account it was admitted under, so the engine reads it
     * with that account's cookies, and every request of the lease, the first open included, is checked against the
     * same entry before it leaves.
     */
    private fun loadStreamed(source: Inbound.Prepare, streamed: IosNativeAudioRoute.Outcome.Streamed) {
        val remote = streamed.remote
        stop()
        val playable = PlaybackRequest(source.occurrenceId, source.mediaId, remote.locator, source.attemptId)
        request = playable
        val native = outputForLoad()
        if (native == null) {
            fail(PlaybackFailureReason.OUTPUT_FAILED)
            return
        }
        val generation = IosStreamLease.loadAdmitted(
            native,
            remote,
            micros(source.startPositionMs),
            if (source.playWhenReady) 1 else 0,
        )
        if (generation == 0uL) {
            // The engine refused the lease before any connection was opened, so there is nothing to release here.
            fail(PlaybackFailureReason.OPEN_FAILED, openFailureMessage(isNetworkOpen = true))
            return
        }
        mapper.beginRequest(playable, generation.toLong())
    }

    private fun load(command: PlaybackCommand.Load) {
        supersede()
        val locator = command.request.locator
        queuedViews.remove(command.request.occurrenceId)?.let { view ->
            if (view.locator == locator) {
                loadQueued(command, view)
                return
            }
            view.outcome.release()
        }
        if (locator is PlaybackLocator.TelegramFile) {
            beginTelegram(command, locator)
            return
        }
        val source = when (locator) {
            is PlaybackLocator.LocalFile -> locator.path.takeIf { it.startsWith("/") && '\u0000' !in it }
            is PlaybackLocator.Remote -> locator.url.takeIf { isWebUrl(it) }
            // The Deezer URL is checked like a remote one. The engine never opens it: only the lease below decrypts it.
            is PlaybackLocator.DeezerEncrypted -> locator.url.takeIf { isWebUrl(it) }
            // A TDLib file has no URL. It is admitted and read through its lease in beginTelegram, never opened here.
            is PlaybackLocator.TelegramFile -> null
            // Host-prepared plaintext owns a transport, so it enters only through submitPrepared, never a bare command.
            is PlaybackLocator.PreparedPlaintext -> null
        }
        stop()
        request = command.request
        // Only this plain path can re-issue a locator on a stall, so only it marks the request repreparable.
        requestRepreparable = true
        if (source == null) {
            fail(PlaybackFailureReason.UNSUPPORTED_LOCATOR)
            return
        }
        val native = outputForLoad()
        if (native == null) {
            fail(PlaybackFailureReason.OUTPUT_FAILED)
            return
        }
        val startUs = micros(command.startPositionMs)
        val play = if (command.playWhenReady) 1 else 0
        // Known expiry needs the lease even without headers; the engine's own opener cannot enforce that window.
        // Deezer ciphertext and header-bearing remotes also stay under the lease policy.
        val generation = when {
            locator is PlaybackLocator.DeezerEncrypted -> IosStreamLease.loadDeezer(native, locator, startUs, play)
            // A lease-bound HLS playlist: the engine's lease refuses child fetches, so the host expands the segments.
            locator is PlaybackLocator.Remote && isHlsPlaylistUrl(locator.url) &&
                (!locator.headers.isEmpty() || locator.expiresAtEpochMs != null ||
                    IosStreamProxyRouting.mustProxy()) ->
                loadSegmentedHls(native, locator, startUs, play)
            // Header- and expiry-free remotes keep the engine's own opener only while no proxy route or
            // rotation applies: a live stream route means the lease transport, so playlist expansions and
            // progressive streams alike leave through the proxy the settings chose — and a rotation with no
            // member fails the open here rather than leaking it direct.
            locator is PlaybackLocator.Remote && (!locator.headers.isEmpty() || locator.expiresAtEpochMs != null ||
                IosStreamProxyRouting.mustProxy()) ->
                IosStreamLease.loadRemote(native, locator, startUs, play)
            else -> at_audio_load(native, source, startUs, play)
        }
        if (generation == 0uL) {
            fail(
                PlaybackFailureReason.OPEN_FAILED,
                openFailureMessage(
                    locator is PlaybackLocator.Remote || locator is PlaybackLocator.DeezerEncrypted,
                ),
            )
            return
        }
        mapper.beginRequest(command.request, generation.toLong())
    }

    /** Plays a view the queue prepared for this occurrence, through the same lease paths as [submitPrepared]. */
    private fun loadQueued(command: PlaybackCommand.Load, view: QueuedView) {
        val request = command.request
        stop()
        val source = Inbound.Prepare(
            request.occurrenceId, request.mediaId, view.prepared, command.startPositionMs, command.playWhenReady,
            request.attemptId,
        )
        when (val outcome = view.outcome) {
            is IosNativeAudioRoute.Outcome.Ready -> loadPrepared(source, outcome)
            is IosNativeAudioRoute.Outcome.Streamed -> loadStreamed(source, outcome)
            // Only Ready and Streamed outcomes are kept, so these cannot arrive here.
            is IosNativeAudioRoute.Outcome.Refused, is IosNativeAudioRoute.Outcome.Failed ->
                errors.tryEmit(PlaybackFailure(request.occurrenceId, PlaybackFailureReason.OPEN_FAILED,
                    "the prepared audio is unavailable", attemptId = request.attemptId))
        }
    }

    /**
     * Admits one Telegram track off the command loop, because the account check and the file lookup wait on TDLib. The
     * result comes back through the loop and plays only if no newer command has superseded it.
     */
    private fun beginTelegram(command: PlaybackCommand.Load, locator: PlaybackLocator.TelegramFile) {
        stop()
        request = command.request
        // A Telegram load re-runs this whole path on recovery: the resolver admits a fresh lease below.
        requestRepreparable = true
        val session = telegram
        val reader = accounts
        if (session == null || reader == null) {
            fail(PlaybackFailureReason.UNSUPPORTED_LOCATOR)
            return
        }
        val issuedAt = ticket
        preparing = scope.launch {
            val outcome = resolveTelegramTrack(session, reader, IOS_TELEGRAM_ACCOUNT_SCOPE, locator.track)
            var queued = false
            try {
                inbox.send(Inbound.TelegramResolved(issuedAt, command, outcome))
                queued = true
            } catch (_: ClosedSendChannelException) {
                // The port closed while the track was being admitted, so its source is released below.
            } finally {
                if (!queued) (outcome as? IosTelegramTrackOutcome.Ready)?.source?.close()
            }
        }
    }

    private fun applyTelegram(item: Inbound.TelegramResolved) {
        val outcome = item.outcome
        if (closed || item.issuedAt != ticket) {
            (outcome as? IosTelegramTrackOutcome.Ready)?.source?.close()
            return
        }
        val occurrenceId = item.command.request.occurrenceId
        when (outcome) {
            is IosTelegramTrackOutcome.Refused ->
                errors.tryEmit(PlaybackFailure(occurrenceId, PlaybackFailureReason.OPEN_FAILED, outcome.reason,
                    attemptId = item.command.request.attemptId))
            is IosTelegramTrackOutcome.Ready -> {
                request = item.command.request
                val native = outputForLoad()
                if (native == null) {
                    outcome.source.close()
                    fail(PlaybackFailureReason.OUTPUT_FAILED)
                    return
                }
                val generation = IosStreamLease.loadPlaintext(
                    native,
                    outcome.source,
                    micros(item.command.startPositionMs),
                    if (item.command.playWhenReady) 1 else 0,
                )
                if (generation == 0uL) {
                    // Closing again is harmless when the engine accepted and then closed the lease itself.
                    outcome.source.close()
                    fail(PlaybackFailureReason.OPEN_FAILED)
                    return
                }
                mapper.beginRequest(item.command.request, generation.toLong())
            }
        }
    }

    /**
     * Loads an anonymous segmented lease for a header-bearing or expiry-carrying HLS remote. The engine's lease cannot
     * fetch child resources, so the host expands the playlist into a segmented plaintext view itself: the playlist
     * document, each segment and each AES-128 key are read through [IosCiphertextOpener], which anchors credentials at
     * the playlist origin — the locator's headers never reach a segment host that lives elsewhere. The playlist is
     * fetched lazily on the producer thread, so this runs on the command loop without blocking it.
     */
    private fun loadSegmentedHls(
        native: CPointer<at_audio_output>,
        locator: PlaybackLocator.Remote,
        startUs: ULong,
        play: Int,
    ): ULong {
        val origin = StreamOrigin.parse(locator.url) ?: return 0uL
        val account = ByteSourceAccount(provider = SEGMENTED_PROVIDER, accountId = null)
        val transport =
            IosCiphertextOpener(
                boundScope = ANONYMOUS_ACCOUNT_SCOPE,
                account = account,
                recheckFor = null,
                headers = locator.headers,
                credentialAnchor = origin,
            )
        val view =
            HlsSegmentedSource(
                lease =
                    ByteSourceLease(
                        account = account,
                        primaryOrigin = origin,
                        permittedOrigins = setOf(origin),
                        // The query string carries a per-request signature, so it never becomes part of the cache key.
                        cacheKey = locator.url.substringBefore('?'),
                        expiresAtEpochMs = locator.expiresAtEpochMs,
                        lengthBytes = null,
                    ),
                playlistUrl = locator.url,
                playlistText = null,
                accountScope = ANONYMOUS_ACCOUNT_SCOPE,
                opener = transport,
            )
        val plaintext =
            NativePlaintext(
                source = view,
                mimeType = SEGMENTED_UNKNOWN_MIME,
                codec = "",
                length = null,
                expiresAtEpochMs = locator.expiresAtEpochMs,
            )
        val generation =
            IosStreamLease.loadPlaintext(native, IosPlaintextSource(transport, plaintext), startUs, play)
        if (generation == 0uL) {
            // An accepted lease is closed by the engine itself, and closing again is harmless.
            transport.close()
        } else {
            liveTransport = transport
        }
        return generation
    }

    /** The one engine output: created on first use, and recreated only after the engine reported an output fault. */
    private fun outputForLoad(): CPointer<at_audio_output>? {
        if (reopenOutput) {
            output?.let { at_audio_destroy(it) }
            output = null
            reopenOutput = false
        }
        return output ?: memScoped {
            val error = allocArray<ByteVar>(256)
            at_audio_create(error, 256uL)
        }?.also {
            output = it
            at_audio_volume(it, volume)
            at_audio_speed(it, desiredSpeed)
        }
    }

    /** Returns true while the engine plays or buffers, the states whose position and failure reads must stay prompt. */
    private fun observe(): Boolean {
        if (closed) return false
        val native = output ?: return false
        blend?.let { engine ->
            val now = SystemMonotonicClock.nowNanos()
            val read = engine.poll(now)
            read.promoted?.let { incoming ->
                // Both nodes are already switched before the old decoder/transport can be released.
                at_audio_stop(native)
                at_audio_destroy(native)
                liveTransport?.close()
                output = incoming.output
                request = incoming.request
                liveTransport = incoming.transport
                requestRepreparable = incoming.repreparable
                // A promoted occurrence is a new load session: its own recovery budget and baseline.
                stallRecoveryUsed = false
                stallWatchdog.reset()
                at_audio_volume(incoming.output, volume)
                at_audio_speed(incoming.output, desiredSpeed)
                mapper.beginRequest(incoming.request, incoming.generation)
            }
            if (read.state.errorCode == EnginePlaybackState.ERROR_OUTPUT) reopenOutput = true
            mapper.observe(read.state, now, outputLatencyMillis(engine.outputLatencySeconds()))?.let { state.value = it }
            mapper.failure(read.state) { "iOS native playback failed" }?.let { errors.tryEmit(it) }
            watchStall(read.state, now)
            return read.state.hasSource &&
                (read.state.isBuffering || (read.state.playWhenReady && !read.state.isEnded))
        }
        return memScoped {
            val s = alloc<ae_snapshot>()
            at_audio_snapshot(native, s.ptr)
            val observation = EnginePlaybackState(s.flags.toInt(), s.error_code.toInt(),
                s.load_generation.toLong(), s.seek_generation.toLong(), s.error_sequence.toLong(),
                s.position_us, s.duration_us, s.buffered_us, s.playback_speed)
            if (observation.errorCode == EnginePlaybackState.ERROR_OUTPUT) reopenOutput = true
            val now = SystemMonotonicClock.nowNanos()
            mapper.observe(
                observation,
                now,
                outputLatencyMs = outputLatencyMillis(at_audio_output_latency(native)),
            )?.let { state.value = it }
            mapper.failure(observation) { "iOS native playback failed" }?.let { errors.tryEmit(it) }
            watchStall(observation, now)
            observation.hasSource &&
                (observation.isBuffering || (observation.playWhenReady && !observation.isEnded))
        }
    }

    /**
     * Feeds the audible read to the stall watchdog: a source that should be rendering but has not
     * moved for the window is a stall, which [onStall] answers once. A promoted blend read arrives
     * through the same [request] slot the direct path fills, so it needs no feed of its own.
     */
    private fun watchStall(read: EnginePlaybackState, nowNanos: Long) {
        val playing = request
        if (playing == null) {
            stallWatchdog.reset()
            return
        }
        val expectProgress = read.hasSource && read.playWhenReady && !read.isEnded &&
            read.errorCode == EnginePlaybackState.ERROR_NONE
        if (stallWatchdog.observe(read, expectProgress, nowNanos)) onStall(playing, read)
    }

    /**
     * The audible source made no progress for the whole stall window. A request the plain load path
     * can re-issue — a local file, a remote URL, a Deezer ciphertext or a Telegram track, which the
     * resolver admits afresh — gets one bounded recovery: the output is recreated the way an
     * ERROR_OUTPUT read recreates it and the same occurrence is re-prepared at the frozen position.
     * A request whose lease was consumed — prepared plaintext or an admitted stream — surfaces as a
     * decode failure for the queue's own recovery, as does a second stall after a recovery ran.
     */
    private fun onStall(playing: PlaybackRequest, read: EnginePlaybackState) {
        if (stallRecoveryUsed || !requestRepreparable) {
            errors.tryEmit(
                PlaybackFailure(
                    playing.occurrenceId,
                    PlaybackFailureReason.DECODE_FAILED,
                    "iOS playback made no progress within the stall window",
                    attemptId = playing.attemptId,
                ),
            )
            return
        }
        reopenOutput = true
        load(PlaybackCommand.Load(playing, read.positionUs.coerceAtLeast(0L) / 1_000L, read.playWhenReady))
        // The stop() inside load() cleared this flag; it latches only after the reload was issued.
        stallRecoveryUsed = true
    }

    private fun stop() {
        output?.let { at_audio_stop(it) }
        blend?.close()
        blend = null
        mapper.stopped(SystemMonotonicClock.nowNanos())?.let { state.value = it }
        request = null
        requestRepreparable = false
        stallRecoveryUsed = false
        stallWatchdog.reset()
        // The engine keeps its lease until the next load, so the transport is released here, after the engine has stopped.
        liveTransport?.close()
        liveTransport = null
    }

    /** Advances the ticket so that any preparation still in flight is discarded, and cancels it so its transport is released. */
    private fun supersede() {
        invalidateHandoff()
        ticket++
        preparing?.cancel()
        preparing = null
    }

    private fun invalidateHandoff() {
        handoffEpoch++
        blend?.cancelNext()
    }

    private fun fail(reason: PlaybackFailureReason, message: String? = null) {
        request?.let {
            errors.tryEmit(
                PlaybackFailure(it.occurrenceId, reason, message ?: "iOS playback rejected", attemptId = it.attemptId),
            )
        }
    }

    /**
     * The wording of a refused lease open: a starved rotation refused the transport inside it, so the
     * sanitized status replaces the engine's generic rejection — it names no proxy address and maps to
     * the dialog's unblocking guidance. A local path never reaches the routing layer, so [isNetworkOpen]
     * keeps it on the ordinary wording.
     */
    private fun openFailureMessage(isNetworkOpen: Boolean): String? =
        if (isNetworkOpen && IosStreamProxyRouting.streamBlocked()) STREAM_BLOCKED_MESSAGE else null

    private fun micros(ms: Long): ULong = ms.coerceAtMost(Long.MAX_VALUE / 1_000).toULong() * 1_000uL

    private class QueuedView(val locator: PlaybackLocator, val prepared: PreparedAudio, val outcome: IosNativeAudioRoute.Outcome)

    /** The answer of [prepareForQueue]: the locator the queue loads, or the reason the audio cannot play here. */
    sealed interface QueuedPreparation {
        class Ready(val locator: PlaybackLocator) : QueuedPreparation

        class Refused(val reason: String) : QueuedPreparation
    }

    private companion object {
        /** Queue preparations not yet claimed. The queue prepares one occurrence at a time, so a few cover every race. */
        const val MAX_QUEUED_VIEWS = 4

        const val ACTIVE_POLL_MS = 50L
        const val BLEND_POLL_MS = 20L
        const val IDLE_POLL_MS = 250L
    }

    /** Work for the one command loop, which runs it in arrival order: a command, a preparation to start, or its result. */
    private sealed interface Inbound {
        class Command(
            val command: PlaybackCommand,
            val origin: PlaybackCommandOrigin,
            val intentGeneration: Long,
        ) : Inbound

        class Prepare(
            val occurrenceId: String,
            val mediaId: String,
            val prepared: PreparedAudio,
            val startPositionMs: Long,
            val playWhenReady: Boolean,
            val attemptId: Long? = null,
        ) : Inbound

        class Prepared(
            val issuedAt: Long,
            val source: Inbound.Prepare,
            val outcome: IosNativeAudioRoute.Outcome,
        ) : Inbound

        class TelegramResolved(
            val issuedAt: Long,
            val command: PlaybackCommand.Load,
            val outcome: IosTelegramTrackOutcome,
        ) : Inbound
    }
}

private fun nowEpochMs(): Long = Clock.System.now().toEpochMilliseconds()

/** Releases the transport of a preparation that will not play. A refused or failed preparation has already closed its own. */
private fun IosNativeAudioRoute.Outcome.release() {
    if (this is IosNativeAudioRoute.Outcome.Ready) transport.close()
}

/**
 * A stream that names a personal account. It is admitted against that account before its first byte. A stream that names
 * no account, or the anonymous scope, keeps the anonymous path, which is cookie-free.
 */
private fun DirectStream.isPersonal(): Boolean = accountScope != null && accountScope != ANONYMOUS_ACCOUNT_SCOPE

/**
 * A stream whose URI is an HLS playlist and that needs the host's segmented lease: it carries request headers or a
 * known authorization expiry, so the engine's own opener may not fetch it. A header-free anonymous playlist keeps the
 * engine path, whose own protocols already expand it.
 */
private fun DirectStream.needsSegmentedRoute(): Boolean =
    isHlsPlaylistUrl(uri) && (!headers.isEmpty() || expiresAtEpochMs != null)

/** An http(s) URL with a host and no embedded credentials, the only remote form the engine and the host lease accept. */
internal fun isWebUrl(url: String): Boolean {
    val parsed = NSURL.URLWithString(url)
    return '\u0000' !in url && parsed?.scheme?.lowercase() in setOf("https", "http") &&
        !parsed?.host.isNullOrBlank() && parsed?.user == null && parsed?.password == null
}
