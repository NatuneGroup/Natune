package app.natune.lyrics

import app.natune.shared.domain.lyrics.TTML_GROUP_AGENT
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

    @Test
    fun parsedTtmlLinesCarryTheirSingerNames() {
        val named =
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
              <head><metadata>
                <ttm:agent type="person" xml:id="v1"><ttm:name type="full">Alice</ttm:name></ttm:agent>
                <ttm:agent type="person" xml:id="v2"><ttm:name type="full">Bob</ttm:name></ttm:agent>
              </metadata></head>
              <body><div>
                <p begin="00:00:01.000" end="00:00:02.000" ttm:agent="v1">lead</p>
                <p begin="00:00:03.000" end="00:00:04.000" ttm:agent="v1 v2">together</p>
              </div></body>
            </tt>
            """.trimIndent()
        assertEquals(
            listOf(listOf("Alice"), listOf("Alice", "Bob")),
            LyricsUtils.parseTtml(named).map { it.singers },
        )
    }
}
