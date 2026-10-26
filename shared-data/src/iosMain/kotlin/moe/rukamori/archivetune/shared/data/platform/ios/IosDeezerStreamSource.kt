/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.data.platform.ios

import kotlinx.coroutines.runBlocking
import moe.rukamori.archivetune.shared.data.bytesource.GuardedByteSource
import moe.rukamori.archivetune.shared.data.bytesource.LeaseCancellation
import moe.rukamori.archivetune.shared.data.stream.deezer.DeezerChunkByteSource

/**
 * The plaintext of one Deezer CDN track, served to the engine through [IosStreamLease]. The common [DeezerChunkByteSource]
 * does the decryption over the header lease that [ciphertext] owns, so this file adds no cipher of its own and writes no
 * file. Deezer keeps the length and the cipher preserves it, so the plaintext probe is the ciphertext probe.
 *
 * Only the producer thread reads. The common reader is suspending, but nothing in this chain suspends, so [runBlocking]
 * serves it on that thread without dispatching. It must never run on the main thread.
 */
internal class IosDeezerStreamSource(
    private val ciphertext: IosHttpRangeSource,
    key: ByteArray,
) : IosLeaseSource {
    private val plaintext = DeezerChunkByteSource(ciphertext, key)

    // A ticket is issued per read, as the HTTP source does, so an interrupt discards only the read that was in flight.
    private val cancellation = LeaseCancellation()

    override fun probe(): IosHttpRangeSource.Probe = ciphertext.probe()

    override fun readBlocking(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        val guarded = GuardedByteSource(plaintext, cancellation.issueTicket())
        return runBlocking { guarded.readAt(position, buffer, offset, length) }
    }

    override fun interrupt() {
        cancellation.cancelAll()
        ciphertext.interrupt()
    }

    override fun close() {
        cancellation.cancelAll()
        ciphertext.close()
    }
}
