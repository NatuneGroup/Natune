package moe.rukamori.archivetune.shared.data.provider

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.floor

/** Port of the shipped `SpotifyInternalToken` — the web player's own token shape. */
@Serializable
data class SpotifyInternalToken(
    val accessToken: String,
    val accessTokenExpirationTimestampMs: Long,
    val isAnonymous: Boolean = false,
    val clientId: String = "",
)

@Serializable
private data class SpotifyNuance(val v: Int, val s: String)

@Serializable
private data class SpotifyServerTime(val serverTime: Long)

/**
 * Pure-Kotlin SHA-1, needed by the Spotify web-player TOTP (RFC 6238, HMAC-SHA1). Kept in common
 * code like the rest of the provider crypto so every platform produces bit-identical output.
 */
internal object Sha1 {
    fun digest(input: ByteArray): ByteArray {
        var h0 = 0x67452301
        var h1 = 0xEFCDAB89.toInt()
        var h2 = 0x98BADCFE.toInt()
        var h3 = 0x10325476
        var h4 = 0xC3D2E1F0.toInt()

        val ml = input.size.toLong() * 8L
        val padded = ByteArray(((input.size + 8) / 64 + 1) * 64)
        input.copyInto(padded)
        padded[input.size] = 0x80.toByte()
        for (i in 0 until 8) padded[padded.size - 1 - i] = ((ml shr (i * 8)) and 0xFF).toByte()

        val w = IntArray(80)
        for (chunkStart in padded.indices step 64) {
            for (i in 0 until 16) {
                w[i] = (padded[chunkStart + i * 4].toInt() and 0xFF shl 24) or
                    (padded[chunkStart + i * 4 + 1].toInt() and 0xFF shl 16) or
                    (padded[chunkStart + i * 4 + 2].toInt() and 0xFF shl 8) or
                    (padded[chunkStart + i * 4 + 3].toInt() and 0xFF)
            }
            for (i in 16 until 80) {
                val v = w[i - 3] xor w[i - 8] xor w[i - 14] xor w[i - 16]
                w[i] = (v shl 1) or (v ushr 31)
            }
            var a = h0; var b = h1; var c = h2; var d = h3; var e = h4
            for (i in 0 until 80) {
                val (f, k) = when {
                    i < 20 -> (b and c) or (b.inv() and d) to 0x5A827999
                    i < 40 -> b xor c xor d to 0x6ED9EBA1
                    i < 60 -> (b and c) or (b and d) or (c and d) to 0x8F1BBCDC.toInt()
                    else -> b xor c xor d to 0xCA62C1D6.toInt()
                }
                val temp = ((a shl 5) or (a ushr 27)) + f + e + k + w[i]
                e = d; d = c; c = (b shl 30) or (b ushr 2); b = a; a = temp
            }
            h0 += a; h1 += b; h2 += c; h3 += d; h4 += e
        }
        val out = ByteArray(20)
        for ((i, v) in listOf(h0, h1, h2, h3, h4).withIndex()) {
            out[i * 4] = (v ushr 24).toByte(); out[i * 4 + 1] = (v ushr 16).toByte()
            out[i * 4 + 2] = (v ushr 8).toByte(); out[i * 4 + 3] = v.toByte()
        }
        return out
    }

    /** RFC 2104 HMAC with SHA-1 as the digest. */
    fun hmac(key: ByteArray, message: ByteArray): ByteArray {
        val k = if (key.size > 64) digest(key) else key
        val padded = ByteArray(64); k.copyInto(padded, 0, 0, k.size.coerceAtMost(64))
        val inner = ByteArray(64) { (padded[it].toInt() xor 0x36).toByte() }
        val outer = ByteArray(64) { (padded[it].toInt() xor 0x5C).toByte() }
        return digest(outer + digest(inner + message))
    }
}

/**
 * Port of the shipped `SpotifyAuth`: authenticates the web player with sp_dc cookies against
 * open.spotify.com's internal `/api/token`, guarded by a TOTP whose secret comes from the
 * community nuances list and whose clock comes from Spotify's own server-time endpoint — never
 * the client clock.
 */
class KtorSpotifyAuth(private val client: HttpClient) {
    private val json = Json { ignoreUnknownKeys = true }
    private val nuanceMutex = Mutex()
    private var cachedNuance: SpotifyNuance? = null
    private var cachedNuanceAtMs: Long = 0

    /**
     * 1. Fetches the TOTP secret from the community Gist (5-minute cache)
     * 2. Fetches Spotify's server time
     * 3. Generates a TOTP
     * 4. Calls /api/token with the TOTP and sp_dc cookie
     */
    suspend fun fetchAccessToken(
        spDc: String,
        spKey: String = "",
        nowMs: () -> Long,
    ): Result<SpotifyInternalToken> =
        try {
            val nuance = fetchNuance(nowMs)
            val serverTimeSec = fetchServerTime()
            val totp = generateTotp(nuance.s, serverTimeSec)

            val tokenUrl =
                buildString {
                    append(TOKEN_URL)
                    append("?reason=transport")
                    append("&productType=web-player")
                    append("&totp=$totp")
                    append("&totpServer=$totp")
                    append("&totpVer=${nuance.v}")
                }

            val cookieHeader =
                buildString {
                    append("sp_dc=$spDc")
                    if (spKey.isNotEmpty()) append("; sp_key=$spKey")
                }

            val body = spotifyGet(tokenUrl, mapOf("Cookie" to cookieHeader))
            val token = json.decodeFromString<SpotifyInternalToken>(body)

            if (token.isAnonymous || token.accessToken.isBlank()) {
                throw SpotifyException(401, "Received anonymous token — sp_dc cookie is invalid or expired")
            }
            Result.success(token)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Result.failure(error)
        }

    private suspend fun fetchNuance(nowMs: () -> Long): SpotifyNuance =
        nuanceMutex.withLock {
            val now = nowMs()
            cachedNuance?.takeIf { now - cachedNuanceAtMs < NUANCE_CACHE_TTL_MS }?.let { return it }
            val nuances = json.decodeFromString<List<SpotifyNuance>>(spotifyGet(NUANCE_URL, emptyMap()))
            val latest = nuances.asSequence()
                .filter { it.v > 0 && it.s.isValidBase32Secret() }
                .maxByOrNull(SpotifyNuance::v)
                ?: throw IllegalStateException("No valid Spotify TOTP configuration was returned")
            cachedNuance = latest
            cachedNuanceAtMs = now
            latest
        }

    private suspend fun fetchServerTime(): Long =
        json.decodeFromString<SpotifyServerTime>(spotifyGet(SERVER_TIME_URL, emptyMap())).serverTime

    /** RFC 6238 6-digit TOTP, 30-second step, over the server's epoch seconds. */
    private fun generateTotp(secret: String, serverTimeSec: Long): String {
        val key = base32Decode(secret)
        val timeStep = floor(serverTimeSec.toDouble() / 30L).toLong()
        val timeBytes = ByteArray(8)
        var value = timeStep
        for (i in 7 downTo 0) {
            timeBytes[i] = (value and 0xFF).toByte()
            value = value shr 8
        }
        val hash = Sha1.hmac(key, timeBytes)
        val offset = hash[hash.size - 1].toInt() and 0x0F
        val code =
            ((hash[offset].toInt() and 0x7F) shl 24) or
                ((hash[offset + 1].toInt() and 0xFF) shl 16) or
                ((hash[offset + 2].toInt() and 0xFF) shl 8) or
                (hash[offset + 3].toInt() and 0xFF)
        return (code % 1_000_000).toString().padStart(6, '0')
    }

    private fun base32Decode(input: String): ByteArray {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val cleaned = input.uppercase().replace("=", "")
        val output = mutableListOf<Byte>()
        var buffer = 0
        var bitsLeft = 0
        for (c in cleaned) {
            val value = alphabet.indexOf(c)
            if (value < 0) continue
            buffer = (buffer shl 5) or value
            bitsLeft += 5
            if (bitsLeft >= 8) {
                bitsLeft -= 8
                output.add(((buffer shr bitsLeft) and 0xFF).toByte())
            }
        }
        return output.toByteArray()
    }

    private fun String.isValidBase32Secret(): Boolean =
        isNotBlank() && all { it in "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567=" }

    private suspend fun spotifyGet(url: String, extraHeaders: Map<String, String>): String {
        val response = client.get(url) {
            header("User-Agent", USER_AGENT)
            header("Accept", "application/json, text/plain, */*")
            header("Accept-Language", "en")
            for ((key, value) in extraHeaders) header(key, value)
        }
        val code = response.status.value
        if (code !in 200..299) throw SpotifyException(code, "Request failed with HTTP $code")
        return response.bodyAsText()
    }

    class SpotifyException(val statusCode: Int, override val message: String) : Exception(message)

    companion object {
        private const val TOKEN_URL = "https://open.spotify.com/api/token"
        private const val SERVER_TIME_URL = "https://open.spotify.com/api/server-time"
        private const val NUANCE_URL =
            "https://gist.githubusercontent.com/sonic-liberation/22ed9c6ba463899e933427f7de1f0eef/raw/nuances.json"
        private const val NUANCE_CACHE_TTL_MS = 5 * 60 * 1000L
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        const val LOGIN_URL = "https://accounts.spotify.com/login?continue=https%3A%2F%2Fopen.spotify.com%2F"
    }
}

/**
 * Port of the shipped `SpotifyStreamingAccount.accessToken`: the streaming account's token is
 * minted from ITS OWN sp_dc — never the catalog session — behind a mutex, cached until the 60s
 * refresh margin, and refused while the catalog is signed in with the same account. Persistence
 * and the catalog collision check stay with the account owner via callbacks.
 */
class KtorSpotifyStreamingAccount(
    private val auth: KtorSpotifyAuth,
    private val nowMs: () -> Long,
    private val streamSpDc: () -> String,
    private val streamSpKey: () -> String = { "" },
    private val cachedToken: () -> Pair<String, Long>? = { null },
    private val persistToken: suspend (accessToken: String, expiresAtMs: Long) -> Unit = { _, _ -> },
    /** True when the streaming sp_dc matches the catalog sign-in; the source must stand down. */
    private val collidesWithCatalog: suspend (String) -> Boolean = { false },
) {
    private val tokenMutex = Mutex()

    suspend fun accessToken(): String? =
        tokenMutex.withLock {
            val spDc = streamSpDc()
            if (spDc.isBlank()) return@withLock null
            if (collidesWithCatalog(spDc)) return@withLock null
            val cached = cachedToken()
            if (cached != null && cached.first.isNotBlank() &&
                cached.second - TOKEN_REFRESH_MARGIN_MS > nowMs()
            ) {
                return@withLock cached.first
            }
            val fresh = auth.fetchAccessToken(spDc, streamSpKey(), nowMs)
                .getOrNull()
                ?.takeUnless { it.isAnonymous }
                ?: return@withLock null
            persistToken(fresh.accessToken, fresh.accessTokenExpirationTimestampMs)
            fresh.accessToken
        }

    companion object {
        const val TOKEN_REFRESH_MARGIN_MS = 60_000L
    }
}
