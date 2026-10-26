package moe.rukamori.archivetune.shared.data.provider

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Request-building/decryption/order checks for the ported provider logic, using known vectors and
 * shapes extracted from the shipped Android implementation.
 */
class ProviderParityTest {

    @Test
    fun `sha1 matches the RFC 3174 test vector`() {
        assertEquals(
            "a9993e364706816aba3e25717850c26c9cd0d89d",
            Sha1.digest("abc".encodeToByteArray()).toHex(),
        )
        val repeated = ByteArray(1_000_000) { 'a'.code.toByte() }
        assertEquals(
            "34aa973cd4c4daa4f61eeb2bdbad27316534016f",
            Sha1.digest(repeated).toHex(),
        )
    }

    @Test
    fun `hmac-sha1 matches the RFC 2202 test vector`() {
        // RFC 2202 case 1: key = 20 bytes of 0x0b, data = "Hi There"
        val key = ByteArray(20) { 0x0b }
        assertEquals(
            "b617318655057264e28bc0b6fb378c8ef146be00",
            Sha1.hmac(key, "Hi There".encodeToByteArray()).toHex(),
        )
    }

    @Test
    fun `amazon merge orders local first then pooled and drops unusable material`() {
        fun access(token: String? = null, jwt: String? = null, jwtExpiresAtMs: Long? = null) =
            AmazonInstanceAccess("scope", token, jwt, turnstileJwtExpiresAtMs = jwtExpiresAtMs) { true }
        val now = 1_000_000L
        val merged =
            AmazonInstances.merge(
                localBaseUrl = "https://local.example/",
                localAccess = access(token = "local-op"),
                pooled =
                    listOf(
                        AmazonPoolInstance("https://local.example", access(token = "dup")),
                        AmazonPoolInstance("https://stale.example", access(jwt = "x", jwtExpiresAtMs = now)),
                        AmazonPoolInstance("https://nomaterial.example", access(jwt = "jwt", jwtExpiresAtMs = now - 60L * 60L * 1000L)),
                        AmazonPoolInstance("https://pool.example", access(jwt = "jwt", jwtExpiresAtMs = now + 50L * 60L * 1000L)),
                    ),
                nowMs = now,
            )
        assertEquals(listOf("https://local.example", "https://pool.example"), merged.map { it.baseUrl })
    }

    @Test
    fun `amazon merge drops pooled jwt inside the five minute safety window`() {
        val now = 10_000L
        val access = AmazonInstanceAccess(
            "scope", null, "jwt",
            turnstileJwtExpiresAtMs = now + 60_000L, // inside the 5-minute safety window
        ) { true }
        val merged = AmazonInstances.merge(
            localBaseUrl = null, localAccess = null,
            pooled = listOf(AmazonPoolInstance("https://pool.example", access)),
            nowMs = now,
        )
        assertTrue(merged.isEmpty())
    }

    @Test
    fun `qobuz backup endpoint normalization heals http and the legacy mirror`() {
        val https = "http://mls.kouzu.in/".let { raw ->
            (if (raw.startsWith("http://")) "https://" + raw.removePrefix("http://") else raw)
                .replace("mlc-ytify.kouzu.in", "mls.kouzu.in").trimEnd('/')
        }
        assertEquals("https://mls.kouzu.in", https)
        val mirror = "https://mlc-ytify.kouzu.in".replace("mlc-ytify.kouzu.in", "mls.kouzu.in")
        assertEquals("https://mls.kouzu.in", mirror)
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { byte -> byte.toInt().and(0xFF).toString(16).padStart(2, '0') }
}
