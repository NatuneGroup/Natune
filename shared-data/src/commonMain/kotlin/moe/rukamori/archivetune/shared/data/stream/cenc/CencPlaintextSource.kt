package moe.rukamori.archivetune.shared.data.stream.cenc

import moe.rukamori.archivetune.shared.data.bytesource.ByteSourceLease
import moe.rukamori.archivetune.shared.data.bytesource.RandomAccessByteSource
import moe.rukamori.archivetune.shared.data.stream.aes.Aes128

/**
 * Presents a CENC fragmented MP4 as plaintext without staging a decrypted copy. Each read pulls ciphertext through the
 * lease, applies the metadata type patches, and XORs AES-CTR keystream over the protected sample bytes it covers.
 * Because CTR is length-preserving and random-access, no byte is ever decrypted twice and no temporary file exists.
 *
 * The keystream of a sample starts at its IV and advances only over protected bytes. The same counter block therefore
 * decrypts any byte at any position, which is what makes seeking safe. Like the other sources in this package, reads
 * are not safe to issue concurrently.
 */
internal class CencPlaintextSource(
    private val ciphertext: RandomAccessByteSource,
    private val map: CencPlaintextMap,
    key: ByteArray,
) : RandomAccessByteSource {
    override val lease: ByteSourceLease = ciphertext.lease

    private val aes = Aes128(key)
    private val counter = ByteArray(BLOCK_SIZE)
    private val keystream = ByteArray(BLOCK_SIZE)

    /** One-block cache so a read that starts mid-block does not repeat the AES call for the same counter. */
    private var keystreamIv: ByteArray? = null
    private var keystreamBlock = -1L

    override suspend fun readAt(
        position: Long,
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        if (length == 0) return 0
        val read = ciphertext.readAt(position, buffer, offset, length)
        // END_OF_STREAM and the zero-byte "not yet" answer both pass through unchanged.
        if (read <= 0) return read
        applyPatches(position, buffer, offset, read)
        applyKeystream(position, buffer, offset, read)
        return read
    }

    private fun applyPatches(
        position: Long,
        buffer: ByteArray,
        offset: Int,
        count: Int,
    ) {
        val end = position + count
        val patches = map.patches
        var index = firstPatchEndingAfter(patches, position)
        while (index < patches.size && patches[index].position < end) {
            val patch = patches[index]
            for (k in patch.type.indices) {
                val at = patch.position + k
                if (at >= position && at < end) buffer[offset + (at - position).toInt()] = patch.type[k]
            }
            index++
        }
    }

    private fun applyKeystream(
        position: Long,
        buffer: ByteArray,
        offset: Int,
        count: Int,
    ) {
        val end = position + count
        val runs = map.runs
        var index = firstRunEndingAfter(runs, position)
        while (index < runs.size && runs[index].start < end) {
            val run = runs[index]
            val stop = minOf(run.start + run.length, end)
            var at = maxOf(run.start, position)
            while (at < stop) {
                val streamPosition = run.streamOffset + (at - run.start)
                val block = streamPosition / BLOCK_SIZE
                val inBlock = (streamPosition % BLOCK_SIZE).toInt()
                val stream = keystreamFor(run.iv, block)
                val take = minOf(BLOCK_SIZE - inBlock, (stop - at).toInt())
                val target = offset + (at - position).toInt()
                for (k in 0 until take) {
                    buffer[target + k] = (buffer[target + k].toInt() xor stream[inBlock + k].toInt()).toByte()
                }
                at += take
            }
            index++
        }
    }

    /** The AES-CTR keystream block for [block] of a sample whose counter block starts at [iv]. */
    private fun keystreamFor(
        iv: ByteArray,
        block: Long,
    ): ByteArray {
        if (iv !== keystreamIv || block != keystreamBlock) {
            iv.copyInto(counter)
            addToCounter(block)
            aes.encryptBlock(counter, 0, keystream, 0)
            keystreamIv = iv
            keystreamBlock = block
        }
        return keystream
    }

    /** Adds [amount] to the 128-bit big-endian counter block with full carry, matching AES-CTR increments. */
    private fun addToCounter(amount: Long) {
        var carry = amount
        var index = BLOCK_SIZE - 1
        while (carry != 0L && index >= 0) {
            val sum = (counter[index].toLong() and 0xffL) + (carry and 0xffL)
            counter[index] = sum.toByte()
            carry = (carry ushr 8) + (sum ushr 8)
            index--
        }
    }

    private companion object {
        const val BLOCK_SIZE = 16

        /** The first run whose end is past [position]. Runs are sorted and do not overlap, so ends are sorted too. */
        fun firstRunEndingAfter(
            runs: Array<CencRun>,
            position: Long,
        ): Int {
            var low = 0
            var high = runs.size
            while (low < high) {
                val mid = (low + high) ushr 1
                val run = runs[mid]
                if (run.start + run.length > position) high = mid else low = mid + 1
            }
            return low
        }

        fun firstPatchEndingAfter(
            patches: Array<CencPatch>,
            position: Long,
        ): Int {
            var low = 0
            var high = patches.size
            while (low < high) {
                val mid = (low + high) ushr 1
                val patch = patches[mid]
                if (patch.position + patch.type.size > position) high = mid else low = mid + 1
            }
            return low
        }
    }
}
