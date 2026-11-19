package moe.rukamori.archivetune.desktop.provider

import java.security.MessageDigest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import moe.rukamori.archivetune.shared.data.provider.spotify.SpotifyEchoProto
import moe.rukamori.archivetune.shared.data.provider.spotify.SpotifyPlayPlayLicenseRequest
import moe.rukamori.archivetune.shared.data.provider.spotify.SpotifyPlayPlayRecordDecoder
import moe.rukamori.archivetune.spotify.echo.SpotifyEchoProto as AppEchoProto
import moe.rukamori.archivetune.spotify.echo.SpotifyPlayPlayLicenseRequest as AppLicenseRequest
import moe.rukamori.archivetune.spotify.echo.SpotifyPlayPlayRecordDecoder as AppRecordDecoder

/** Temporary: proves the shared PlayPlay port matches the app copy before the app copy is removed. */
class SpotifyPlayPlayParityTest {
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }

    @Test fun sharedDecoderMatchesAppCopy() {
        val random = Random(0x5eed)
        val digest = MessageDigest.getInstance("SHA-256")
        repeat(512) {
            val input = random.nextBytes(16)
            val shared = SpotifyPlayPlayRecordDecoder.decodeObfuscatedKey(input)
            assertContentEquals(AppRecordDecoder.decodeObfuscatedKey(input), shared, input.hex())
            digest.update(shared)
        }
        val body = byteArrayOf(0x0a, 16) + ByteArray(16) { it.toByte() }
        assertContentEquals(AppRecordDecoder.parseObfuscatedKey(body), SpotifyPlayPlayRecordDecoder.parseObfuscatedKey(body))
        assertContentEquals(AppLicenseRequest.encode(1_700_000_000L), SpotifyPlayPlayLicenseRequest.encode(1_700_000_000L))
        val kinds = listOf(SpotifyEchoProto.EXTENSION_KIND_TRACK_V4, SpotifyEchoProto.EXTENSION_KIND_AUDIO_FILES)
        assertContentEquals(AppEchoProto.buildExtendedMetadataRequest("spotify:track:x", kinds),
            SpotifyEchoProto.buildExtendedMetadataRequest("spotify:track:x", kinds))
        val first = SpotifyPlayPlayRecordDecoder.decodeObfuscatedKey(ByteArray(16) { it.toByte() }).hex()
        val license = SpotifyPlayPlayLicenseRequest.encode(1_700_000_000L).hex()
        assertEquals("GOLDEN", "first=$first digest=${digest.digest().hex()} license=$license")
    }
}
