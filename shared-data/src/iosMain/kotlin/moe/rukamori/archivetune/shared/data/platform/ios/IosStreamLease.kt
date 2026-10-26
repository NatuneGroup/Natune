@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package moe.rukamori.archivetune.shared.data.platform.ios

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.cinterop.*
import cnames.structs.at_audio_output
import moe.rukamori.archivetune.nativeaudio.*
import cnames.structs.at_audio_output
import moe.rukamori.archivetune.shared.data.bytesource.ByteSourceAccount
import moe.rukamori.archivetune.shared.data.bytesource.ByteSourceLease
import moe.rukamori.archivetune.shared.data.bytesource.RandomAccessByteSource
import moe.rukamori.archivetune.shared.data.bytesource.StreamOrigin
import moe.rukamori.archivetune.shared.data.stream.deezer.DeezerCrypto
import moe.rukamori.archivetune.shared.domain.playback.PlaybackLocator
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
internal class IosStreamLease private constructor(
    private val source: IosLeaseSource,
) {
    // Producer-thread state. The engine opens once, then reads and seeks in sequence.
    private var cursor = 0L
    private var seekable = false

    // Set when the engine closes the lease. A refusal that never reached the close path leaves the StableRef to the caller.
    private var released = false

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
        // The engine has no retry for open, so a failed probe is final for this load.
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

    /** Called once, after the engine has retracted the lease. Releases the connection. */
    internal fun closeStream() {
        released = true
        source.close()
    }

    companion object {
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
                leaseFor(PLAYBACK_PROVIDER, origin, locator.url),
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
                leaseFor(DEEZER_PROVIDER, origin, locator.url),
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
private fun leaseFor(provider: String, origin: StreamOrigin, url: String): ByteSourceLease = ByteSourceLease(
    account = ByteSourceAccount(provider = provider, accountId = null),
    primaryOrigin = origin,
    permittedOrigins = setOf(origin),
    cacheKey = url.substringBefore('?'),
    expiresAtEpochMs = null,
    lengthBytes = null,
)
