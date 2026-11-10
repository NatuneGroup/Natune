package moe.rukamori.archivetune.shared.domain.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TtmlAgentTest {
    @Test
    fun aSingleAgentIdPassesThrough() {
        assertEquals("v1", normalizeTtmlAgent("v1"))
        assertEquals("v2", normalizeTtmlAgent("  v2 "))
        assertEquals("v1000", normalizeTtmlAgent("v1000"))
    }

    @Test
    fun aFragmentReferenceDropsItsHash() {
        assertEquals("v2", normalizeTtmlAgent("#v2"))
    }

    @Test
    fun severalDistinctAgentsBecomeAGroupLine() {
        assertEquals(TTML_GROUP_AGENT, normalizeTtmlAgent("v1 v2"))
        assertEquals(TTML_GROUP_AGENT, normalizeTtmlAgent("#v1\t#v2"))
    }

    @Test
    fun aRepeatedAgentIsStillOneVoice() {
        assertEquals("v2", normalizeTtmlAgent("v2 #v2 V2"))
    }

    @Test
    fun anEmptyReferenceIsNoAgent() {
        assertNull(normalizeTtmlAgent(null))
        assertNull(normalizeTtmlAgent(""))
        assertNull(normalizeTtmlAgent("   "))
        assertNull(normalizeTtmlAgent("#"))
    }

    @Test
    fun theParserNormalizesParagraphAgents() {
        val ttml =
            """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
              <body>
                <div>
                  <p begin="1.000" end="2.000" ttm:agent="#v2"><span begin="1.000" end="2.000">Hi</span></p>
                  <p begin="3.000" end="4.000" ttm:agent="v1 v2"><span begin="3.000" end="4.000">Both</span></p>
                </div>
              </body>
            </tt>
            """.trimIndent()

        assertEquals(listOf("v2", TTML_GROUP_AGENT), LyricsParser.parseTtml(ttml).map { it.agent })
    }
}
