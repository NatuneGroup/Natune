/*
 * Modified source port of Kuromoji 0.9.0 TokenizerBase and ViterbiBuilder/Searcher.
 * Copyright © 2010-2015 Atilika Inc. and contributors (see kuromoji-CONTRIBUTORS.md)
 * Licensed under the Apache License, Version 2.0. See kuromoji-LICENSE.txt.
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package moe.rukamori.archivetune.shared.domain.lyrics.romanization

/** Resource/work ceilings are failures, never partial successful token lists. */
data class KuromojiLimits(
    val maxInputCodeUnits: Int = 16_384,
    val maxNodes: Int = 100_000,
    val maxWork: Int = 2_000_000,
) {
    init {
        require(maxInputCodeUnits in 1..16_384)
        require(maxNodes in 1..100_000)
        require(maxWork in 1..2_000_000)
    }
}

/**
 * Kuromoji 0.9.0 NORMAL mode/IPADIC in common Kotlin. No user dictionary, SEARCH/EXTENDED modes,
 * debug lattice or compiler are included: the Android downloaded adapter uses none of those.
 * Instances own immutable dictionary data; each invocation owns its entire lattice.
 */
class PortableKuromojiJapaneseTokenizer internal constructor(
    private val dictionary: KuromojiDictionary,
    private val limits: KuromojiLimits,
) : JapaneseTokenizer {
    override fun tokenize(text: String): List<JapaneseToken> = tokenize(text) {}

    /** Pass e.g. a captured CoroutineContext.ensureActive for cancellable worker-side analysis. */
    fun tokenize(text: String, checkCancelled: () -> Unit): List<JapaneseToken> {
        checkCancelled()
        require(text.length <= limits.maxInputCodeUnits) { "Kuromoji input limit exceeded" }
        val budget = Budget(limits, checkCancelled)
        val result = ArrayList<JapaneseToken>()
        var start = 0
        // TokenizerBase's default sentence splitting includes the punctuation in the preceding lattice.
        for (i in text.indices) {
            if (text[i] == '。' || text[i] == '、') {
                result.addAll(sentence(text, start, i + 1, budget))
                start = i + 1
            }
        }
        if (start < text.length) result.addAll(sentence(text, start, text.length, budget))
        checkCancelled()
        return result
    }

    private fun sentence(text: String, offset: Int, end: Int, budget: Budget): List<JapaneseToken> {
        val ends = arrayOfNulls<MutableList<Node>>(end - offset + 1)
        ends[0] = arrayListOf(Node(-1, offset, offset, 0, false, 0L, null))
        var unknownEnd = -1
        for (start in offset until end) {
            budget.step()
            val previous = ends[start - offset] ?: continue
            fun add(id: Int, length: Int, known: Boolean) {
                budget.node()
                val costs = if (known) null else dictionary.unknownCosts[id]
                val left = costs?.get(0) ?: dictionary.left(id)
                val right = costs?.get(1) ?: dictionary.right(id)
                val wordCost = costs?.get(2) ?: dictionary.cost(id)
                var best: Node? = null
                var bestCost = Long.MAX_VALUE
                for (node in previous) {
                    budget.step()
                    val cost = node.pathCost + dictionary.connectionCost(node.right, left) + wordCost
                    if (cost < bestCost) {
                        bestCost = cost
                        best = node
                    }
                }
                val destination = start + length - offset
                val nodes = ends[destination] ?: arrayListOf<Node>().also { ends[destination] = it }
                nodes.add(Node(id, start, start + length, right, known, bestCost, best))
            }
            var found = false
            dictionary.prefixes(text, start, end, budget::step) { source, length ->
                found = true
                dictionary.wordIds(source) { add(it, length, known = true) }
            }
            // Preserve NORMAL's unknown-word suppression and category-slot grouping exactly.
            if (unknownEnd <= start) {
                dictionary.categories(text[start]).forEachIndexed { slot, category ->
                    val definition = requireNotNull(dictionary.definitions[category])
                    if (definition[0] == 1 || !found) {
                        var length = 1
                        if (definition[1] != 0) {
                            while (start + length < end) {
                                budget.step()
                                val categories = dictionary.categories(text[start + length])
                                if (slot >= categories.size || category != categories[slot]) break
                                length++
                            }
                        }
                        for (id in dictionary.unknownReferences[category]) add(id, length, known = false)
                        unknownEnd = start + length
                    }
                }
            }
        }
        var last: Node? = null
        var least = Long.MAX_VALUE
        for (node in requireNotNull(ends[end - offset]) { "Disconnected Kuromoji lattice" }) {
            budget.step()
            val cost = node.pathCost + dictionary.connectionCost(node.right, 0)
            if (cost < least) {
                least = cost
                last = node
            }
        }
        val result = ArrayList<JapaneseToken>()
        while (last != null && last.id != -1) {
            budget.step()
            result.add(JapaneseToken(text.substring(last.start, last.end),
                if (last.known) dictionary.reading(last.id) else null, last.start))
            last = last.previous
        }
        result.reverse()
        return result
    }

    private class Node(
        val id: Int,
        val start: Int,
        val end: Int,
        val right: Int,
        val known: Boolean,
        val pathCost: Long,
        val previous: Node?,
    )

    private class Budget(private val limits: KuromojiLimits, private val checkpoint: () -> Unit) {
        private var work = 0
        private var nodes = 0
        fun step() {
            require(++work <= limits.maxWork) { "Kuromoji work limit exceeded" }
            if (work % 1024 == 0) checkpoint()
        }
        fun node() = require(++nodes <= limits.maxNodes) { "Kuromoji node limit exceeded" }
    }
}
