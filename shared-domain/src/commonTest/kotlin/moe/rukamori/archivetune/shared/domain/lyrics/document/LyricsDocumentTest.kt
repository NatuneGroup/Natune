package moe.rukamori.archivetune.shared.domain.lyrics.document

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue
import moe.rukamori.archivetune.shared.domain.lyrics.LyricsParser

class LyricsDocumentTest {
    @Test fun plainTextPreservesBlankRowsAndSource() {
        val doc = LyricsDocument.parse("  Hello  \n\nworld")
        assertEquals(listOf("Hello", "world"), doc.segments.map { it.text })
        assertEquals("  Hello  \n  Bonjour  \n\nworld\nmonde", doc.rebuild(mapOf(0 to "Bonjour", 1 to "monde")))
    }

    @Test fun spacerWithSameTimestampDoesNotHideSungLineOrTranslateOldTranslation() {
        val doc = LyricsDocument.parse("[ar:Artist]\n[00:21.50]\n[00:21.50]Hello\n[00:21.50]Old translation")
        assertEquals(listOf("Hello"), doc.segments.map { it.text })
        assertEquals("[ar:Artist]\n[00:21.50]\n[00:21.50]Hello\n[00:21.50]Bonjour", doc.rebuild(mapOf(0 to "Bonjour")))
    }

    @Test fun enhancedTranslationDoesNotInventWordTiming() {
        val raw = "[00:01.00]<00:01.00>Hello <00:01.50>world<00:02.00>"
        val doc = LyricsDocument.parse(raw)
        assertEquals(listOf("Hello world"), doc.segments.map { it.text })
        assertEquals("$raw\n[00:01.00]Bonjour monde", doc.rebuild(mapOf(0 to "Bonjour monde")))
    }

    @Test fun repeatedLrcTimestampsAndOffsetArePreserved() {
        val raw = "[offset:50]\n[00:01.00][00:03.00]Hello"
        assertEquals("$raw\n[00:01.00][00:03.00]Bonjour", LyricsDocument.parse(raw).rebuild(mapOf(0 to "Bonjour")))
    }

    @Test fun qrcWrapperAndWordTimesSurvive() {
        val raw = """<QrcInfos><LyricInfo LyricContent="[ar:Artist]
[1000,1000]Hello(1000,400) world(1400,600)"/></QrcInfos>"""
        val doc = LyricsDocument.parse(raw)
        assertEquals(listOf("Hello world"), doc.segments.map { it.text })
        val rebuilt = doc.rebuild(mapOf(0 to "Bonjour & monde"))
        assertTrue(rebuilt.startsWith("<QrcInfos>"))
        assertTrue(rebuilt.contains("Hello(1000,400) world(1400,600)"))
        assertTrue(rebuilt.contains("[1000,1000]Bonjour &amp; monde"))
    }

    @Test fun ttmlTranslationKeepsTimingAgentsAndBackgroundSpans() {
        val raw = """<tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata"><body><div><p begin="1s" end="3s" ttm:agent="v2"><span begin="1s" end="2s">Hello</span><span ttm:role="x-bg" begin="2s" end="3s"> world</span></p></div></body></tt>"""
        val doc = LyricsDocument.parse(raw)
        assertEquals("Hello world", doc.segments.single().text)
        val rebuilt = doc.rebuild(mapOf(0 to "Bonjour & <monde>"))
        assertTrue(rebuilt.contains("begin=\"1s\""))
        assertTrue(rebuilt.contains("ttm:agent=\"v2\""))
        assertTrue(rebuilt.contains("ttm:role=\"x-bg\""))
        assertTrue(rebuilt.contains("Bonjour &amp; &#60;monde&#62;"))
        assertTrue(LyricsParser.parseTtml(raw).single().words!!.isNotEmpty())
        moe.rukamori.archivetune.shared.domain.lyrics.XmlTreeParser.parse(rebuilt)
        assertEquals(LyricsParser.parseTtml(raw).map { it.words }, LyricsParser.parseTtml(rebuilt).map { it.words }, rebuilt)
        assertEquals("Bonjour & <monde>", LyricsParser.parseTtml(rebuilt).single().providerTranslationText)
    }

    @Test fun ttmlRebuildIsRepeatableAndReplacesOnlyOwnMetadata() {
        val raw = """<tt><head><metadata><translation><text for="v">Provider</text></translation></metadata></head><body><p key="v" begin="1s">Hello</p></body></tt>"""
        val doc = LyricsDocument.parse(raw)
        assertEquals(doc.rebuild(mapOf(0 to "One")), doc.rebuild(mapOf(0 to "One")))
        val twice = LyricsDocument.parse(doc.rebuild(mapOf(0 to "One"))).rebuild(mapOf(0 to "Two"))
        assertTrue(twice.contains(">Provider<"))
        assertTrue(twice.contains(">Two<"))
        assertTrue(!twice.contains(">One<"))
    }

    @Test fun noChangesKeepExactInputAndUnchangedEchoIsNotAdded() {
        for (raw in listOf("Hello", "[00:01.00]Hello", "<tt><body><p>Hello</p></body></tt>")) {
            val doc = LyricsDocument.parse(raw)
            assertEquals(raw, doc.rebuild(emptyMap()))
            assertEquals(raw, doc.rebuild(mapOf(0 to "hello")))
        }
    }

    @Test fun prefixedTtmlRetainsItsStructuralNamespaceAndDoesNotTranslateMetadataParagraphs() {
        val raw = """<t:tt xmlns:t="http://www.w3.org/ns/ttml"><t:body><t:p begin="1s">Hello</t:p></t:body></t:tt>"""
        val rebuilt = LyricsDocument.parse(raw).rebuild(mapOf(0 to "Bonjour"))
        assertTrue(rebuilt.contains("<t:head><t:metadata>"))
        assertEquals("Bonjour", LyricsParser.parseTtml(rebuilt).single().providerTranslationText)
        val withMetadata = """<tt><head><metadata><p>Not sung</p></metadata></head><body><p begin="1s">Sung</p></body></tt>"""
        assertEquals(listOf("Sung"), LyricsDocument.parse(withMetadata).segments.map { it.text })
    }

    @Test fun refusesExternalEntitiesAndOversizedInput() {
        assertFails { LyricsDocument.parse("<!DOCTYPE tt [<!ENTITY x SYSTEM 'file:///private'>]><tt><p>&x;</p></tt>") }
        assertFails { LyricsDocument.parse("x".repeat(2_000_001)) }
    }
}
