package moe.rukamori.archivetune.shared.domain.dsp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
                tryptifyParametricStageEnabled = true,
                tryptifyGraphicStageEnabled = true,
                tryptifyParametricBandsJson = parametric,
                tryptifyGraphicBandsJson = graphic,
                lastWaveEngineEnabled = true,
                lastWaveEqualizerEnabled = true,
                lastWaveGainsDb = legacy,
            ),
            listOf(100.0, 1_000.0),
        )
        assertEquals(listOf(500.0), both.equalizerBands.map { it.frequencyHz })

        val graphicOnly = DspSettingsMapper.fromPersisted(
            PersistedDspValues(
                tryptifyEqualizerEnabled = true,
                tryptifyGraphicStageEnabled = true,
                tryptifyGraphicBandsJson = graphic,
                lastWaveEngineEnabled = true,
                lastWaveEqualizerEnabled = true,
                lastWaveGainsDb = legacy,
            ),
            listOf(100.0, 1_000.0),
        )
        assertEquals(listOf(1_000.0), graphicOnly.equalizerBands.map { it.frequencyHz })
        assertEquals(listOf(2.0), graphicOnly.equalizerBands.map { it.gainDb })

        val legacyOnly = DspSettingsMapper.fromPersisted(
            PersistedDspValues(lastWaveEngineEnabled = true, lastWaveEqualizerEnabled = true, lastWaveGainsDb = legacy),
            listOf(100.0, 1_000.0),
        )
        assertEquals(DspSettingsMapper.LAST_WAVE_BAND_FREQUENCIES_HZ, legacyOnly.equalizerBands.map { it.frequencyHz })
        assertTrue(legacyOnly.equalizerEnabled)
    }

    @Test
    fun onlyTheStagesThatWereSwitchedOnMigrate() {
        val parametric = """[{"type":"PEAKING","freq":500,"gain":4,"q":1}]"""
        val graphic = """[{"type":"PEAKING","freq":1000,"gain":2,"q":1}]"""
        val levels = EqualizerBandLevels.encode(listOf(-600, 600))

        // A stage that was switched off keeps its stored curve, and the shared levels answer instead.
        val parametricOff = DspSettingsMapper.fromPersisted(
            PersistedDspValues(
                tryptifyEqualizerEnabled = true,
                tryptifyParametricStageEnabled = false,
                tryptifyGraphicStageEnabled = true,
                tryptifyParametricBandsJson = parametric,
                tryptifyGraphicBandsJson = graphic,
                equalizerBandLevelsMb = levels,
                equalizerEnabled = true,
            ),
            listOf(100.0, 1_000.0),
        )
        assertEquals(listOf(1_000.0), parametricOff.equalizerBands.map { it.frequencyHz })

        // Both stages off: neither stored curve runs, even with the engine still switched on.
        val stageOff = DspSettingsMapper.fromPersisted(
            PersistedDspValues(
                tryptifyEqualizerEnabled = true,
                tryptifyParametricBandsJson = parametric,
                tryptifyGraphicBandsJson = graphic,
                equalizerBandLevelsMb = levels,
                equalizerEnabled = true,
            ),
            listOf(100.0, 1_000.0),
        )
        assertEquals(listOf(-6.0, 6.0), stageOff.equalizerBands.map { it.gainDb })
    }

    @Test
    fun aDormantEnginesPresetsStayInactive() {
        val parametric = """[{"type":"PEAKING","freq":500,"gain":4,"q":1}]"""
        val levels = EqualizerBandLevels.encode(listOf(-600, 600))

        val engineOff = DspSettingsMapper.fromPersisted(
            PersistedDspValues(
                tryptifyEqualizerEnabled = false,
                tryptifyParametricStageEnabled = true,
                tryptifyParametricBandsJson = parametric,
                equalizerBandLevelsMb = levels,
            ),
            listOf(100.0, 1_000.0),
        )
        assertEquals(listOf(-6.0, 6.0), engineOff.equalizerBands.map { it.gainDb })

        val lastWaveEngineOff = DspSettingsMapper.fromPersisted(
            PersistedDspValues(
                lastWaveEngineEnabled = false,
                lastWaveEqualizerEnabled = true,
                lastWaveGainsDb = (1..15).joinToString(",") { "3" },
                clarityEnabled = true,
                clarityPresetIndex = 3,
            ),
            listOf(100.0),
        )
        assertNull(lastWaveEngineOff.clarity)
        assertTrue(lastWaveEngineOff.equalizerBands.isEmpty(), "the dormant curve contributes no bands")

        val lastWaveEngineOn = DspSettingsMapper.fromPersisted(
            PersistedDspValues(lastWaveEngineEnabled = true, clarityEnabled = true, clarityPresetIndex = 3),
            emptyList(),
        )
        assertEquals(ClarityPreset.DAC, lastWaveEngineOn.clarity)
    }

    @Test
    fun aStagesPreampMigratesWithTheStage() {
        val parametric = """[{"type":"PEAKING","freq":500,"gain":4,"q":1}]"""
        val graphic = """[{"type":"PEAKING","freq":1000,"gain":2,"q":1}]"""

        val parametricStage = DspSettingsMapper.fromPersisted(
            PersistedDspValues(
                tryptifyEqualizerEnabled = true,
                tryptifyParametricStageEnabled = true,
                tryptifyParametricBandsJson = parametric,
                tryptifyParametricPreampDb = -2.5,
                tryptifyGraphicPreampDb = -6.0,
                equalizerOutputGainMb = -100,
            ),
            emptyList(),
        )
        assertEquals(-3.5, parametricStage.preampDb, 1e-9)

        val graphicStage = DspSettingsMapper.fromPersisted(
            PersistedDspValues(
                tryptifyEqualizerEnabled = true,
                tryptifyGraphicStageEnabled = true,
                tryptifyGraphicBandsJson = graphic,
                tryptifyParametricPreampDb = -2.5,
                tryptifyGraphicPreampDb = -6.0,
                equalizerOutputGainMb = -100,
            ),
            emptyList(),
        )
        assertEquals(-7.0, graphicStage.preampDb, 1e-9)
    }

    /**
     * The legacy parametric stage applied its own preamp whether or not it also held a band curve, so a preset
     * with no bands but a -6 dB preamp keeps its gain. Activation reads the engine and stage gates, not the
     * decoded band list.
     */
    @Test
    fun aStagePreampSurvivesWithoutAnyBand() {
        val preampOnly = DspSettingsMapper.fromPersisted(
            PersistedDspValues(
                tryptifyEqualizerEnabled = true,
                tryptifyParametricStageEnabled = true,
                tryptifyParametricBandsJson = "[]",
                tryptifyParametricPreampDb = -6.0,
            ),
            listOf(100.0, 1_000.0),
        )
        assertEquals(-6.0, preampOnly.preampDb, 1e-9)
        assertTrue(preampOnly.preampEnabled, "the migrated preamp must render without its own switch")
        assertTrue(preampOnly.equalizerBands.isEmpty(), "a preamp-only preset migrates no band")

        // The same gates still keep a dormant stage's stored preamp inactive.
        val dormant = DspSettingsMapper.fromPersisted(
            PersistedDspValues(
                tryptifyEqualizerEnabled = false,
                tryptifyParametricStageEnabled = true,
                tryptifyParametricBandsJson = "[]",
                tryptifyParametricPreampDb = -6.0,
            ),
            emptyList(),
        )
        assertEquals(0.0, dormant.preampDb, 1e-9)

        // And the graphic stage's preamp survives the same way when only it is switched on.
        val graphicOnly = DspSettingsMapper.fromPersisted(
            PersistedDspValues(
                tryptifyEqualizerEnabled = true,
                tryptifyGraphicStageEnabled = true,
                tryptifyGraphicBandsJson = "[]",
                tryptifyGraphicPreampDb = -3.0,
            ),
            emptyList(),
        )
        assertEquals(-3.0, graphicOnly.preampDb, 1e-9)
    }

    @Test
    fun aStereoGraphicCurveKeepsItsRightChannelOverrides() {
        val left = """[{"type":"PEAKING","freq":1000,"gain":2,"q":1},{"type":"PEAKING","freq":2000,"gain":3,"q":1}]"""
        val right = """[{"type":"PEAKING","freq":1000,"gain":2,"q":1},{"type":"PEAKING","freq":2500,"gain":5,"q":1}]"""

        val stereo = DspSettingsMapper.fromPersisted(
            PersistedDspValues(
                tryptifyEqualizerEnabled = true,
                tryptifyGraphicStageEnabled = true,
                tryptifyGraphicBandsJson = left,
                tryptifyStereoMode = true,
                tryptifyRightGraphicBandsJson = right,
            ),
            emptyList(),
        )
        assertEquals(2, stereo.equalizerBands.size)
        // The first band matches on both channels, so it stays linked; the second carries its own design.
        assertNull(stereo.equalizerBands[0].rightBand())
        assertEquals(2_500.0, stereo.equalizerBands[1].rightFrequencyHz)
        assertEquals(5.0, stereo.equalizerBands[1].rightGainDb)
    }

    @Test
    fun aRightGraphOfAnotherWidthIsNotGuessedAt() {
        val left = """[{"type":"PEAKING","freq":1000,"gain":2,"q":1},{"type":"PEAKING","freq":2000,"gain":3,"q":1}]"""
        val right = """[{"type":"PEAKING","freq":2500,"gain":5,"q":1}]"""

        val linked = DspSettingsMapper.fromPersisted(
            PersistedDspValues(
                tryptifyEqualizerEnabled = true,
                tryptifyGraphicStageEnabled = true,
                tryptifyGraphicBandsJson = left,
                tryptifyStereoMode = true,
                tryptifyRightGraphicBandsJson = right,
            ),
            emptyList(),
        )
        assertTrue(linked.equalizerBands.none { it.hasRightOverride })
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
    fun thePreampSwitchKeepsTheStoredGainStored() {
        val switchedOff = DspSettingsMapper.fromPersisted(
            PersistedDspValues(equalizerOutputGainMb = -350, equalizerOutputGainEnabled = false),
            emptyList(),
        )
        // A switched-off preamp keeps its value and applies nothing, so flipping the switch back applies it.
        assertEquals(-3.5, switchedOff.preampDb)
        assertFalse(switchedOff.preampEnabled)
        assertEquals(1f, switchedOff.preampGain)

        // A retired stage applied its own preamp whenever the stage ran, so it keeps contributing even
        // though the shared output-gain switch was off.
        val stagePreamp = DspSettingsMapper.fromPersisted(
            PersistedDspValues(
                equalizerOutputGainEnabled = false,
                tryptifyEqualizerEnabled = true,
                tryptifyGraphicStageEnabled = true,
                tryptifyGraphicBandsJson = """[{"type":"PEAKING","freq":1000,"gain":2,"q":1}]""",
                tryptifyGraphicPreampDb = -6.0,
            ),
            emptyList(),
        )
        assertEquals(-6.0, stagePreamp.preampDb, 1e-9)
        assertTrue(stagePreamp.preampEnabled)
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
