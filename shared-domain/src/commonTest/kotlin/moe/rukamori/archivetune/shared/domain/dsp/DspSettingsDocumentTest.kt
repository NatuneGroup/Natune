package moe.rukamori.archivetune.shared.domain.dsp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import moe.rukamori.archivetune.shared.domain.dsp.eq.EqualizerBand
import moe.rukamori.archivetune.shared.domain.dsp.eq.EqualizerBandLevels
import moe.rukamori.archivetune.shared.domain.dsp.eq.EqualizerBandType
import moe.rukamori.archivetune.shared.domain.dsp.eq.GraphicEqualizer
import moe.rukamori.archivetune.shared.domain.settings.AudioNormalizationKey
import moe.rukamori.archivetune.shared.domain.settings.EqualizerBandLevelsMbKey
import moe.rukamori.archivetune.shared.domain.settings.EqualizerEnabledKey
import moe.rukamori.archivetune.shared.domain.settings.EqualizerOutputGainEnabledKey
import moe.rukamori.archivetune.shared.domain.settings.EqualizerOutputGainMbKey
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot

/**
 * The stored document is the one value a host reads, so these cases pin the two rules it cannot get wrong: the
 * document wins over the legacy keys once it exists, and the legacy keys are read only while it does not.
 */
class DspSettingsDocumentTest {
    private val storedBands = listOf(
        EqualizerBand(EqualizerBandType.PEAKING, 500.0, 3.0, 1.0),
        EqualizerBand(EqualizerBandType.LOW_SHELF, 100.0, -2.0, 0.7),
    )

    private val stored = DspSettings(
        preampDb = -1.5,
        normalizationEnabled = true,
        equalizerEnabled = true,
        equalizerBands = storedBands,
    )

    @Test
    fun noDocumentReadsAsNothingSoTheHostMigrates() {
        assertNull(DspSettingsDocument.read(null))
        assertNull(DspSettingsDocument.read("   "))
        assertNull(DspSettingsDocument.read("not a document"))
    }

    @Test
    fun theDocumentWinsOverEveryLegacyKey() {
        val legacy = PersistedDspValues(
            audioNormalization = false,
            equalizerEnabled = true,
            equalizerBandLevelsMb = EqualizerBandLevels.encode(List(GraphicEqualizer.BAND_COUNT) { 1_200 }),
            equalizerOutputGainMb = 600,
        )

        val value = DspSettingsDocument.of(DspSettingsDocument.write(stored), legacy)

        assertEquals(storedBands, value.equalizerBands)
        assertEquals(-1.5, value.preampDb, 1e-12)
        assertTrue(value.normalizationEnabled)
    }

    @Test
    fun theLegacyKeysAreReadOnceWhenThereIsNoDocument() {
        val levels = GraphicEqualizer.flatLevelsMb().toMutableList().also { it[4] = 450 }

        val value = DspSettingsDocument.of(
            raw = null,
            legacy = PersistedDspValues(
                audioNormalization = true,
                equalizerEnabled = true,
                equalizerBandLevelsMb = EqualizerBandLevels.encode(levels),
                equalizerOutputGainMb = 300,
            ),
        )

        assertTrue(value.normalizationEnabled)
        assertTrue(value.equalizerEnabled)
        assertEquals(3.0, value.preampDb, 1e-12)
        assertEquals(GraphicEqualizer.BAND_COUNT, value.equalizerBands.size)
        assertEquals(4.5, value.equalizerBands[4].gainDb, 1e-9)
        assertEquals(GraphicEqualizer.BAND_CENTRES_HZ[4], value.equalizerBands[4].frequencyHz, 1e-12)
    }

    @Test
    fun aHostWithoutLegacyValuesStaysNeutral() {
        val value = DspSettingsDocument.legacyOf(null)

        assertEquals(false, value.audioNormalization)
        assertEquals(false, value.equalizerEnabled)
        assertNull(value.equalizerBandLevelsMb)
        assertEquals(0, value.equalizerOutputGainMb)
    }

    @Test
    fun theLegacyPreampIsHonouredOnlyWhileItsOwnSwitchIsOn() {
        val enabled = DspSettingsDocument.legacyOf(
            PreferenceSnapshot.of(
                AudioNormalizationKey to true,
                EqualizerEnabledKey to true,
                EqualizerBandLevelsMbKey to EqualizerBandLevels.encode(GraphicEqualizer.flatLevelsMb()),
                EqualizerOutputGainEnabledKey to true,
                EqualizerOutputGainMbKey to 450,
            ),
        )
        val disabled = DspSettingsDocument.legacyOf(
            PreferenceSnapshot.of(EqualizerOutputGainEnabledKey to false, EqualizerOutputGainMbKey to 450),
        )

        assertEquals(450, enabled.equalizerOutputGainMb)
        assertEquals(0, disabled.equalizerOutputGainMb)
        assertTrue(enabled.audioNormalization)
        assertTrue(enabled.equalizerEnabled)
    }

    @Test
    fun theGraphicLayoutRoundTripsTheLevelsItShows() {
        val levels = listOf(-1_200, 0, 300, -450, 0, 600, 0, 1_200, 0, -2_400)

        val bands = GraphicEqualizer.bandsFromLevelsMb(levels)

        assertEquals(GraphicEqualizer.BAND_COUNT, bands.size)
        assertEquals(levels, GraphicEqualizer.levelsMbOf(bands))
        assertEquals(EqualizerBandType.PEAKING, bands[0].type)
        assertEquals(GraphicEqualizer.BAND_Q, bands[0].q, 1e-12)
        assertEquals(-12.0, bands[0].gainDb, 1e-9)
    }

    @Test
    fun anotherHostsBandLayoutShowsFlatInsteadOfAnotherEqualizer() {
        val fifteen = DspSettingsMapper.LAST_WAVE_BAND_FREQUENCIES_HZ.map { frequencyHz ->
            EqualizerBand(EqualizerBandType.PEAKING, frequencyHz, 6.0, DspSettingsMapper.GRAPHIC_BAND_Q)
        }

        assertEquals(GraphicEqualizer.flatLevelsMb(), GraphicEqualizer.levelsMbOf(fifteen))
        assertEquals(GraphicEqualizer.flatLevelsMb(), GraphicEqualizer.levelsMbOf(emptyList()))
    }

    @Test
    fun aStoredGainIsClampedToTheLevelRange() {
        assertEquals(GraphicEqualizer.MAX_LEVEL_MB, GraphicEqualizer.levelMbOf(100.0))
        assertEquals(-GraphicEqualizer.MAX_LEVEL_MB, GraphicEqualizer.levelMbOf(-100.0))
        assertEquals(0, GraphicEqualizer.levelMbOf(0.0))
    }
}
