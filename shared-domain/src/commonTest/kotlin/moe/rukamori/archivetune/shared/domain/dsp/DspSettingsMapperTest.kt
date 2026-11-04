package moe.rukamori.archivetune.shared.domain.dsp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import moe.rukamori.archivetune.shared.domain.dsp.clarity.ClarityPreset
import moe.rukamori.archivetune.shared.domain.dsp.eq.EqualizerBand
import moe.rukamori.archivetune.shared.domain.dsp.eq.EqualizerBandLevels
import moe.rukamori.archivetune.shared.domain.dsp.eq.EqualizerBandType

/**
 * Every equalizer shape the app already stored reads back through one model, and the bus graph the unified
 * chain cannot restore is deliberately left out.
 */
class DspSettingsMapperTest {
    private fun band(type: EqualizerBandType, hz: Double, gainDb: Double, q: Double) =
        EqualizerBand(type = type, frequencyHz = hz, gainDb = gainDb, q = q)

    @Test
    fun theSharedMillibelLevelsBecomeBandsOnTheHostsOwnCentres() {
        val levels = EqualizerBandLevels.encode(listOf(600, -300))
        val bands = DspSettingsMapper.bandsFromLevelsMb(levels, listOf(100.0, 1_000.0))

        assertEquals(
            listOf(
                band(EqualizerBandType.PEAKING, 100.0, 6.0, DspSettingsMapper.GRAPHIC_BAND_Q),
                band(EqualizerBandType.PEAKING, 1_000.0, -3.0, DspSettingsMapper.GRAPHIC_BAND_Q),
            ),
            bands,
        )
    }

    @Test
    fun storedLevelsFromAnotherBandCountAreResampledNotTruncated() {
        val levels = EqualizerBandLevels.encode(listOf(1_200, -1_200))
        val bands = DspSettingsMapper.bandsFromLevelsMb(levels, listOf(60.0, 230.0, 910.0))

        assertEquals(listOf(12.0, 0.0, -12.0), bands.map { it.gainDb })
    }

    @Test
    fun aDamagedOrOversizedLevelListReadsAsFlatOrClampedInsteadOfFailing() {
        assertEquals(emptyList(), DspSettingsMapper.bandsFromLevelsMb(null, listOf(100.0)))
        assertEquals(emptyList(), DspSettingsMapper.bandsFromLevelsMb("", listOf(100.0)))
        assertEquals(listOf(0.0), DspSettingsMapper.bandsFromLevelsMb("not json", listOf(100.0)).map { it.gainDb })

        val oversized = EqualizerBandLevels.encode(listOf(9_999))
        val clamped = DspSettingsMapper.bandsFromLevelsMb(oversized, listOf(100.0))
        assertEquals(EqualizerBand.MAX_GAIN_DB, clamped.single().gainDb)
    }

    @Test
    fun theLegacyFifteenGainsLandOnTheirIsoCentres() {
        val gains = (1..15).joinToString(",") { it.toString() }
        val bands = DspSettingsMapper.bandsFromLastWaveGains(gains)

        assertEquals(DspSettingsMapper.LAST_WAVE_BAND_FREQUENCIES_HZ, bands.map { it.frequencyHz })
        assertEquals((1..15).map { it.toDouble() }, bands.map { it.gainDb })
        assertTrue(bands.all { it.type == EqualizerBandType.PEAKING && it.q == DspSettingsMapper.GRAPHIC_BAND_Q })
    }

    @Test
    fun aLegacyListOfTheWrongWidthIsNotGuessedAt() {
        assertEquals(emptyList(), DspSettingsMapper.bandsFromLastWaveGains(null))
        assertEquals(emptyList(), DspSettingsMapper.bandsFromLastWaveGains((1..14).joinToString(",")))
        assertEquals(emptyList(), DspSettingsMapper.bandsFromLastWaveGains((1..16).joinToString(",")))
    }

    @Test
    fun aLegacyGainBeyondTheChainLimitIsClamped() {
        val gains = List(15) { if (it == 9) "99" else "0" }.joinToString(",")
        val bands = DspSettingsMapper.bandsFromLastWaveGains(gains)

        assertEquals(EqualizerBand.MAX_GAIN_DB, bands[9].gainDb)
        assertEquals(0.0, bands[0].gainDb)
    }

    @Test
    fun tryptifyParametricBandsKeepTheirShapeAndDropWhatItCannotRun() {
        val raw = """
            [
              {"type":"LOWSHELF","freq":120,"gain":-3,"q":0.7,"enabled":true},
              {"type":"HIGHSHELF","freq":9000,"gain":2.5,"q":0.8},
              {"freq":1000,"gain":40,"q":0.05},
              {"type":"NOTCH","freq":300,"gain":1,"q":1},
              {"type":"PEAKING","gain":1,"q":1},
              {"type":"PEAKING","freq":2000,"gain":1,"q":1,"enabled":false}
            ]
        """.trimIndent()
        val bands = DspSettingsMapper.bandsFromTryptifyJson(raw)

        assertEquals(3, bands.size)
        assertEquals(band(EqualizerBandType.LOW_SHELF, 120.0, -3.0, 0.7), bands[0])
        assertEquals(band(EqualizerBandType.HIGH_SHELF, 9_000.0, 2.5, 0.8), bands[1])
        // A band without a shape is a peaking band, and both its gain and its Q stay inside the chain's range.
        assertEquals(EqualizerBandType.PEAKING, bands[2].type)
        assertEquals(EqualizerBand.MAX_GAIN_DB, bands[2].gainDb)
        assertEquals(0.1, bands[2].q)
    }

    @Test
    fun aStoredTryptifyListIsCappedAtTheChainWidth() {
        val raw = (0 until DspSettings.MAX_EQUALIZER_BANDS + 4).joinToString(",", "[", "]") { index ->
            """{"type":"PEAKING","freq":${100 + index * 10},"gain":1,"q":1}"""
        }
        val bands = DspSettingsMapper.bandsFromTryptifyJson(raw)

        assertEquals(DspSettings.MAX_EQUALIZER_BANDS, bands.size)
        assertEquals(100.0, bands.first().frequencyHz)
    }

    @Test
    fun anUnreadableTryptifyDocumentReadsAsNoBands() {
        assertEquals(emptyList(), DspSettingsMapper.bandsFromTryptifyJson(null))
        assertEquals(emptyList(), DspSettingsMapper.bandsFromTryptifyJson("{"))
        assertEquals(emptyList(), DspSettingsMapper.bandsFromTryptifyJson("""{"bands":[]}"""))
    }

    @Test
    fun theClarityIndexOnlyAppliesWhileItsSwitchIsOn() {
        assertEquals(ClarityPreset.DAC, DspSettingsMapper.clarityFromStored(enabled = true, presetIndex = 3))
        assertEquals(ClarityPreset.REFERENCE, DspSettingsMapper.clarityFromStored(enabled = true, presetIndex = 9))
        assertNull(DspSettingsMapper.clarityFromStored(enabled = false, presetIndex = 3))
    }

    @Test
    fun theParametricListWinsOverTheGraphicOneAndBothWinOverTheLegacyList() {
        val parametric = """[{"type":"PEAKING","freq":500,"gain":4,"q":1}]"""
        val graphic = """[{"type":"PEAKING","freq":1000,"gain":2,"q":1}]"""
        val legacy = (1..15).joinToString(",") { "0" }

        val both = DspSettingsMapper.fromPersisted(
            PersistedDspValues(
                tryptifyEqualizerEnabled = true,
                tryptifyParametricBandsJson = parametric,
                tryptifyGraphicBandsJson = graphic,
                lastWaveEqualizerEnabled = true,
                lastWaveGainsDb = legacy,
            ),
            listOf(100.0, 1_000.0),
        )
        assertEquals(listOf(500.0), both.equalizerBands.map { it.frequencyHz })

        val graphicOnly = DspSettingsMapper.fromPersisted(
            PersistedDspValues(
                tryptifyEqualizerEnabled = true,
                tryptifyGraphicBandsJson = graphic,
                lastWaveEqualizerEnabled = true,
                lastWaveGainsDb = legacy,
            ),
            listOf(100.0, 1_000.0),
        )
        assertEquals(listOf(1_000.0), graphicOnly.equalizerBands.map { it.frequencyHz })

        val legacyOnly = DspSettingsMapper.fromPersisted(
            PersistedDspValues(lastWaveEqualizerEnabled = true, lastWaveGainsDb = legacy),
            listOf(100.0, 1_000.0),
        )
        assertEquals(DspSettingsMapper.LAST_WAVE_BAND_FREQUENCIES_HZ, legacyOnly.equalizerBands.map { it.frequencyHz })
        assertTrue(legacyOnly.equalizerEnabled)
    }

    @Test
    fun theSharedLevelsAreTheFallbackWhateverTheOtherSwitchesSay() {
        val settings = DspSettingsMapper.fromPersisted(
            PersistedDspValues(
                equalizerBandLevelsMb = EqualizerBandLevels.encode(listOf(-600, 600)),
                equalizerEnabled = true,
                tryptifyEqualizerEnabled = true,
            ),
            listOf(100.0, 1_000.0),
        )
        assertEquals(listOf(-6.0, 6.0), settings.equalizerBands.map { it.gainDb })
        assertTrue(settings.equalizerEnabled)
    }

    @Test
    fun thePreampArrivesFromMillibelsWithinTheChainLimit() {
        val settings = DspSettingsMapper.fromPersisted(
            PersistedDspValues(equalizerOutputGainMb = -350),
            emptyList(),
        )
        assertEquals(-3.5, settings.preampDb)

        val clamped = DspSettingsMapper.fromPersisted(
            PersistedDspValues(equalizerOutputGainMb = 2_500),
            emptyList(),
        )
        assertEquals(DspSettings.MAX_PREAMP_DB, clamped.preampDb)
    }

    @Test
    fun theNativeFloatChainOwnsTheBlockerLimiterAndDither() {
        val on = DspSettingsMapper.fromPersisted(PersistedDspValues(floatDspEnabled = true), emptyList())
        assertTrue(on.dcBlockerEnabled)
        assertTrue(on.limiterEnabled)
        assertEquals(DspDither(enabled = true), on.dither)

        val off = DspSettingsMapper.fromPersisted(PersistedDspValues(floatDspEnabled = false), emptyList())
        assertEquals(false, off.dcBlockerEnabled)
        assertEquals(false, off.limiterEnabled)
        assertEquals(DspDither.OFF, off.dither)
    }

    @Test
    fun storedSpeedAndPitchArriveBounded() {
        val fast = DspSettingsMapper.fromPersisted(
            PersistedDspValues(playbackSpeed = 2f, playbackPitch = 2f, transitionGain = 9f),
            emptyList(),
        )
        assertEquals(2.0, fast.speed)
        assertEquals(12.0, fast.pitchSemitones, 1e-9)
        assertEquals(DspSettings.MAX_TRANSITION_GAIN, fast.transitionGain)

        val outOfRange = DspSettingsMapper.fromPersisted(
            PersistedDspValues(playbackSpeed = 0.1f, playbackPitch = 0f, transitionGain = -1f),
            emptyList(),
        )
        assertEquals(0.5, outOfRange.speed)
        assertEquals(0.0, outOfRange.pitchSemitones, 1e-12)
        assertEquals(0f, outOfRange.transitionGain)
    }

    @Test
    fun aNeutralStoredStateIsSampleTransparent() {
        val settings = DspSettingsMapper.fromPersisted(PersistedDspValues(), emptyList())

        assertEquals(DspSettings(equalizerEnabled = false, normalizationEnabled = false), settings)
        assertTrue(settings.isNeutral)
    }
}
