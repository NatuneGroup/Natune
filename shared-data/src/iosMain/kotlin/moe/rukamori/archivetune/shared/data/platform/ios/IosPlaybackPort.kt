/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package moe.rukamori.archivetune.shared.data.platform.ios

import kotlin.time.Clock
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.flow.*
import moe.rukamori.archivetune.nativeaudio.*
import moe.rukamori.archivetune.shared.data.bytesource.ScopedCookieSource
import moe.rukamori.archivetune.shared.data.playback.EnginePlaybackMapper
import moe.rukamori.archivetune.shared.data.playback.EnginePlaybackState
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
    private var request: PlaybackRequest? = null
    private var reopenOutput = false
    private var closed = false

    // Main-queue state for host-prepared audio. Every Load, Stop and preparation advances [ticket]. A preparation whose
    // ticket no longer matches when it returns is discarded with its transport, so a superseded occurrence never plays.
    private var ticket = 0L
    private var preparing: Job? = null
    private var liveTransport: IosCiphertextOpener? = null
    private var route: IosNativeAudioRoute? = null

    override val snapshots: StateFlow<PlaybackSnapshot?> = state.asStateFlow()
    override val failures: SharedFlow<PlaybackFailure> = errors.asSharedFlow()

    init {
        scope.launch {
            for (item in inbox) {
                when (item) {
                    is Inbound.Command -> apply(item.command)
                    is Inbound.Prepare -> beginPreparation(item)
                    is Inbound.Prepared -> applyPrepared(item)
                }
                observe()
            }
        }
        scope.launch {
            while (isActive) {
                observe()
                delay(50)
            }
        }
    }

    override fun submit(command: PlaybackCommand) {
        val occurrence = (command as? PlaybackCommand.Load)?.request?.occurrenceId ?: state.value?.occurrenceId
        enqueue(Inbound.Command(command), occurrence)
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
            is PreparedAudio.Progressive ->
                prepared.stream.takeIf { it.uri.isNotBlank() && !it.isPersonal() }?.toPlaybackLocator()
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
            (prepared is PreparedAudio.Progressive && prepared.stream.isPersonal())
        if (needsAdmission) {
            enqueue(Inbound.Prepare(occurrenceId, mediaId, prepared, startPositionMs, playWhenReady), occurrenceId)
            return
        }
        submit(PlaybackCommand.Stop)
        errors.tryEmit(PlaybackFailure(occurrenceId, PlaybackFailureReason.UNSUPPORTED_LOCATOR,
            "the stream has no playable address"))
    }

    /**
     * Supplies the account reader and the scoped cookie source that personal progressive, native and TIDAL audio are
     * admitted against. It is single-shot: the root calls it once, when the vault is ready, with the one store that owns
     * the session. Until then that audio is refused, while the anonymous progressive and Deezer paths are unaffected.
     */
    fun attachAccounts(accounts: AcceptedAccountReader, cookies: ScopedCookieSource) {
        check(!closed && route == null)
        route = IosNativeAudioRoute(accounts, cookies)
    }

    override fun close() {
        // Queued results are discarded here, because cancelling the channel drops its elements without returning them. A
        // dropped preparation still holds a transport, so each one is released on the way out.
        while (true) {
            val queued = inbox.tryReceive().getOrNull() ?: break
            (queued as? Inbound.Prepared)?.outcome?.release()
        }
        inbox.cancel() // Discard queued commands; a close must not first execute pending loads.
        scope.launch {
            if (closed) return@launch
            closed = true
            supersede()
            stop()
            output?.let { at_audio_destroy(it) }
            output = null
            scope.cancel()
        }
    }

    /** Queues [item]. A full queue reports the occurrence it could not take, and an unknown occurrence is dropped silently. */
    private fun enqueue(item: Inbound, occurrenceId: String?) {
        val result = inbox.trySend(item)
        if (result.isFailure && !result.isClosed && occurrenceId != null) {
            errors.tryEmit(PlaybackFailure(occurrenceId, PlaybackFailureReason.OUTPUT_FAILED,
                "iOS playback command queue is full"))
        }
    }

    private fun apply(command: PlaybackCommand) {
        if (closed) return
        when (command) {
            is PlaybackCommand.Load -> load(command)
            PlaybackCommand.Play -> output?.let {
                if (at_audio_play(it) == 0) fail(PlaybackFailureReason.OUTPUT_FAILED)
            }
            PlaybackCommand.Pause -> output?.let { at_audio_pause(it) }
            is PlaybackCommand.SeekTo -> output?.let { at_audio_seek(it, micros(command.positionMs)) }
            is PlaybackCommand.SetSpeed -> output?.let { at_audio_speed(it, command.speed) }
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
                "the prepared audio is not a native plaintext view"))
            return
        }
        val expiresAt = handle.expiresAtEpochMs
        if (expiresAt != null && expiresAt <= nowEpochMs()) {
            transport.close()
            errors.tryEmit(PlaybackFailure(source.occurrenceId, PlaybackFailureReason.OPEN_FAILED,
                "the prepared audio authorization expired; prepare it again"))
            return
        }
        stop()
        val playable = PlaybackRequest(source.occurrenceId, source.mediaId, PlaybackLocator.PreparedPlaintext(handle))
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
            fail(PlaybackFailureReason.OPEN_FAILED)
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
        val playable = PlaybackRequest(source.occurrenceId, source.mediaId, remote.locator)
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
            fail(PlaybackFailureReason.OPEN_FAILED)
            return
        }
        mapper.beginRequest(playable, generation.toLong())
    }

    private fun load(command: PlaybackCommand.Load) {
        supersede()
        val locator = command.request.locator
        val source = when (locator) {
            is PlaybackLocator.LocalFile -> locator.path.takeIf { it.startsWith("/") && '\u0000' !in it }
            is PlaybackLocator.Remote -> locator.url.takeIf { isWebUrl(it) }
            // The Deezer URL is checked like a remote one. The engine never opens it: only the lease below decrypts it.
            is PlaybackLocator.DeezerEncrypted -> locator.url.takeIf { isWebUrl(it) }
            // A TDLib file has no URL, and the iOS host has no Telegram lease yet, so the null source refuses the load.
            is PlaybackLocator.TelegramFile -> null
            // Host-prepared plaintext owns a transport, so it enters only through submitPrepared, never a bare command.
            is PlaybackLocator.PreparedPlaintext -> null
        }
        stop()
        request = command.request
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
        // Deezer ciphertext and header-bearing remotes are read through the host lease, which decrypts them or carries
        // their headers under the lease policy. A header-free remote keeps the engine's own opener.
        val generation = when {
            locator is PlaybackLocator.DeezerEncrypted -> IosStreamLease.loadDeezer(native, locator, startUs, play)
            locator is PlaybackLocator.Remote && !locator.headers.isEmpty() ->
                IosStreamLease.loadRemote(native, locator, startUs, play)
            else -> at_audio_load(native, source, startUs, play)
        }
        if (generation == 0uL) {
            fail(PlaybackFailureReason.OPEN_FAILED)
            return
        }
        mapper.beginRequest(command.request, generation.toLong())
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
        }?.also { output = it }
    }

    private fun observe() {
        if (closed) return
        val native = output ?: return
        memScoped {
            val s = alloc<ae_snapshot>()
            at_audio_snapshot(native, s.ptr)
            val observation = EnginePlaybackState(s.flags.toInt(), s.error_code.toInt(),
                s.load_generation.toLong(), s.seek_generation.toLong(), s.error_sequence.toLong(),
                s.position_us, s.duration_us, s.buffered_us, s.playback_speed)
            if (observation.errorCode == EnginePlaybackState.ERROR_OUTPUT) reopenOutput = true
            mapper.observe(observation, SystemMonotonicClock.nowNanos())?.let { state.value = it }
            mapper.failure(observation) { "iOS native playback failed" }?.let { errors.tryEmit(it) }
        }
    }

    private fun stop() {
        output?.let { at_audio_stop(it) }
        mapper.stopped(SystemMonotonicClock.nowNanos())?.let { state.value = it }
        request = null
        // The engine keeps its lease until the next load, so the transport is released here, after the engine has stopped.
        liveTransport?.close()
        liveTransport = null
    }

    /** Advances the ticket so that any preparation still in flight is discarded, and cancels it so its transport is released. */
    private fun supersede() {
        ticket++
        preparing?.cancel()
        preparing = null
    }

    private fun fail(reason: PlaybackFailureReason) {
        request?.let { errors.tryEmit(PlaybackFailure(it.occurrenceId, reason, "iOS playback rejected")) }
    }

    private fun micros(ms: Long): ULong = ms.coerceAtMost(Long.MAX_VALUE / 1_000).toULong() * 1_000uL

    /** Work for the one command loop, which runs it in arrival order: a command, a preparation to start, or its result. */
    private sealed interface Inbound {
        class Command(val command: PlaybackCommand) : Inbound

        class Prepare(
            val occurrenceId: String,
            val mediaId: String,
            val prepared: PreparedAudio,
            val startPositionMs: Long,
            val playWhenReady: Boolean,
        ) : Inbound

        class Prepared(
            val issuedAt: Long,
            val source: Inbound.Prepare,
            val outcome: IosNativeAudioRoute.Outcome,
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

/** An http(s) URL with a host and no embedded credentials, the only remote form the engine and the host lease accept. */
private fun isWebUrl(url: String): Boolean {
    val parsed = NSURL.URLWithString(url)
    return '\u0000' !in url && parsed?.scheme?.lowercase() in setOf("https", "http") &&
        !parsed?.host.isNullOrBlank() && parsed?.user == null && parsed?.password == null
}
