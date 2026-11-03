@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package moe.rukamori.archivetune.shared.data.platform.ios

import kotlin.concurrent.Volatile
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.cinterop.*
import cnames.structs.at_audio_output
import moe.rukamori.archivetune.nativeaudio.*
import moe.rukamori.archivetune.shared.data.bytesource.ByteSourceAccount
import moe.rukamori.archivetune.shared.data.bytesource.ByteSourceException
import moe.rukamori.archivetune.shared.data.bytesource.ByteSourceLease
import moe.rukamori.archivetune.shared.data.bytesource.RandomAccessByteSource
import moe.rukamori.archivetune.shared.data.bytesource.StreamOrigin
import moe.rukamori.archivetune.shared.data.stream.deezer.DeezerCrypto
import moe.rukamori.archivetune.shared.domain.playback.PlaybackLocator
import moe.rukamori.archivetune.shared.domain.playback.STREAM_BLOCKED_MESSAGE
import platform.Foundation.NSLock
import platform.posix.memcpy

/*
 * Mirrors AE_STREAM_IO_VERSION and the AE_IO_* results of archivetune_engine.h. They are literals so this file never
 * depends on how cinterop renders a macro. A change to the header must change these together.
 */
private const val STREAM_IO_ABI_VERSION: UInt = 1u
private const val IO_EOF = 0
private const val IO_RETRY = -1
private const val IO_FAILED = -2
private const val IO_INTERRUPTED = -3

/** One ABI read is capped, so a large request from the engine cannot allocate an unbounded scratch buffer. */
private const val MAX_READ_BYTES = 1 shl 20

/** Lease provider names. Header-bearing playback is anonymous; the Deezer CDN is cookie-free but still its own provider. */
private const val PLAYBACK_PROVIDER = "playback"
private const val DEEZER_PROVIDER = "deezer"

/**
 * The blocking surface the ABI callbacks drive. A header-bearing progressive source and a Deezer decrypting view both
 * answer it, so one ABI adapter serves each. Every call runs on the producer thread except [interrupt].
 */
internal interface IosLeaseSource {
    /** Opens the resource. Reports its length (-1 when unknown) and whether ranged reads are honoured. */
    fun probe(): IosHttpRangeSource.Probe

    /** Reads without suspending. Returns 0 when nothing arrived within the stall budget, which the caller reports as RETRY. */
    fun readBlocking(position: Long, buffer: ByteArray, offset: Int, length: Int): Int

    /** Runs on the command thread. It cancels in-flight work and never calls into the engine. */
    fun interrupt()

    /** Called once, after the engine has retracted the lease. It releases the connection. */
    fun close()
}

/**
 * The host byte source behind one header-bearing progressive or Deezer load. FFmpeg reads it through ae_stream_io, so the
 * request carries the caller's headers under the lease policy, and no plaintext or decrypted file is ever written. A Deezer
 * track is decrypted in memory by [IosDeezerStreamSource] before the engine reads it.
 *
 * Threads follow the ABI. open, read and seek run on the engine's producer thread, one call at a time. interrupt runs on
 * the command thread under the engine mutex and only cancels in-flight work. The engine retracts the lease before it
 * calls close, so an interrupt never reaches a closed lease, and close releases the StableRef exactly once.
 */
internal class IosStreamLease internal constructor(
    private val source: IosLeaseSource,
) {
    // Producer-thread state. The engine opens once, then reads and seeks in sequence.
    private var cursor = 0L
    private var seekable = false

    // Set under liveLeasesLock when the engine closes the lease. A refusal that never reached the close
    // path leaves the StableRef to the caller.
    @Volatile
    internal var released = false

    /**
     * The (output, generation) key the register put this lease under, or null while attach has not
     * published one — written under liveLeasesLock so a close can never see a half-assigned key.
     */
    @Volatile
    internal var registryKey: LeaseKey? = null

    /**
     * The sanitized refusal this lease's open carried, or null. open() writes it on the producer thread and
     * the error report reads it on the command loop, so it is volatile.
     */
    @Volatile
    internal var openRefusal: String? = null

    /** Probes the resource and writes its length (-1 when unknown) and seekability. Returns 0 when the source opened. */
    internal fun openStream(sizeOut: CPointer<LongVar>, seekableOut: CPointer<IntVar>): Int = try {
        val probe = source.probe()
        cursor = 0L
        seekable = probe.seekable
        sizeOut.pointed.value = probe.sizeBytes
        seekableOut.pointed.value = if (probe.seekable) 1 else 0
        0
    } catch (cancelled: CancellationException) {
        IO_INTERRUPTED
    } catch (failure: Throwable) {
        // The engine has no retry for open, so a failed probe is final for this load. A refusal the
        // stream routing made keeps its sanitized status so the error report can name it later.
        if (generateSequence(failure) { it.cause }
                .any { it is ByteSourceException && it.message == STREAM_BLOCKED_MESSAGE }
        ) {
            openRefusal = STREAM_BLOCKED_MESSAGE
        }
        IO_FAILED
    }

    /**
     * Copies bytes at the cursor into [buffer]. Returns the count, AE_IO_EOF at the end, or AE_IO_RETRY when nothing
     * arrived in time. Zero is never returned as data, because FFmpeg reads a zero count as end of input.
     */
    internal fun readStream(buffer: CPointer<UByteVar>, length: UInt): Int {
        val count = minOf(length, MAX_READ_BYTES.toUInt()).toInt()
        if (count == 0) return IO_FAILED
        val scratch = ByteArray(count)
        return try {
            val read = source.readBlocking(cursor, scratch, 0, count)
            when {
                read > 0 -> {
                    scratch.usePinned { memcpy(buffer, it.addressOf(0), read.toULong()) }
                    cursor += read
                    read
                }
                read == RandomAccessByteSource.END_OF_STREAM -> IO_EOF
                else -> IO_RETRY
            }
        } catch (cancelled: CancellationException) {
            IO_INTERRUPTED
        } catch (failure: Throwable) {
            IO_FAILED
        }
    }

    /** Moves the cursor. Returns the position reached, or AE_IO_FAILED when the source cannot seek there. */
    internal fun seekStream(position: Long): Long {
        if (!seekable || position < 0L) return IO_FAILED.toLong()
        cursor = position
        return position
    }

    /** Runs on the command thread. It cancels in-flight work and returns without touching the engine. */
    internal fun interruptStream() {
        source.interrupt()
    }

    /**
     * Called once, after the engine has retracted the lease. Releases the connection. The refusal the
     * open carried is copied into the refusal slot first — the native side may close a failed source
     * before it publishes the error, and the report must still find the sanitized status afterwards.
     */
    internal fun closeStream() {
        liveLeasesLock.locked {
            released = true
            registryKey?.let { key ->
                openRefusal?.let { refusals[key] = it }
                if (liveLeases[key] === this) liveLeases.remove(key)
            }
        }
        source.close()
    }

    companion object {
        /** One engine's output and one of its load generations — generations restart per output. */
        internal data class LeaseKey(val output: Long, val generation: Long)

        /**
         * Live leases by their (output, generation) key. Closed leases drop out on close, but their
         * sanitized refusal stays in [refusals] until the error report consumes it or a newer
         * generation for the same output supersedes it.
         */
        private val liveLeasesLock = NSLock()
        private val liveLeases = HashMap<LeaseKey, IosStreamLease>()
        private val refusals = HashMap<LeaseKey, String>()

        /**
         * Registers [lease] under [output] and [generation], the moment the engine accepted the load.
         * A refusal the open already carried is kept under the key even when the lease was already
         * closed — the engine may open and retract a failed lease before attach resumes — and a newer
         * generation supersedes older refusals of the same output.
         */
        internal fun register(output: Long, generation: Long, lease: IosStreamLease) =
            liveLeasesLock.locked {
                val key = LeaseKey(output, generation)
                // The key lands under the same lock the close path takes, so a lease closed between
                // the native call and this line keeps its refusal under the correct key only.
                lease.registryKey = key
                lease.openRefusal?.let { refusals[key] = it }
                if (!lease.released) liveLeases[key] = lease
                refusals.keys.removeAll { it.output == output && it.generation != generation }
            }

        /**
         * Drops every live lease and refusal of a destroyed output — a pointer the allocator reuses
         * must not surface a refusal recorded for the output that used to sit at the address.
         */
        internal fun retireOutput(output: Long) = liveLeasesLock.locked {
            // at_audio_destroy can return before the producer thread joined, so a retracted lease's
            // closeStream may still run — blanking its key first keeps that late close from writing
            // the refusal back under an output that no longer exists.
            liveLeases.values.forEach { if (it.registryKey?.output == output) it.registryKey = null }
            liveLeases.keys.removeAll { it.output == output }
            refusals.keys.removeAll { it.output == output }
        }

        /**
         * Drops the entry under the key only while it still names [lease] — a newer lease may already
         * occupy it when the old one's close finally runs.
         */
        internal fun unregister(output: Long, generation: Long, lease: IosStreamLease) =
            liveLeasesLock.locked {
                val key = LeaseKey(output, generation)
                if (liveLeases[key] === lease) liveLeases.remove(key)
            }

        /**
         * The sanitized refusal the open of (output, generation) carried, or null — a stale generation
         * or a clean open both answer null, so the error report falls back to the engine's own wording.
         */
        internal fun refusalOf(output: Long, generation: Long): String? = liveLeasesLock.locked {
            val key = LeaseKey(output, generation)
            refusals.remove(key) ?: liveLeases[key]?.openRefusal
        }

        private inline fun <T> NSLock.locked(block: () -> T): T {
            lock()
            try {
                return block()
            } finally {
                unlock()
            }
        }
        /**
         * Loads a header-bearing progressive [locator] into [output] through the host lease. Returns the load generation,
         * or 0 when the engine refuses it. The engine owns an accepted lease and closes it once, so this side frees the
         * StableRef only for a refusal that never reached the engine's close path.
         */
        internal fun loadRemote(
            output: CPointer<at_audio_output>,
            locator: PlaybackLocator.Remote,
            startUs: ULong,
            play: Int,
        ): ULong {
            val origin = StreamOrigin.parse(locator.url) ?: return 0uL
            val http = IosHttpRangeSource(
                leaseFor(PLAYBACK_PROVIDER, origin, locator.url, locator.expiresAtEpochMs),
                locator.url,
                locator.headers,
                emptyList(),
            )
            return attach(output, http, startUs, play)
        }

        /**
         * Loads a Deezer [locator] as decrypted audio. The ciphertext is read under the same lease policy as a header-bearing
         * stream, and the chunk key derives from the track id, so the engine receives plaintext and no file is staged. The
         * ownership rules are those of [loadRemote].
         */
        internal fun loadDeezer(
            output: CPointer<at_audio_output>,
            locator: PlaybackLocator.DeezerEncrypted,
            startUs: ULong,
            play: Int,
        ): ULong {
            val origin = StreamOrigin.parse(locator.url) ?: return 0uL
            val ciphertext = IosHttpRangeSource(
                leaseFor(DEEZER_PROVIDER, origin, locator.url, locator.expiresAtEpochMs),
                locator.url,
                locator.headers,
                emptyList(),
            )
            val plaintext = IosDeezerStreamSource(ciphertext, DeezerCrypto.deriveKey(locator.trackId))
            return attach(output, plaintext, startUs, play)
        }

        /**
         * Loads host-prepared plaintext [source], such as a native recipe or TIDAL manifest that the shared preparers have
         * already opened, through the same lease as the other loads. The ownership rules are those of [loadRemote]. The
         * engine reads only the bytes the preparer produced, and nothing is fetched or staged here.
         */
        internal fun loadPlaintext(
            output: CPointer<at_audio_output>,
            source: IosLeaseSource,
            startUs: ULong,
            play: Int,
        ): ULong = attach(output, source, startUs, play)

        /**
         * Loads an admitted personal progressive [remote] into [output]. Each request of the lease, the first open
         * included, reads its cookies through [IosAdmittedRemote.recheck] just before it leaves, so no request carries
         * a copy taken at admission. Returns the load generation, or 0 when the engine refuses it. The ownership rules
         * are those of [loadRemote].
         */
        internal fun loadAdmitted(
            output: CPointer<at_audio_output>,
            remote: IosAdmittedRemote,
            startUs: ULong,
            play: Int,
        ): ULong {
            val http = IosHttpRangeSource(
                remote.lease,
                remote.locator.url,
                remote.locator.headers,
                recheck = remote.recheck,
            )
            return attach(output, http, startUs, play)
        }

        /** Hands [source] to the engine as one ae_stream_io lease. Returns the load generation, or 0 when it is refused. */
        private fun attach(output: CPointer<at_audio_output>, source: IosLeaseSource, startUs: ULong, play: Int): ULong {
            val lease = IosStreamLease(source)
            val reference = StableRef.create(lease)
            val generation = memScoped {
                val io = alloc<ae_stream_io>()
                io.abi_version = STREAM_IO_ABI_VERSION
                io.opaque = reference.asCPointer()
                io.open = staticCFunction { opaque: COpaquePointer?, size: CPointer<LongVar>?, seekable: CPointer<IntVar>? ->
                    streamOpen(opaque, size, seekable)
                }
                io.read = staticCFunction { opaque: COpaquePointer?, buffer: CPointer<UByteVar>?, length: UInt ->
                    streamRead(opaque, buffer, length)
                }
                io.seek = staticCFunction { opaque: COpaquePointer?, position: Long -> streamSeek(opaque, position) }
                io.interrupt = staticCFunction { opaque: COpaquePointer? -> streamInterrupt(opaque) }
                io.close = staticCFunction { opaque: COpaquePointer? -> streamClose(opaque) }
                // The engine copies the struct during this call and then owns the lease, closing it exactly once.
                at_audio_load_stream(output, io.ptr, startUs, play)
            }
            // A refusal after the copy closes the lease synchronously and sets [released]. Only an ABI mismatch returns
            // before that path, and then no callback has run, so this side frees the StableRef.
            if (generation == 0uL && !lease.released) reference.dispose()
            if (generation != 0uL) {
                // The open may already have run (and the lease closed) inside the native call — the
                // register publishes this lease's key and records a refusal it carried in one lock.
                register(output.toLong(), generation.toLong(), lease)
            }
            return generation
        }
    }
}

/**
 * A personal progressive stream admitted against its account before its first byte was requested. [lease] names that
 * entry, and [recheck] reads the cookies of that same entry before every request, the first one included. No cookie
 * is kept from admission, so none can outlive the entry it was read for.
 */
internal class IosAdmittedRemote(
    val locator: PlaybackLocator.Remote,
    val lease: ByteSourceLease,
    val recheck: LeaseCookieRecheck,
)

/** Recovers the lease behind an ABI opaque pointer. Null only if the engine broke its ownership rules. */
private fun leaseOf(opaque: COpaquePointer?): IosStreamLease? = opaque?.asStableRef<IosStreamLease>()?.get()

private fun streamOpen(opaque: COpaquePointer?, size: CPointer<LongVar>?, seekable: CPointer<IntVar>?): Int {
    val lease = leaseOf(opaque) ?: return IO_FAILED
    if (size == null || seekable == null) return IO_FAILED
    return lease.openStream(size, seekable)
}

private fun streamRead(opaque: COpaquePointer?, buffer: CPointer<UByteVar>?, length: UInt): Int {
    val lease = leaseOf(opaque) ?: return IO_FAILED
    if (buffer == null) return IO_FAILED
    return lease.readStream(buffer, length)
}

private fun streamSeek(opaque: COpaquePointer?, position: Long): Long =
    leaseOf(opaque)?.seekStream(position) ?: IO_FAILED.toLong()

private fun streamInterrupt(opaque: COpaquePointer?) {
    leaseOf(opaque)?.interruptStream()
}

private fun streamClose(opaque: COpaquePointer?) {
    val reference = opaque?.asStableRef<IosStreamLease>() ?: return
    reference.get().closeStream()
    reference.dispose()
}

/**
 * The lease for one header-bearing or Deezer URL. Only its own origin is permitted, so a redirect elsewhere is refused and
 * its credentials never travel. The cache key drops the query, which carries per-request signatures.
 */
private fun leaseFor(
    provider: String,
    origin: StreamOrigin,
    url: String,
    expiresAtEpochMs: Long?,
): ByteSourceLease = ByteSourceLease(
    account = ByteSourceAccount(provider = provider, accountId = null),
    primaryOrigin = origin,
    permittedOrigins = setOf(origin),
    cacheKey = url.substringBefore('?'),
    expiresAtEpochMs = expiresAtEpochMs,
    lengthBytes = null,
)
