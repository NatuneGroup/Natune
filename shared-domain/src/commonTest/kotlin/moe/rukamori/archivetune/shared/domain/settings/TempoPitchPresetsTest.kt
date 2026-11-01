package moe.rukamori.archivetune.shared.domain.settings

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TempoPitchPresetsTest {
    private val nightcore = TempoPitchPreset(id = "p1", name = "Nightcore", speed = 1.5f, pitch = 1.25f)
    private val slow = TempoPitchPreset(id = "p2", name = "Slow", speed = 0.75f, pitch = 0.8f)

    @Test
    fun codecRoundTripsThePresetList() {
        val raw = encodeTempoPitchPresets(listOf(nightcore, slow))
        assertEquals(listOf(nightcore, slow), parseTempoPitchPresets(raw))
    }

    @Test
    fun parseAcceptsMetrolistShapedJson() {
        val raw = """[{"id":"a","name":"Up","speed":1.5,"pitch":1.25},{"id":"b","name":"Down","speed":0.8,"pitch":1.0}]"""
        val presets = parseTempoPitchPresets(raw)
        assertEquals(listOf("a", "b"), presets.map { it.id })
        assertEquals(0.8f, presets[1].speed)
        assertEquals(1.0f, presets[1].pitch)
    }

    @Test
    fun parseToleratesBlankMalformedAndForeignJson() {
        assertEquals(emptyList(), parseTempoPitchPresets(null))
        assertEquals(emptyList(), parseTempoPitchPresets(""))
        assertEquals(emptyList(), parseTempoPitchPresets("   "))
        assertEquals(emptyList(), parseTempoPitchPresets("not-json"))
        assertEquals(emptyList(), parseTempoPitchPresets("[]"))
        assertEquals(emptyList(), parseTempoPitchPresets("""{"id":"a"}"""))
        assertEquals(emptyList(), parseTempoPitchPresets("""["a","b"]"""))
    }

    @Test
    fun parseDropsInvalidEntriesAndDuplicateIds() {
        val raw =
            """[
            {"id":"good","name":"Keep","speed":1.0,"pitch":1.0},
            {"id":"","name":"NoId","speed":1.0,"pitch":1.0},
            {"id":"blank","name":"  ","speed":1.0,"pitch":1.0},
            {"id":"fast","name":"TooFast","speed":2.5,"pitch":1.0},
            {"id":"low","name":"TooLow","speed":0.1,"pitch":1.0},
            {"id":"good","name":"Second","speed":1.1,"pitch":1.0}
            ]"""
        assertEquals(listOf(TempoPitchPreset("good", "Keep", 1.0f, 1.0f)), parseTempoPitchPresets(raw))
    }

    @Test
    fun parseIgnoresUnknownJsonFields() {
        val raw = """[{"id":"a","name":"Up","speed":1.5,"pitch":1.25,"extra":"field"}]"""
        assertEquals(listOf(TempoPitchPreset("a", "Up", 1.5f, 1.25f)), parseTempoPitchPresets(raw))
    }

    @Test
    fun encodeWritesOnlyValidPresets() {
        val raw = encodeTempoPitchPresets(listOf(nightcore, nightcore.copy(id = "", name = ""), nightcore.copy(speed = 0.1f)))
        assertEquals(listOf(nightcore), parseTempoPitchPresets(raw))
    }

    @Test
    fun validityRequiresIdentityAndInRangeValues() {
        assertTrue(nightcore.isValid)
        assertFalse(nightcore.copy(id = "").isValid)
        assertFalse(nightcore.copy(id = " ").isValid)
        assertFalse(nightcore.copy(name = "").isValid)
        assertFalse(nightcore.copy(name = " ").isValid)
        assertFalse(nightcore.copy(speed = TEMPO_PITCH_VALUE_MIN - 0.01f).isValid)
        assertFalse(nightcore.copy(speed = TEMPO_PITCH_VALUE_MAX + 0.01f).isValid)
        assertFalse(nightcore.copy(pitch = 0f).isValid)
        assertFalse(nightcore.copy(pitch = Float.NaN).isValid)
        assertFalse(nightcore.copy(speed = Float.POSITIVE_INFINITY).isValid)
        assertTrue(nightcore.copy(speed = TEMPO_PITCH_VALUE_MIN, pitch = TEMPO_PITCH_VALUE_MAX).isValid)
    }

    @Test
    fun applyWritesSpeedBeforePitch() {
        val order = mutableListOf<Pair<String, Float>>()
        assertTrue(nightcore.applyTo({ order += "speed" to it }, { order += "pitch" to it }))
        assertEquals(listOf("speed" to 1.5f, "pitch" to 1.25f), order)
    }

    @Test
    fun invalidPresetAppliesNothing() {
        var applied = false
        val invalid = nightcore.copy(speed = 0.1f)
        assertFalse(invalid.applyTo({ applied = true }, { applied = true }))
        assertFalse(applied)
    }

    @Test
    fun upsertAppendsAndReplacesInPlace() {
        val added = upsertTempoPitchPreset(emptyList(), nightcore)
        assertEquals(listOf(nightcore), added)
        val updated = nightcore.copy(name = "Renamed", speed = 1.4f)
        assertEquals(
            listOf(nightcore.copy(name = "Other"), slow),
            upsertTempoPitchPreset(listOf(nightcore, slow), nightcore.copy(name = "Other")),
        )
        assertEquals(listOf(updated, slow), upsertTempoPitchPreset(listOf(nightcore, slow), updated))
        assertEquals(
            listOf(nightcore, slow, slow.copy(id = "p9")),
            upsertTempoPitchPreset(listOf(nightcore, slow), slow.copy(id = "p9")),
        )
    }

    @Test
    fun upsertRejectsInvalidPresets() {
        assertEquals(listOf(nightcore), upsertTempoPitchPreset(listOf(nightcore), nightcore.copy(name = "")))
    }

    @Test
    fun renameTrimsAndGuards() {
        assertEquals(listOf(nightcore.copy(name = "Live")), renameTempoPitchPreset(listOf(nightcore), "p1", "  Live  "))
        assertEquals(listOf(nightcore), renameTempoPitchPreset(listOf(nightcore), "p1", "   "))
        assertEquals(listOf(nightcore), renameTempoPitchPreset(listOf(nightcore), "missing", "Live"))
        assertEquals(listOf(nightcore), renameTempoPitchPreset(listOf(nightcore), "", "Live"))
    }

    @Test
    fun removeDeletesOnlyTheMatchingId() {
        assertEquals(listOf(slow), removeTempoPitchPreset(listOf(nightcore, slow), "p1"))
        assertEquals(listOf(nightcore, slow), removeTempoPitchPreset(listOf(nightcore, slow), "missing"))
        assertEquals(listOf(nightcore, slow), removeTempoPitchPreset(listOf(nightcore, slow), ""))
    }

    @Test
    fun generatedIdsAreHexAndDistinct() {
        val random = Random(42)
        val first = newTempoPitchPresetId(random)
        val second = newTempoPitchPresetId(random)
        assertEquals(16, first.length)
        assertTrue(first.all { it in '0'..'9' || it in 'a'..'f' })
        assertNotEquals(first, second)
        assertTrue(TempoPitchPreset(first, "x", 1f, 1f).isValid)
    }
}
