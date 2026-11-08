package moe.rukamori.archivetune.modules

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ModuleLyricsTest {
    @Test fun parsesEachLyricsFormatWithoutRewritingTiming() {
        for ((format, text) in listOf("lrc" to "[00:01.25]Hello", "ttml" to "<tt><body><p begin=\"1s\">Hello</p></body></tt>", "plain" to "Hello\nWorld")) {
            val result = ModuleLyrics.parse(JSONObject().put("format", format).put("text", text).put("language", "en"))!!
            assertEquals(format.uppercase(), result.format.name)
            assertEquals(text, result.text)
            assertEquals("en", result.language)
        }
    }

    @Test fun nullIsAProviderMiss() {
        assertNull(ModuleLyrics.parse(null))
        assertNull(ModuleLyrics.parse(JSONObject.NULL))
    }

    @Test fun rejectsInvalidAndOversizedDocuments() {
        for (value in listOf("lyrics", JSONObject().put("format", "html").put("text", "Hello"),
            JSONObject().put("format", "lrc").put("text", 123), JSONObject().put("format", "plain").put("text", " "),
            JSONObject().put("format", "plain").put("text", "é".repeat(ModuleLyrics.MAX_TEXT_BYTES / 2 + 1)),
            JSONObject().put("format", "plain").put("text", "Hello").put("language", 1))) {
            assertThrows(ModuleException::class.java) { ModuleLyrics.parse(value) }
        }
    }
}
