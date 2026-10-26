/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 *
 * Modified source port of Kuromoji 0.9.0 dictionary/buffer/io/trie readers.
 * Copyright © 2010-2015 Atilika Inc. and contributors (see kuromoji-CONTRIBUTORS.md)
 * Licensed under the Apache License, Version 2.0. See kuromoji-LICENSE.txt.
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package moe.rukamori.archivetune.shared.domain.lyrics.romanization

/** Big-endian Kuromoji data, not Java serialization or executable dictionary code. */
internal class KuromojiBytes(val bytes: ByteArray, private val checkpoint: () -> Unit = {}) {
    var position = 0
    fun u16(at: Int): Int = ((bytes[at].toInt() and 255) shl 8) or (bytes[at + 1].toInt() and 255)
    fun short(at: Int): Int = u16(at).toShort().toInt()
    fun int(at: Int): Int = (u16(at) shl 16) or u16(at + 2)
    fun int(): Int = int(position).also { position += 4 }
    fun count(max: Int): Int = int().also { require(it in 0..max) { "Invalid Kuromoji array size" } }
    fun ints(): IntArray {
        val n = count((bytes.size - position - 4).coerceAtLeast(0) / 4)
        return IntArray(n) { i ->
            if (i % 4096 == 0) checkpoint()
            int()
        }
    }
    fun arrays(): Array<IntArray> = Array(count(bytes.size / 4)) { ints() }
    fun sparse(): Array<IntArray?> {
        val result = arrayOfNulls<IntArray>(count(65536))
        while (true) {
            checkpoint()
            val index = int()
            if (index == -1) return result
            require(index in result.indices && result[index] == null)
            result[index] = ints()
        }
    }
    // The only modified-UTF input used here is the ASCII category symbol array.
    fun categorySymbols(): List<String> = List(count(256)) {
        val n = u16(position)
        position += 2
        require(n <= bytes.size - position)
        val end = position + n
        require((position until end).all { bytes[it].toInt() in 1..127 })
        bytes.decodeToString(position, end).also { position = end }
    }
    fun finished() = require(position == bytes.size) { "Trailing Kuromoji data" }
}

internal class KuromojiDictionary(
    resources: Map<String, ByteArray>,
    checkpoint: () -> Unit,
) {
    private val compact: Boolean
    private val base: IntArray
    private val check: IntArray
    private val tail: CharArray
    // Retained random-access readers must not retain the construction coroutine or all raw resources.
    private val token = KuromojiBytes(requireNotNull(resources["tokenInfoDictionary.bin"]))
    private val features = KuromojiBytes(requireNotNull(resources["tokenInfoFeaturesMap.bin"]))
    private val connection = KuromojiBytes(requireNotNull(resources["connectionCosts.bin"]))
    private val indices: IntArray
    private val wordIds: IntArray
    val definitions: Array<IntArray?>
    private val mappings: Array<IntArray?>
    private val defaultCategory: IntArray
    val unknownCosts: Array<IntArray>
    val unknownReferences: Array<IntArray>
    private val connectionSize: Int
    private val entrySize: Int
    private val posCount: Int
    private val tokenInfoCount: Int

    init {
        val trie = KuromojiBytes(requireNotNull(resources["doubleArrayTrie.bin"]), checkpoint)
        compact = trie.bytes[0].toInt() == 1
        trie.position = 1
        val n = trie.count(trie.bytes.size / 8)
        val t = trie.count(trie.bytes.size / 2)
        base = IntArray(n) { i -> if (i % 4096 == 0) checkpoint(); trie.int() }
        check = IntArray(n) { i -> if (i % 4096 == 0) checkpoint(); trie.int() }
        tail = CharArray(t) { i ->
            if (i % 4096 == 0) checkpoint()
            trie.u16(trie.position).toChar().also { trie.position += 2 }
        }
        trie.finished()
        val target = KuromojiBytes(requireNotNull(resources["tokenInfoTargetMap.bin"]), checkpoint)
        indices = target.ints()
        wordIds = target.ints()
        target.finished()
        val characters = KuromojiBytes(requireNotNull(resources["characterDefinitions.bin"]), checkpoint)
        definitions = characters.sparse()
        mappings = characters.sparse()
        defaultCategory = intArrayOf(characters.categorySymbols().indexOf("DEFAULT").also { require(it >= 0) })
        characters.finished()
        val unknown = KuromojiBytes(requireNotNull(resources["unknownDictionary.bin"]), checkpoint)
        unknownCosts = unknown.arrays()
        unknownReferences = unknown.arrays()
        require(mappings.size == 65536)
        connectionSize = connection.int()
        require(connection.int() == connection.bytes.size - 8)
        require(connectionSize > 0 && connectionSize.toLong() * connectionSize * 2 == connection.bytes.size - 8L)
        require(token.int(0) == token.bytes.size - 4)
        tokenInfoCount = token.int(12)
        posCount = token.int(16)
        val featureCount = token.int(20)
        require(tokenInfoCount == 3 && posCount == 6 && featureCount == 3)
        entrySize = tokenInfoCount * 2 + posCount + featureCount * 4
        require(24L + token.int(8).toLong() * entrySize == token.bytes.size.toLong())
        require(features.int(0) == features.bytes.size - 4)
    }

    fun categories(c: Char): IntArray = mappings[c.code] ?: defaultCategory
    fun connectionCost(right: Int, left: Int): Int = connection.short(8 + 2 * (right * connectionSize + left))
    fun left(id: Int): Int = token.short(24 + id * entrySize)
    fun right(id: Int): Int = token.short(26 + id * entrySize)
    fun cost(id: Int): Int = token.short(28 + id * entrySize)

    fun reading(id: Int): String? {
        val featureId = token.int(24 + id * entrySize + tokenInfoCount * 2 + posCount + (7 - posCount) * 4)
        val address = features.int(8 + featureId * 4) + 4
        val length = features.u16(address)
        return features.bytes.decodeToString(address + 2, address + 2 + length)
            .takeUnless { it.isEmpty() || it == "*" }
    }

    fun wordIds(source: Int, emit: (Int) -> Unit) {
        val offset = indices[source]
        if (offset < 0) return
        repeat(wordIds[offset]) { emit(wordIds[offset + 1 + it]) }
    }

    /** Incremental equivalent of DoubleArrayTrie.lookup for every prefix, including compressed tails. */
    fun prefixes(text: String, start: Int, end: Int, step: () -> Unit, emit: (Int, Int) -> Unit) {
        var index = 0
        var b = 1
        var cursor = start
        while (cursor < end) {
            step()
            val previous = index
            index = (if (compact) 0 else index) + b + text[cursor].code
            if (index !in base.indices || base[index] == 0 || check[index] != previous) return
            b = base[index]
            cursor++
            if (b >= TAIL_OFFSET) {
                var t = b - TAIL_OFFSET
                while (t in tail.indices && tail[t] != '\u0001') {
                    step()
                    if (cursor >= end || text[cursor] != tail[t]) return
                    cursor++
                    t++
                }
                if (t in tail.indices) emit(index, cursor - start)
                return
            }
            val terminal = (if (compact) 0 else index) + b + 1
            if (terminal in check.indices && check[terminal] == index) emit(index, cursor - start)
        }
    }

    private companion object {
        const val TAIL_OFFSET = 100_000_000
    }
}
