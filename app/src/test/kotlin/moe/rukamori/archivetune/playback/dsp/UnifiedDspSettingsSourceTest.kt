package moe.rukamori.archivetune.playback.dsp

import androidx.datastore.preferences.core.emptyPreferences
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import moe.rukamori.archivetune.constants.EqualizerOutputGainEnabledKey
import moe.rukamori.archivetune.constants.EqualizerOutputGainMbKey
import moe.rukamori.archivetune.shared.domain.dsp.DspSettings
import moe.rukamori.archivetune.shared.domain.dsp.DspSettingsMapper
import moe.rukamori.archivetune.shared.domain.dsp.PersistedDspValues
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnifiedDspSettingsSourceTest {
    private val stored =
        PersistedDspValues(
            audioNormalization = true,
            equalizerEnabled = true,
            equalizerBandLevelsMb = "[600]",
            equalizerOutputGainMb = -300,
        )

    private fun source(
        values: () -> PersistedDspValues = { stored },
        processingEnabled: () -> Boolean = { true },
        bitPerfectActive: () -> Boolean = { false },
    ) = UnifiedDspSettingsSource(readValues = values, processingEnabled = processingEnabled, bitPerfectActive = bitPerfectActive)

    @Test
    fun `the switch on publishes the stored equalizer`() {
        val source = source()
        val snapshot = source.current

        assertFalse(snapshot.plan.bypass)
        assertTrue(snapshot.plan.equalizer)
        assertTrue(snapshot.settings.equalizerEnabled)
        assertTrue("the stored levels reach the graph", snapshot.settings.equalizerBands.isNotEmpty())
        assertTrue(source.isProcessing())
    }

    @Test
    fun `the processing switch turns the whole chain off`() {
        val source = source(processingEnabled = { false })
        val snapshot = source.current

        assertTrue(snapshot.plan.bypass)
        assertFalse(snapshot.plan.equalizer)
        // The published value is the shared neutral rendering, not a defaulting value that still looks on.
        assertEquals(DspSettings().withoutProcessing(), snapshot.settings)
        assertFalse(snapshot.settings.preampEnabled)
        assertTrue("the software curve is bypassed, not flattened", snapshot.settings.equalizerBands.isEmpty())
        assertFalse(source.isProcessing())
    }

    @Test
    fun `a refresh republishes the bypass to an attached processor`() {
        var processing = true
        val source = source(processingEnabled = { processing })
        val processor = UnifiedDspAudioProcessor(bypassGate = { false }).also(source::attach)
        val format = AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT)
        assertEquals(C.ENCODING_PCM_16BIT, processor.configure(format).encoding)

        processing = false
        source.refresh()

        assertEquals(AudioProcessor.AudioFormat.NOT_SET, processor.configure(format))
    }

    @Test
    fun `the output gain is read only while its own switch is on`() {
        val on = UnifiedDspSettingsSource.readPersistedDspValues(
            emptyPreferences().toMutablePreferences().apply {
                this[EqualizerOutputGainEnabledKey] = true
                this[EqualizerOutputGainMbKey] = -450
            },
        )
        assertEquals(-450, on.equalizerOutputGainMb)
        assertTrue(on.equalizerOutputGainEnabled)
        assertEquals(-4.5, DspSettingsMapper.fromPersisted(on, emptyList()).preampDb, 1e-9)
        assertTrue(DspSettingsMapper.fromPersisted(on, emptyList()).preampEnabled)

        val off = UnifiedDspSettingsSource.readPersistedDspValues(
            emptyPreferences().toMutablePreferences().apply { this[EqualizerOutputGainMbKey] = -450 },
        )
        assertEquals(0, off.equalizerOutputGainMb)
        assertFalse(off.equalizerOutputGainEnabled)
        assertFalse(DspSettingsMapper.fromPersisted(off, emptyList()).preampEnabled)
    }
}
