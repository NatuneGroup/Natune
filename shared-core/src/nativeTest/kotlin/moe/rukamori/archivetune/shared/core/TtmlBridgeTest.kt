package moe.rukamori.archivetune.shared.core

import java.io.File
import kotlin.system.measureNanoTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import moe.rukamori.archivetune.shared.core.bindings.CoreException
import moe.rukamori.archivetune.shared.core.bindings.ParseCancellation
import moe.rukamori.archivetune.shared.core.bindings.parseTtml
import moe.rukamori.archivetune.shared.domain.lyrics.*

class TtmlBridgeTest {
    private val repo = File(System.getProperty("natune.repo"))

    @Test fun retainedKotlinOracleAndNativeBindingAgree() = runBlocking {
        val reader = assertNotNull(rustTtmlReaderOrNull(), "The actual host native library must load")
        val inputs = Json.parseToJsonElement(File(repo, "native/core/fixtures/ttml-inputs.json").readText()).jsonArray
        val goldens = buildJsonObject {
            inputs.forEach { value ->
                val case = value.jsonObject
                val id = case.getValue("id").jsonPrimitive.content
                val raw = case.getValue("raw").jsonPrimitive.content
                // The retained two-parse API is the oracle, independent of the new reader.
                val expected = TtmlDocument(LyricsParser.parseTtml(raw), LyricsParser.parseTtmlCredits(raw))
                assertEquals(expected, KotlinTtmlDocumentReader.readTtml(raw), "single-parse Kotlin: $id")
                assertDocument(expected, reader.readTtml(raw), id)
                put(id, snapshot(expected))
            }
        }
        File(repo, "shared-core/build/ttml-kotlin-goldens.json").writeText(goldens.toString())
        println("TTML_KOTLIN_GOLDENS=$goldens")
        println("TTML_PARITY: ${inputs.size} retained/added fixtures through the real JVM native binding")
    }

    @Test fun nativeCancellationAndRepeatedRelease() {
        val cancelled = ParseCancellation()
        try {
            cancelled.cancel()
            assertFailsWith<CoreException.Cancelled> { parseTtml("<tt/>", cancelled) }
        } finally { cancelled.destroy() }
        repeat(200) {
            val token = ParseCancellation()
            try { assertEquals(0, parseTtml("<!DOCTYPE tt><tt/>", token).lines.size) }
            finally { token.destroy() }
        }
    }

    @Test fun generatedBoundsAndUtf16RefusalMatchTheKotlinReader() = runBlocking {
        val reader = assertNotNull(rustTtmlReaderOrNull())
        val bodies = listOf(
            "<tt><p begin=\"0\">" + "a".repeat(2_000_000) + "</p></tt>",
            "<tt>" + "<div>".repeat(200) + "<p begin=\"0\">Deep</p>" + "</div>".repeat(200) + "</tt>",
            "<tt><p begin=\"0\">Bound</p>" + "<a/>".repeat(100_000) + "</tt>",
            "<tt><p begin=\"0\">" + "𠀀".repeat(600_000) + "</p></tt>",
            "<tt><p begin=\"0\">" + '\uD800' + "</p></tt>",
            "<tt><p begin=\"0\">" + '\uDC00' + "</p></tt>",
        )
        bodies.forEachIndexed { index, raw ->
            assertDocument(TtmlDocument(LyricsParser.parseTtml(raw), LyricsParser.parseTtmlCredits(raw)), reader.readTtml(raw), "generated-bound-$index")
        }
    }

    @Test fun fullJvmPathTiming() = runBlocking {
        val loadStart = System.nanoTime()
        val reader = assertNotNull(rustTtmlReaderOrNull())
        val load = System.nanoTime() - loadStart
        val results = buildJsonArray {
            for (kib in listOf(5, 50, 500)) {
                val paragraph = """<p begin="10s" end="14s" ttm:agent="v1"><span begin="0s" end="1s">Hello </span><span begin="1s" end="2s">world</span><span role="x-bg" begin="2s" end="3s"> echo</span></p>"""
                val xml = "<tt><head><songwriter>Fixture Writer</songwriter></head><body><div>" +
                    paragraph.repeat(kib * 1024 / paragraph.length) + "</div></body></tt>"
                val samples = List(3) { mutableListOf<Long>() }
                var consumed = 0L
                repeat(65) { round ->
                    for (offset in 0..2) {
                        val kind = (round + offset) % 3 // Rotate order to reduce warmup/order bias.
                        val elapsed = measureNanoTime {
                            val doc = when (kind) {
                                0 -> TtmlDocument(LyricsParser.parseTtml(xml), LyricsParser.parseTtmlCredits(xml))
                                1 -> KotlinTtmlDocumentReader.readTtml(xml)
                                else -> reader.readTtml(xml)
                            }
                            consumed += doc.lines.sumOf { it.text.length.toLong() + it.time }
                        }
                        if (round >= 15) samples[kind].add(elapsed)
                    }
                }
                put(buildJsonObject {
                    put("syntheticBytes", xml.toByteArray().size)
                    put("consumed", consumed)
                    for ((index, label) in listOf("kotlinTwoParse", "kotlinOneParse", "rustIncludingBridge").withIndex()) {
                        val sorted = samples[index].sorted()
                        put(label, buildJsonObject { put("p50Us", sorted[25] / 1000); put("p95Us", sorted[47] / 1000) })
                    }
                })
            }
        }
        File(repo, "shared-core/build/ttml-jvm-benchmark.json").writeText(results.toString())
        println("TTML_JVM_LIBRARY_PROBE_NS=$load (test ordering means this may already be warm)")
        println("TTML_JVM_TIMINGS=$results")
    }

    private fun assertDocument(expected: TtmlDocument, actual: TtmlDocument, id: String) {
        assertEquals(expected.credits, actual.credits, id)
        assertEquals(expected.lines.size, actual.lines.size, id)
        expected.lines.zip(actual.lines).forEach { (a, b) ->
            assertEquals(snapshot(TtmlDocument(listOf(a))).jsonObject["lines"]?.jsonArray?.get(0)?.jsonObject?.minus("words"),
                snapshot(TtmlDocument(listOf(b))).jsonObject["lines"]?.jsonArray?.get(0)?.jsonObject?.minus("words"), id)
            assertEquals(a.words?.size, b.words?.size, id)
            a.words.orEmpty().zip(b.words.orEmpty()).forEach { (x, y) ->
                assertEquals(x.text, y.text, id)
                assertEquals(x.isBackground, y.isBackground, id)
                assertEquals(x.startTime, y.startTime, 0.000001, id)
                assertEquals(x.endTime, y.endTime, 0.000001, id)
                assertEquals((x.startTime * 1000).toLong(), (y.startTime * 1000).toLong(), id)
                assertEquals((x.endTime * 1000).toLong(), (y.endTime * 1000).toLong(), id)
            }
        }
    }

    private fun snapshot(d: TtmlDocument): JsonObject = buildJsonObject {
        put("credits", buildJsonObject {
            put("songwriters", JsonArray(d.credits.songwriters.map(::JsonPrimitive)))
            put("translationAutoCreated", d.credits.translationAutoCreated)
            put("transliterationAutoCreated", d.credits.transliterationAutoCreated)
        })
        put("lines", buildJsonArray {
            d.lines.forEach { line -> add(buildJsonObject {
                put("time", line.time); put("text", line.text); put("agent", line.agent?.let(::JsonPrimitive) ?: JsonNull)
                put("isInstrumental", line.isInstrumental); put("durationMs", line.durationMs)
                put("providerRomanizedText", line.providerRomanizedText?.let(::JsonPrimitive) ?: JsonNull)
                put("providerRomanizedLanguage", line.providerRomanizedLanguage?.let(::JsonPrimitive) ?: JsonNull)
                put("providerTranslationText", line.providerTranslationText?.let(::JsonPrimitive) ?: JsonNull)
                put("providerRomanizedWords", line.providerRomanizedWords?.let { JsonArray(it.map(::JsonPrimitive)) } ?: JsonNull)
                put("words", line.words?.let { words -> buildJsonArray {
                    words.forEach { word -> add(buildJsonObject {
                        put("text", word.text); put("startTime", word.startTime); put("endTime", word.endTime); put("isBackground", word.isBackground)
                    }) }
                } } ?: JsonNull)
            }) }
        })
    }
}
