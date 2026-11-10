package moe.rukamori.archivetune.lyrics

import moe.rukamori.archivetune.shared.domain.lyrics.TTML_GROUP_AGENT
import org.junit.Assert.assertEquals
import org.junit.Test

class TtmlAgentParseTest {
    private val ttml =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata" xml:lang="en">
          <body>
            <div>
              <p begin="00:00:01.000" end="00:00:02.000" ttm:agent="v1">
                <span begin="00:00:01.000" end="00:00:02.000">lead</span>
              </p>
              <p begin="00:00:03.000" end="00:00:04.000" ttm:agent="#v2">
                <span begin="00:00:03.000" end="00:00:04.000">answer</span>
              </p>
              <p begin="00:00:05.000" end="00:00:06.000" ttm:agent="v1 v2">
                <span begin="00:00:05.000" end="00:00:06.000">together</span>
              </p>
              <p begin="00:00:07.000" end="00:00:08.000" ttm:agent="v2 V2">
                <span begin="00:00:07.000" end="00:00:08.000">two voices</span>
              </p>
            </div>
          </body>
        </tt>
        """.trimIndent()

    @Test
    fun parsedTtmlLinesCarryOneBareAgentId() {
        assertEquals(
            listOf("v1", "v2", TTML_GROUP_AGENT, TTML_GROUP_AGENT),
            LyricsUtils.parseTtml(ttml).map { it.agent },
        )
    }
}
