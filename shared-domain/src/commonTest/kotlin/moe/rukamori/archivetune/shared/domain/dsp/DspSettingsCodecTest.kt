package moe.rukamori.archivetune.shared.domain.dsp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import moe.rukamori.archivetune.shared.domain.dsp.clarity.ClarityPreset
import moe.rukamori.archivetune.shared.domain.dsp.eq.EqualizerBand
import moe.rukamori.archivetune.shared.domain.dsp.eq.EqualizerBandType

/** One versioned document holds every knob, and nothing a host stored may stop playback. */
class DspSettingsCodecTest {
    private val everyField = DspSettings(
        linearGain = 0.5f,
        preampDb = -3.5,
        normalizationEnabled = false,
        equalizerEnabled = false,
        equalizerBands = listOf(
            EqualizerBand(EqualizerBandType.PEAKING, 1_000.0, 6.0, 1.2),
            EqualizerBand(
                type = EqualizerBandType.LOW_SHELF,
                frequencyHz = 200.0,
                gainDb = -4.0,
                q = 0.7,
                rightFrequencyHz = 220.0,
                rightGainDb = -3.0,
                rightQ = 0.9,
            ),
        ),
        clarity = ClarityPreset.DAC,
        dcBlockerEnabled = true,
        limiterEnabled = true,
        limiterCeilingDb = -1.5,
        speed = 1.25,
        pitchSemitones = -4.0,
        transitionGain = 0.6f,
        dither = DspDither(enabled = true, bitDepth = 24, seed = -7),
    )

    @Test
    fun everyFieldSurvivesTheRoundTrip() {
        assertEquals(everyField, DspSettingsCodec.decode(DspSettingsCodec.encode(everyField)))
    }

    @Test
    fun theDocumentIsVersionedAndLeavesTheClarityContourOutWhenUnset() {
        val encoded = DspSettingsCodec.encode(DspSettings())
        assertTrue(encoded.contains("\"version\":${DspSettingsCodec.FORMAT_VERSION}"), encoded)
        assertFalse(encoded.contains("clarity"), encoded)
        assertTrue(encoded.contains("\"dither\""), encoded)
    }

    @Test
    fun aMissingOrUnreadableDocumentReadsAsTheNeutralDefaults() {
        assertEquals(DspSettings(), DspSettingsCodec.decode(null))
        assertEquals(DspSettings(), DspSettingsCodec.decode(""))
        assertEquals(DspSettings(), DspSettingsCodec.decode("   "))
        assertEquals(DspSettings(), DspSettingsCodec.decode("not json at all"))
        assertEquals(DspSettings(), DspSettingsCodec.decode("[1,2,3]"))
    }

    @Test
    fun outOfRangeValuesAreClampedInsteadOfRejected() {
        val decoded = DspSettingsCodec.decode(
            """
            {"linearGain":0,"preampDb":99,"speed":9,"pitchSemitones":-99,
             "limiterCeilingDb":3,"transitionGain":-2,"dither":{"bitDepth":4,"seed":9}}
            """.trimIndent(),
        )
        assertEquals(DspSettings().linearGain, decoded.linearGain)
        assertEquals(DspSettings.MAX_PREAMP_DB, decoded.preampDb)
        assertEquals(4.0, decoded.speed)
        assertEquals(-12.0, decoded.pitchSemitones)
        assertEquals(0.0, decoded.limiterCeilingDb)
        assertEquals(0f, decoded.transitionGain)
        assertEquals(DspDither.MIN_BIT_DEPTH, decoded.dither.bitDepth)
        assertEquals(9, decoded.dither.seed)
    }

    @Test
    fun aClarityIdThisBuildDoesNotKnowDropsTheContourInsteadOfFailing() {
        val decoded = DspSettingsCodec.decode("""{"clarity":"future-shape","limiter":true}""")
        assertNull(decoded.clarity)
        assertTrue(decoded.limiterEnabled)
    }

    @Test
    fun keysTheNextVersionAddsAreIgnored() {
        val decoded = DspSettingsCodec.decode("""{"future":{"a":1},"speed":2.0}""")
        assertEquals(2.0, decoded.speed)
        assertEquals(DspSettings().equalizerBands, decoded.equalizerBands)
    }

    @Test
    fun damagedBandsAreDroppedAndTheSurvivorsAreClamped() {
        val decoded = DspSettingsCodec.decode(
            """
            {"bands":[
              {"type":"PEAKING","hz":1000,"gainDb":40,"q":0.01},
              {"type":"NOTCH","hz":200,"gainDb":1},
              {"type":"PEAKING","gainDb":1},
              {"hz":500,"gainDb":1,"q":0.5},
              {"hz":1000,"gainDb":2,"type":"LOW_SHELF","q":0.8,"rightGainDb":-3,"rightQ":0.05}
            ]}
            """.trimIndent(),
        )
        assertEquals(3, decoded.equalizerBands.size)

        val clamped = decoded.equalizerBands[0]
        assertEquals(EqualizerBandType.PEAKING, clamped.type)
        assertEquals(24.0, clamped.gainDb)
        assertEquals(0.1, clamped.q)

        // A band with no stored shape stays a peaking band, as the shared column always was.
        assertEquals(EqualizerBandType.PEAKING, decoded.equalizerBands[1].type)
        assertEquals(500.0, decoded.equalizerBands[1].frequencyHz)

        val shelf = decoded.equalizerBands[2]
        assertEquals(EqualizerBandType.LOW_SHELF, shelf.type)
        assertNull(shelf.rightFrequencyHz)
        assertEquals(-3.0, shelf.rightGainDb)
        assertEquals(0.1, shelf.rightQ)
    }

    @Test
    fun aStoredBandListIsCappedAtTheChainWidth() {
        val bands = (0 until DspSettings.MAX_EQUALIZER_BANDS + 6).joinToString(",", "[", "]") { index ->
            """{"type":"PEAKING","hz":${100 + index * 10},"gainDb":1.0,"q":1.0}"""
        }
        val decoded = DspSettingsCodec.decode("""{"bands":$bands}""")
        assertEquals(DspSettings.MAX_EQUALIZER_BANDS, decoded.equalizerBands.size)
        assertEquals(100.0, decoded.equalizerBands.first().frequencyHz)
    }
}
