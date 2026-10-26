package moe.rukamori.archivetune.shared.data.stream.qmc

/**
 * A keystream XORed in place at an absolute stream [position]. Every implementation depends only on that absolute
 * position, never on how earlier bytes were read, which is what lets a ranged read decrypt exactly its own bytes.
 */
internal interface QmcStreamCipher {
    fun decrypt(
        position: Int,
        buffer: ByteArray,
        bufferOffset: Int,
        length: Int,
    )
}

/**
 * QQ Music's fixed-keystream container (QMC1). The keystream is a 256-byte box indexed by `offset² + 27`, wrapping
 * every [PERIOD] bytes. The table and index arithmetic were cross-checked upstream against two independent open-source
 * implementations, and the baseline pins the wrap point.
 */
internal object QmcStaticCipher : QmcStreamCipher {
    /** The keystream restarts every 0x7FFF bytes; positions beyond one wrap are reduced first. */
    const val PERIOD = 0x7FFF

    private const val STATIC_BOX_HEX =
        "77483273def2c0c895ec30b251c3e1a09ee69dcffa7f14d1ceb8dcc34a6793d6" +
            "28c29170ca8da2a4f00861907e6fa2e0ebae3eb667c792f491b5f66c5e8440f7" +
            "f31b027fd5ab418928f425cc5211ad4368a6418b84b5ff2c924a26d8476a7c95" +
            "61cce6cbbb3f47588975c375a1d9afcc087317dcaa9aa21641d8a206c68bfc66" +
            "349fcf1823a00a74e72b277092e9af37e68ca7bc62659cc208c988b3f343ac74" +
            "2c0fd4afa1c30164954e489ff43578957a39d66aa06d40e84fa8ef111df31b3f" +
            "3f07dd6f5b193019fbef0e37f00ecd1649fe5347131abda4f14019600eed6809" +
            "065f4dcf3d1afe2077e4d9daf9a42b761c71db00bcfd0c6ca547f7f600794a11"

    private val box: ByteArray =
        ByteArray(STATIC_BOX_HEX.length / 2) { index ->
            STATIC_BOX_HEX.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }

    /** The keystream byte that cancels the cipher at [position]. */
    fun mask(position: Int): Int {
        val index = if (position > PERIOD) position % PERIOD else position
        val square = index.toLong() * index.toLong()
        return box[((square + 27L) and 0xFFL).toInt()].toInt() and 0xFF
    }

    override fun decrypt(
        position: Int,
        buffer: ByteArray,
        bufferOffset: Int,
        length: Int,
    ) {
        for (index in 0 until length) {
            buffer[bufferOffset + index] = (buffer[bufferOffset + index].toInt() xor mask(position + index)).toByte()
        }
    }
}

/**
 * QQ Music's short-key stream cipher (QMC2 with a key of at most 300 bytes). The key is first compressed to one
 * 128-byte table, each slot taking a key byte chosen by `slot² + 71214` and rotating it by that byte's own index. The
 * stream then reads that table at `position % 128`. Folding the position into the table, rather than into the key
 * lookup, is what keeps the cipher defined for keys of any length.
 */
internal class QmcMapCipher(
    key: ByteArray,
) : QmcStreamCipher {
    private val table: ByteArray

    init {
        require(key.isNotEmpty()) { "a map cipher needs a key" }
        table = ByteArray(TABLE_SIZE) { slot -> compressSlot(key, slot) }
    }

    /** The keystream byte that cancels the cipher at [position]. */
    fun mask(position: Int): Int {
        val index = if (position > QmcStaticCipher.PERIOD) position % QmcStaticCipher.PERIOD else position
        return table[index % TABLE_SIZE].toInt() and 0xFF
    }

    override fun decrypt(
        position: Int,
        buffer: ByteArray,
        bufferOffset: Int,
        length: Int,
    ) {
        for (index in 0 until length) {
            buffer[bufferOffset + index] = (buffer[bufferOffset + index].toInt() xor mask(position + index)).toByte()
        }
    }

    private companion object {
        const val TABLE_SIZE = 128

        fun compressSlot(
            key: ByteArray,
            slot: Int,
        ): Byte {
            val source = ((slot.toLong() * slot.toLong() + 71214L) % key.size).toInt()
            val value = key[source].toInt() and 0xFF
            val rotation = (source + 4) and 0b111
            return (((value shl rotation) or (value ushr rotation)) and 0xFF).toByte()
        }
    }
}

/**
 * QQ Music's long-key stream cipher (QMC2 with a key longer than 300 bytes): a modified RC4. The first 128 bytes use a
 * plain keyed XOR. Everything after runs through an RC4 keystream restarted for each 5120-byte segment and primed with
 * a count derived from the key. The segment walk is kept exactly, so a read that starts mid-segment reproduces the
 * bytes a single pass from the file start would produce.
 */
internal class QmcRc4Cipher(
    private val key: ByteArray,
) : QmcStreamCipher {
    private val size = key.size
    private val hash = hashBase(key)
    private val seedBox = ByteArray(size) { (it and 0xFF).toByte() }

    init {
        var j = 0
        for (i in 0 until size) {
            j = (j + (seedBox[i].toInt() and 0xFF) + (key[i].toInt() and 0xFF)) % size
            val swap = seedBox[i]
            seedBox[i] = seedBox[j]
            seedBox[j] = swap
        }
    }

    override fun decrypt(
        position: Int,
        buffer: ByteArray,
        bufferOffset: Int,
        length: Int,
    ) {
        var streamPosition = position
        var index = bufferOffset
        var remaining = length

        if (streamPosition < FIRST_SEGMENT_SIZE) {
            val count = minOf(remaining, FIRST_SEGMENT_SIZE - streamPosition)
            firstSegment(streamPosition, buffer, index, count)
            index += count
            remaining -= count
            streamPosition += count
        }
        if (remaining <= 0) return
        val toAlign = streamPosition % OTHER_SEGMENT_SIZE
        if (toAlign != 0) {
            val count = minOf(remaining, OTHER_SEGMENT_SIZE - toAlign)
            otherSegment(streamPosition, buffer, index, count)
            index += count
            remaining -= count
            streamPosition += count
        }
        while (remaining > OTHER_SEGMENT_SIZE) {
            otherSegment(streamPosition, buffer, index, OTHER_SEGMENT_SIZE)
            index += OTHER_SEGMENT_SIZE
            remaining -= OTHER_SEGMENT_SIZE
            streamPosition += OTHER_SEGMENT_SIZE
        }
        if (remaining > 0) otherSegment(streamPosition, buffer, index, remaining)
    }

    private fun firstSegment(
        position: Int,
        buffer: ByteArray,
        from: Int,
        count: Int,
    ) {
        var streamPosition = position
        for (index in from until from + count) {
            val seed = key[streamPosition % size].toInt() and 0xFF
            val shift = (segmentKey(streamPosition, seed) % size.toULong()).toInt()
            buffer[index] = (buffer[index].toInt() xor (key[shift].toInt() and 0xFF)).toByte()
            streamPosition++
        }
    }

    private fun otherSegment(
        position: Int,
        buffer: ByteArray,
        from: Int,
        count: Int,
    ) {
        val segment = position / OTHER_SEGMENT_SIZE
        val seed = key[segment % size].toInt() and 0xFF
        val discard = (segmentKey(segment, seed) and 0x1FFuL).toInt() + (position % OTHER_SEGMENT_SIZE)

        val state = seedBox.copyOf()
        var j = 0
        var k = 0
        repeat(discard) {
            j = (j + 1) % size
            k = ((state[j].toInt() and 0xFF) + k) % size
            val swap = state[j]
            state[j] = state[k]
            state[k] = swap
        }
        for (index in from until from + count) {
            j = (j + 1) % size
            k = ((state[j].toInt() and 0xFF) + k) % size
            val swap = state[j]
            state[j] = state[k]
            state[k] = swap
            val mask =
                state[((state[j].toInt() and 0xFF) + (state[k].toInt() and 0xFF)) % size].toInt() and 0xFF
            buffer[index] = (buffer[index].toInt() xor mask).toByte()
        }
    }

    /**
     * The per-segment prime count: a ratio of the key's hash base to the segment's place in the stream, in the widest
     * unsigned type the reference uses. A zero divisor saturates to the largest value, and callers reduce that value
     * themselves (by key length for a table index, to nine bits for a prime count). The two reductions disagree on the
     * saturation, so it is preserved here rather than folded away.
     */
    private fun segmentKey(
        id: Int,
        seed: Int,
    ): ULong {
        val divisor = (id.toLong() + 1L) * seed.toLong()
        if (divisor == 0L) return ULong.MAX_VALUE
        val value = hash.toDouble() / divisor.toDouble() * 100.0
        return if (value <= 0.0) 0uL else value.toULong()
    }

    companion object {
        private const val FIRST_SEGMENT_SIZE = 0x80
        private const val OTHER_SEGMENT_SIZE = 0x1400

        /**
         * A running product of the key's non-zero bytes, stopping as soon as it can no longer grow. The wrap-around the
         * reference's overflow check deliberately keeps is preserved.
         */
        fun hashBase(key: ByteArray): Long {
            var hash = 1L
            for (byte in key) {
                val value = byte.toLong() and 0xFFL
                if (value == 0L) continue
                val next = (hash * value) and 0xFFFFFFFFL
                if (next == 0L || next <= hash) break
                hash = next
            }
            return hash
        }
    }
}
