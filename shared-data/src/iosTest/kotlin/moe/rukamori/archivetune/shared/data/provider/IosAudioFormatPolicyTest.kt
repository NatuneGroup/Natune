package moe.rukamori.archivetune.shared.data.provider

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IosAudioFormatPolicyTest {
    private data class Format(val mime: String, val bitrate: Int)
    private fun pick(formats: List<Format>, low: Boolean = false) =
        selectIosAudioFormat(formats, { it.mime }, { it.bitrate }, low)

    @Test fun aacWinsOverHigherBitrateOpusAndLowStillHonorsTheContainer() {
        val aac = Format("audio/mp4; codecs=mp4a.40.2", 128_000)
        val low = Format("audio/mp4", 48_000)
        val opus = Format("audio/webm", 256_000)
        assertEquals(aac, pick(listOf(opus, low, aac)))
        assertEquals(low, pick(listOf(opus, low, aac), low = true))
    }

    @Test fun ffmpegFallbackRemainsAvailableAndEmptyIsNotSuccess() {
        val opus = Format("audio/webm", 128_000)
        assertEquals(opus, pick(listOf(opus)))
        assertNull(pick(emptyList()))
    }
}
