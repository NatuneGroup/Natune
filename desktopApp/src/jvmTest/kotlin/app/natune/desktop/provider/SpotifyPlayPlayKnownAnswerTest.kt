package app.natune.desktop.provider

import java.security.MessageDigest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import app.natune.shared.data.provider.spotify.SpotifyPlayPlayLicenseRequest
import app.natune.shared.data.provider.spotify.SpotifyPlayPlayRecordDecoder

/**
 * Known answers recorded from the retained Android PlayPlay implementation, which the shared port matched
 * on these inputs before the Android copy was removed. A change here means the key transform drifted.
 */
class SpotifyPlayPlayKnownAnswerTest {
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }

    @Test fun keyTransformMatchesTheRetainedAndroidImplementation() {
        assertEquals("e16505b2ab78b1e0d739bbf7eec2de26",
            SpotifyPlayPlayRecordDecoder.decodeObfuscatedKey(ByteArray(16) { it.toByte() }).hex())
        val random = Random(0x5eed)
        val digest = MessageDigest.getInstance("SHA-256")
        repeat(512) { digest.update(SpotifyPlayPlayRecordDecoder.decodeObfuscatedKey(random.nextBytes(16))) }
        assertEquals("a6b7d3fd4096b2f2a4bf20f65f09a8a81eda372dbadded7e0aa8098836eb05cc", digest.digest().hex())
    }

    @Test fun licenseRequestMatchesTheRetainedAndroidImplementation() {
        assertEquals("0805121002198316beae6d4af43524d02c372b7e200128013080e2cfaa06",
            SpotifyPlayPlayLicenseRequest.encode(1_700_000_000L).hex())
    }
}
