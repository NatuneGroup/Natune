package moe.rukamori.archivetune.shared.core

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import moe.rukamori.archivetune.shared.domain.lyrics.LyricsParser
import moe.rukamori.archivetune.shared.domain.lyrics.TtmlDocument

/** The same complete fixtures also run through cinterop on the iOS simulator. */
class TtmlFixtureParityTest {
    @Test fun retainedKotlinAndNativeReaderMatchSharedGoldens() = runTest {
        val reader = assertNotNull(rustTtmlReaderOrNull())
        ttmlFixtureCases.forEach { (id, raw, golden) ->
            val expected = Json.parseToJsonElement(golden)
            val kotlin = TtmlDocument(LyricsParser.parseTtml(raw), LyricsParser.parseTtmlCredits(raw))
            compare(expected, ttmlSnapshot(kotlin), "$id/Kotlin")
            compare(expected, ttmlSnapshot(reader.readTtml(raw)), "$id/Rust")
        }
    }

    private fun compare(a: JsonElement, b: JsonElement, path: String) {
        when {
            a is JsonObject && b is JsonObject -> {
                assertEquals(a.keys, b.keys, path)
                a.forEach { (key, value) -> compare(value, b.getValue(key), "$path/$key") }
            }
            a is JsonArray && b is JsonArray -> {
                assertEquals(a.size, b.size, path)
                a.indices.forEach { compare(a[it], b[it], "$path/$it") }
            }
            a is JsonPrimitive && b is JsonPrimitive && !a.isString && !b.isString &&
                a.doubleOrNull != null && b.doubleOrNull != null -> {
                if (a.longOrNull != null && b.longOrNull != null) assertEquals(a.long, b.long, path)
                else {
                    assertTrue(abs(a.double - b.double) <= 0.000001, path)
                    assertEquals((a.double * 1000).toLong(), (b.double * 1000).toLong(), "$path/ms")
                }
            }
            else -> assertEquals(a, b, path)
        }
    }
}

internal fun ttmlSnapshot(d: TtmlDocument): JsonObject = buildJsonObject {
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
