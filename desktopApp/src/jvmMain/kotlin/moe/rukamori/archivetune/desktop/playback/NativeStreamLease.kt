package moe.rukamori.archivetune.desktop.playback

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import moe.rukamori.archivetune.shared.data.bytesource.AdmittedTelegramPartialFilePort
import moe.rukamori.archivetune.shared.data.bytesource.ByteSourceAccount
import moe.rukamori.archivetune.shared.data.bytesource.ByteSourceCancelledException
import moe.rukamori.archivetune.shared.data.bytesource.ByteSourceException
import moe.rukamori.archivetune.shared.data.bytesource.ByteSourceLease
import moe.rukamori.archivetune.shared.data.bytesource.GuardedByteSource
import moe.rukamori.archivetune.shared.data.bytesource.LeaseCancellation
import moe.rukamori.archivetune.shared.data.bytesource.RandomAccessByteSource
import moe.rukamori.archivetune.shared.data.bytesource.StreamOrigin
import moe.rukamori.archivetune.shared.data.bytesource.TelegramLocalPartialFileSource
import moe.rukamori.archivetune.shared.data.bytesource.TelegramRandomAccessSource
import moe.rukamori.archivetune.shared.data.bytesource.telegramFileLease
import moe.rukamori.archivetune.shared.data.stream.NativePlaintext
import moe.rukamori.archivetune.shared.data.stream.deezer.DeezerChunkByteSource
import moe.rukamori.archivetune.shared.data.stream.deezer.DeezerCrypto
import moe.rukamori.archivetune.shared.domain.playback.PlaybackLocator
import moe.rukamori.archivetune.shared.domain.telegram.TelegramTrackRef

/*
 * Result codes shared with AE_IO_EOF, AE_IO_RETRY, AE_IO_FAILED and AE_IO_INTERRUPTED in archivetune_engine.h.
 * Native read treats 0 as end of stream, so a shared-data 0 (no bytes yet) must map to RETRY, not EOF.
 */
private const val AE_IO_EOF = 0
private const val AE_IO_RETRY = -1
private const val AE_IO_FAILED = -2
private const val AE_IO_INTERRUPTED = -3
private const val AE_IO_OK = 0

/** How long one chunk read may wait for bytes that have not arrived. It matches the engine's own starvation limit. */
private const val STALL_BUDGET_MS = 30_000L
private const val STALL_BUDGET_NANOS = STALL_BUDGET_MS * 1_000_000L

/** The pause between two empty reads from a stalled connection, short enough that an interrupt is noticed promptly. */
private const val RETRY_DELAY_MS = 5L

/**
 * The host side of one engine stream lease: the object the native engine calls back on its producer thread.
 *
 * The engine calls [open], [read], [seek] and [close] on its producer thread, which may block. It calls [interrupt]
 * on the command thread while the engine mutex is held, so that one must return at once. Every method returns a native
 * code instead of throwing, because a Java exception cannot cross the JNI boundary. The JNI binding looks these methods
 * up by name and descriptor, so a rename or signature change needs a matching change in archivetune_engine_jni.c.
 *
 * The bytes come from [readAt], which is a header-bearing HTTP(S) resource, a decrypting chain over one, or a TDLib file
 * read through a Telegram lease. The lease does not need to know which. It needs only the probe, the interrupt and the
 * close that belong to that source.
 */
internal class NativeStreamLease private constructor(
    private val readAt: suspend (position: Long, buffer: ByteArray, offset: Int, length: Int) -> Int,
    private val probe: suspend () -> StreamSizing,
    private val interruptSource: () -> Unit,
    private val closeSource: () -> Unit,
    /**
     * Restartable cancellation for the calls that [probe] and [readAt] wait in, so an interrupt wakes a read that a
     * TDLib request holds. Null for a source whose calls end on their own, such as an HTTP request that
     * [interruptSource] already breaks.
     */
    private val lifetimes: ReadLifetimes? = null,
) {
    // Confined to the producer thread. The engine keeps the same position in its own view of the stream.
    private var position = 0L
    private var seekable = false
    private var closed = false

    /** Writes the length (-1 when unknown) and seekability into [out], then returns 0 or a negative AE_IO code. */
    fun open(out: LongArray): Int {
        if (closed) return AE_IO_FAILED
        return try {
            val sizing = runBlocking(blockingContext()) { probe() }
            seekable = sizing.seekable
            out[0] = sizing.sizeBytes
            out[1] = if (sizing.seekable) 1L else 0L
            AE_IO_OK
        } catch (cancelled: ByteSourceCancelledException) {
            AE_IO_INTERRUPTED
        } catch (cancelled: CancellationException) {
            // A lifetime cancelled by an interrupt ends the call as a cancellation, which is not a failure of the source.
            AE_IO_INTERRUPTED
        } catch (failure: Exception) {
            AE_IO_FAILED
        }
    }

    /** Copies up to [length] bytes into [buffer] and returns the count, or AE_IO_EOF, AE_IO_RETRY or a failure code. */
    fun read(buffer: ByteArray, length: Int): Int {
        if (closed) return AE_IO_FAILED
        return try {
            val read = runBlocking(blockingContext()) { readAt(position, buffer, 0, length) }
            when {
                read == RandomAccessByteSource.END_OF_STREAM -> AE_IO_EOF
                read > 0 -> {
                    position += read
                    read
                }
                else -> AE_IO_RETRY
            }
        } catch (cancelled: ByteSourceCancelledException) {
            AE_IO_INTERRUPTED
        } catch (cancelled: CancellationException) {
            AE_IO_INTERRUPTED
        } catch (failure: Exception) {
            AE_IO_FAILED
        }
    }

    /**
     * Moves the read position and returns the position reached. The request is not sent here: the next read
     * opens it at the new position, so a seek never waits on the network. A source that cannot seek refuses.
     */
    fun seek(target: Long): Long {
        if (closed || !seekable || target < 0L) return AE_IO_FAILED.toLong()
        position = target
        return target
    }

    /** Runs on the command thread. It wakes a blocked call and never waits for the producer. */
    fun interrupt() {
        interruptSource()
    }

    /** Called exactly once by the engine, which may be the command thread if the request was refused. Idempotent. */
    fun close() {
        if (closed) return
        closed = true
        closeSource()
    }

    /** The context for a call the producer waits on: the read's own token, so an interrupt ends only the read in flight. */
    private fun blockingContext(): CoroutineContext = lifetimes?.start() ?: EmptyCoroutineContext

    companion object {
        private const val PLAYBACK_PROVIDER = "playback"
        private const val DEEZER_PROVIDER = "deezer"
        private const val TELEGRAM_PROVIDER = "telegram"

        /**
         * Stops Telegram transfers off the engine thread. [NativeStreamLease.close] can run on the command thread when a
         * request was refused, and a TDLib request must not hold that thread for its whole timeout.
         */
        private val telegramRetireScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        // A logout must stop every transfer before TDLib wipes its database, including one whose lease the engine has
        // not closed yet, so each Telegram port is tracked from its open until its retirement finishes.
        private val openTelegramPorts: MutableSet<TdlibPartialFilePort> = ConcurrentHashMap.newKeySet()

        /** Stops the transfer of every Telegram stream that is open or still retiring. Failures are ignored per port. */
        suspend fun cancelTelegramTransfers() {
            for (port in openTelegramPorts.toList()) port.cancelDownload()
        }

        /**
         * The lease for a header-bearing HTTP(S) resource. Returns null when the locator is not one, such as a URL with
         * userinfo, which is refused so that credentials never ride in a URL. The lease permits only the primary origin,
         * so no redirect or child request can leave it.
         */
        fun forRemote(locator: PlaybackLocator.Remote): NativeStreamLease? {
            val origin = StreamOrigin.parse(locator.url) ?: return null
            val lease = ByteSourceLease(
                account = ByteSourceAccount(provider = PLAYBACK_PROVIDER, accountId = null),
                primaryOrigin = origin,
                permittedOrigins = setOf(origin),
                // The query string often carries a signed token, so it never becomes part of the cache key.
                cacheKey = locator.url.substringBefore('?'),
                expiresAtEpochMs = locator.expiresAtEpochMs,
                lengthBytes = null,
            )
            val http = HttpRangeSource(lease, locator.url, locator.headers, cookies = emptyList())
            return NativeStreamLease(
                readAt = { position, buffer, offset, length -> http.readAt(position, buffer, offset, length) },
                probe = { http.probe().let { StreamSizing(it.sizeBytes, it.seekable) } },
                interruptSource = { http.interrupt() },
                closeSource = { http.close() },
            )
        }

        /**
         * The lease for one Deezer CDN track, read as decrypted audio. Deezer keeps the length and encrypts every third
         * 2048-byte chunk, so the plaintext size is the ciphertext size that the probe reports. One lease generation
         * covers the whole chain: an interrupt cancels it, so a chunk still in flight is discarded rather than decoded.
         * The track id travels with the locator, because the chunk key cannot be recovered from the URL. Origin rules
         * match [forRemote], so a URL that [forRemote] refuses is refused here too.
         */
        fun forDeezerTrack(locator: PlaybackLocator.DeezerEncrypted): NativeStreamLease? {
            val origin = StreamOrigin.parse(locator.url) ?: return null
            val lease = ByteSourceLease(
                account = ByteSourceAccount(provider = DEEZER_PROVIDER, accountId = null),
                primaryOrigin = origin,
                permittedOrigins = setOf(origin),
                cacheKey = locator.url.substringBefore('?'),
                expiresAtEpochMs = locator.expiresAtEpochMs,
                lengthBytes = null,
            )
            val http = HttpRangeSource(lease, locator.url, locator.headers, cookies = emptyList())
            val cancellation = LeaseCancellation()
            // The ciphertext reader takes its ticket at the start of each read, and the plaintext guard is applied per
            // read, so an interrupt discards the chunk in flight without making every later read refuse.
            val plaintext = DeezerChunkByteSource(
                ciphertext = WholeReadSource(http, cancellation),
                key = DeezerCrypto.deriveKey(locator.trackId),
            )
            return NativeStreamLease(
                readAt = { position, buffer, offset, length ->
                    GuardedByteSource(plaintext, cancellation.issueTicket()).readAt(position, buffer, offset, length)
                },
                probe = { http.probe().let { StreamSizing(it.sizeBytes, it.seekable) } },
                interruptSource = {
                    cancellation.cancelAll()
                    http.interrupt()
                },
                closeSource = {
                    cancellation.cancelAll()
                    http.close()
                },
            )
        }

        /**
         * The lease for one Telegram track, read from its TDLib partial file. A Telegram file has no network origin, so the
         * lease carries no origin, header or cookie policy and is never presented as HTTP. The account is admitted when the
         * engine opens the track, on the producer thread that already waits for the probe, so the playback executor never
         * blocks on the account store. An account that is not accepted refuses the open, and the refusal never falls back to
         * another route. A read outside the downloaded run returns a retry while TDLib fetches the bytes, and the engine
         * waits that out on its own stall budget. The port re-resolves a stale stored file id from the track's message, so a
         * stale id does not refuse the track.
         */
        fun forTelegramTrack(
            track: TelegramTrackRef,
            binding: TelegramFileBinding,
        ): NativeStreamLease {
            val port = TdlibPartialFilePort(track, binding.operations)
            openTelegramPorts.add(port)
            // Set by the open. A read cannot arrive before a successful open, so an empty holder fails the read closed.
            val admittedSource = AtomicReference<TelegramRandomAccessSource?>(null)
            // One token covers the probe and every read. An interrupt ends the TDLib request in flight and arms a fresh
            // token, because the engine's interrupt is a transient wake-up and the next read must reach TDLib again.
            val lifetimes = ReadLifetimes()
            return NativeStreamLease(
                readAt = { position, buffer, offset, length ->
                    val source = admittedSource.get()
                        ?: throw ByteSourceException("The Telegram track was read before it was admitted")
                    source.readAt(position, buffer, offset, length)
                },
                probe = {
                    val admission = binding.accounts.admit(TELEGRAM_PROVIDER, binding.accountScope)
                        ?: throw ByteSourceException(
                            "No Telegram account is accepted; sign in to Telegram before playing",
                        )
                    val lease = telegramFileLease(track, lengthBytes = null, admission = admission)
                    val guarded = AdmittedTelegramPartialFilePort(admission, binding.accounts, port)
                    admittedSource.set(TelegramLocalPartialFileSource(lease, guarded))
                    // TDLib reports no length for a file it has not sized yet, and -1 is the engine's unknown length. Telegram
                    // reads can start at any offset, so the stream is seekable.
                    guarded.state().let { StreamSizing(it.expectedSizeBytes ?: -1L, seekable = true) }
                },
                interruptSource = { lifetimes.interrupt() },
                closeSource = {
                    lifetimes.close()
                    telegramRetireScope.launch {
                        try {
                            port.cancelDownload()
                        } finally {
                            openTelegramPorts.remove(port)
                        }
                    }
                },
                lifetimes = lifetimes,
            )
        }

        /**
         * The lease for a prepared native plaintext view: an Amazon CENC view, a QQ container view or a TIDAL segment chain.
         * The view reads its ciphertext through [opener], so an interrupt or close of this lease reaches every request the
         * view has open. A view that states no length is unknown-length, and the engine must not assume zero for it.
         */
        fun forNativePlaintext(
            plaintext: NativePlaintext,
            opener: HostCiphertextOpener,
        ): NativeStreamLease {
            val guarded = opener.guard(plaintext.source)
            return NativeStreamLease(
                readAt = { position, buffer, offset, length -> guarded.readAt(position, buffer, offset, length) },
                // Every view reads by position over its ciphertext, so the stream can start at any offset.
                probe = { StreamSizing(plaintext.length ?: -1L, seekable = true) },
                interruptSource = { opener.interrupt() },
                closeSource = { opener.close() },
            )
        }
    }
}

/** What the engine learns when it opens a lease: the length (-1 when unknown) and whether a read can start mid-stream. */
internal class StreamSizing(val sizeBytes: Long, val seekable: Boolean)

/**
 * Completes each read from [upstream] unless the stream ends first, so each chunk request reaches
 * [DeezerChunkByteSource] whole. That class withholds a partial encrypted chunk by itself, so this adapter is no
 * longer what keeps ciphertext away from the engine. It still owns the stall budget for the ciphertext reads: it gives up
 * after the same budget the engine applies to an empty read, counted from the last byte received, so a slow but live
 * connection is not cut off. It stops as soon as the lease generation is cancelled, taking its ticket at the start of
 * each read so a cancelled read does not make the next one refuse.
 */
private class WholeReadSource(
    private val upstream: RandomAccessByteSource,
    private val cancellation: LeaseCancellation,
) : RandomAccessByteSource {
    override val lease: ByteSourceLease
        get() = upstream.lease

    override suspend fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        val ticket = cancellation.issueTicket()
        var got = 0
        var ended = false
        // A monotonic clock, because a wall-clock step would otherwise end or extend the stall budget.
        var lastProgressNanos = System.nanoTime()
        while (got < length) {
            ticket.throwIfCancelled()
            val read = upstream.readAt(position + got, buffer, offset + got, length - got)
            if (read == RandomAccessByteSource.END_OF_STREAM) {
                ended = true
                break
            }
            if (read > 0) {
                got += read
                lastProgressNanos = System.nanoTime()
            } else {
                if (System.nanoTime() - lastProgressNanos >= STALL_BUDGET_NANOS) {
                    throw ByteSourceException("the source stalled mid-chunk")
                }
                delay(RETRY_DELAY_MS)
            }
        }
        ticket.throwIfCancelled()
        return if (got == 0 && ended) RandomAccessByteSource.END_OF_STREAM else got
    }
}

/**
 * The read token of a lease whose host call can block. The engine's interrupt contract is a transient wake-up: it wakes
 * the read in flight and the source stays usable. So every read takes the current token, and an interrupt cancels that
 * token and arms a new one for the next read. A single fixed job would end the lease at the first seek.
 */
internal class ReadLifetimes {
    private val current = AtomicReference<Job>(Job())

    /** The token of a read that starts now. */
    fun start(): Job = current.get()

    /** Ends the read in flight and arms a fresh token for the next one. */
    fun interrupt() {
        var previous: Job
        do {
            previous = current.get()
        } while (!current.compareAndSet(previous, Job()))
        previous.cancel()
    }

    /** After a close no read may run again, so every token that start hands out is already cancelled. */
    fun close() {
        interrupt()
        current.get().cancel()
    }
}
