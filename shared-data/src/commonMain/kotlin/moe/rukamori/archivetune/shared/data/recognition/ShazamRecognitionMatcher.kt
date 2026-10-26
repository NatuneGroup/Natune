/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.shared.data.recognition

import io.ktor.client.HttpClient
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import moe.rukamori.archivetune.shared.domain.recognition.RecognitionCaptureSpec
import moe.rukamori.archivetune.shared.domain.recognition.RecognitionClock
import moe.rukamori.archivetune.shared.domain.recognition.RecognitionMatchOutcome
import moe.rukamori.archivetune.shared.domain.recognition.RecognitionMatchPort
import moe.rukamori.archivetune.shared.domain.recognition.RecognitionSignaturePort
import moe.rukamori.archivetune.shared.domain.recognition.RecognizedTrack
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.random.Random

/**
 * Matches captured audio against the Shazam discovery endpoint. The endpoint, query, headers, retry on rate limits
 * and track mapping follow the baseline shazamkit client; the fingerprint comes from the injected
 * [RecognitionSignaturePort].
 *
 * Outcomes: a 404, or a reply without a track, is [RecognitionMatchOutcome.NoMatch]. A track without a title, a rate
 * limit that persists through every attempt, a server error, a timeout, an unreadable reply, or a signature without its
 * sample-count header throws, so the owner reports a failure instead of an empty success. Cancellation propagates.
 *
 * Nothing here logs the PCM, the signature, the request body or the reply, and every thrown message is a fixed string.
 *
 * The borrowed [client] supplies transport and should keep the platform's proxy-aware engine. Content negotiation is not
 * required because the JSON body is encoded here. Each attempt is bounded by [REQUEST_TIMEOUT_MILLIS] in this class,
 * because the client's own timeout plugin cannot be assumed. Requests are serialized and spaced by
 * [MIN_REQUEST_INTERVAL_MILLIS]; a successful match is kept for [CACHE_DURATION_MILLIS] per instance.
 */
class ShazamRecognitionMatcher(
    private val signatures: RecognitionSignaturePort,
    private val client: HttpClient,
    private val clock: RecognitionClock,
    private val random: Random = Random.Default,
) : RecognitionMatchPort {
    private val requestLock = Mutex()

    // Both fields are guarded by requestLock.
    private var lastRequestAtMillis: Long? = null
    private val resultCache = mutableMapOf<String, CachedTrack>()

    override suspend fun match(samples: ShortArray): RecognitionMatchOutcome {
        if (samples.isEmpty()) return RecognitionMatchOutcome.SignatureUnavailable
        val signature =
            signatures.generateSignature(samples)?.takeIf { it.isNotBlank() }
                ?: return RecognitionMatchOutcome.SignatureUnavailable
        val sampleDurationMillis = signedDurationMillis(signature)
        return requestLock.withLock { matchLocked(signature, sampleDurationMillis) }
    }

    private suspend fun matchLocked(
        signature: String,
        sampleDurationMillis: Long,
    ): RecognitionMatchOutcome {
        cachedTrack(signature)?.let { return RecognitionMatchOutcome.Matched(it) }
        val outcome = discover(signature, sampleDurationMillis)
        if (outcome is RecognitionMatchOutcome.Matched) cacheTrack(signature, outcome.track)
        return outcome
    }

    /** Retries only rate limits, with exponential backoff, as the baseline client does. */
    private suspend fun discover(
        signature: String,
        sampleDurationMillis: Long,
    ): RecognitionMatchOutcome {
        for (attempt in 0 until MAX_ATTEMPTS) {
            awaitRequestSpacing()
            val outcome = withRequestTimeout { discoverOnce(signature, sampleDurationMillis) }
            if (outcome != null) return outcome
            if (attempt < MAX_ATTEMPTS - 1) delay(backoffMillis(attempt))
        }
        throw IllegalStateException(RATE_LIMITED_MESSAGE)
    }

    /** Null means the provider rate-limited this attempt and the caller may retry. */
    private suspend fun discoverOnce(
        signature: String,
        sampleDurationMillis: Long,
    ): RecognitionMatchOutcome? {
        val timestampSeconds = clock.nowEpochMillis() / 1000
        val firstId = randomUuid().uppercase()
        val secondId = randomUuid()
        val body =
            json.encodeToString(
                ShazamDiscoveryRequest.serializer(),
                ShazamDiscoveryRequest(
                    geolocation =
                        ShazamDiscoveryGeolocation(
                            altitude = random.nextDouble() * 400 + 100,
                            latitude = random.nextDouble() * 180 - 90,
                            longitude = random.nextDouble() * 360 - 180,
                        ),
                    signature =
                        ShazamDiscoverySignature(
                            samplems = sampleDurationMillis,
                            timestamp = timestampSeconds,
                            uri = signature,
                        ),
                    timestamp = timestampSeconds,
                    timezone = TIMEZONES[random.nextInt(TIMEZONES.size)],
                ),
            )
        val response: HttpResponse =
            try {
                client.post("$DISCOVERY_URL/$firstId/$secondId") {
                    parameter("sync", "true")
                    parameter("webv3", "true")
                    parameter("sampling", "true")
                    parameter("connected", "")
                    parameter("shazamapiversion", "v3")
                    parameter("sharehub", "true")
                    parameter("video", "v3")
                    header("User-Agent", USER_AGENTS[random.nextInt(USER_AGENTS.size)])
                    header("Content-Language", "en_US")
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            } catch (failure: ResponseException) {
                // Only the status is read here. A client configured to expect success still yields its failed response.
                failure.response
            }
        val code = response.status.value
        return when {
            response.status.isSuccess() -> decodeReply(response.bodyAsText()).toOutcome()
            code == HTTP_NOT_FOUND -> RecognitionMatchOutcome.NoMatch
            code == HTTP_TOO_MANY_REQUESTS -> null
            code in 500..599 -> throw IllegalStateException(UNAVAILABLE_MESSAGE)
            else -> throw IllegalStateException("Recognition failed (error $code)")
        }
    }

    private suspend fun awaitRequestSpacing() {
        lastRequestAtMillis?.let { last ->
            val elapsed = clock.nowEpochMillis() - last
            if (elapsed in 0L until MIN_REQUEST_INTERVAL_MILLIS) delay(MIN_REQUEST_INTERVAL_MILLIS - elapsed)
        }
        lastRequestAtMillis = clock.nowEpochMillis()
    }

    private suspend fun <T> withRequestTimeout(block: suspend () -> T): T =
        try {
            withTimeout(REQUEST_TIMEOUT_MILLIS) { block() }
        } catch (timeout: TimeoutCancellationException) {
            throw IllegalStateException(TIMED_OUT_MESSAGE)
        }

    private fun decodeReply(text: String): ShazamDiscoveryReply =
        try {
            json.decodeFromString(ShazamDiscoveryReply.serializer(), text)
        } catch (failure: IllegalArgumentException) {
            // The parser's own message can quote reply text, so it is not carried forward.
            throw IllegalStateException(REPLY_UNREADABLE_MESSAGE)
        }

    /** Maps the reply the way the baseline client does, except that a track without a title is a failure. */
    private fun ShazamDiscoveryReply.toOutcome(): RecognitionMatchOutcome {
        val track = this.track ?: return RecognitionMatchOutcome.NoMatch
        val title = track.title?.takeIf { it.isNotBlank() } ?: throw IllegalStateException(TRACK_UNREADABLE_MESSAGE)
        val songMetadata = track.sections?.find { it?.type == SONG_SECTION }?.metadata
        val trackId = track.key?.takeIf { it.isNotBlank() } ?: this.tagid?.takeIf { it.isNotBlank() } ?: ""
        return RecognitionMatchOutcome.Matched(
            RecognizedTrack(
                trackId = trackId,
                title = title,
                artist = track.subtitle ?: "",
                album = songMetadata.textOf(ALBUM_TITLE),
                coverArtUrl = track.images?.coverart,
                coverArtHqUrl = track.images?.coverarthq,
                genre = track.genres?.primary,
                releaseDate = songMetadata.textOf(RELEASED_TITLE),
                label = songMetadata.textOf(LABEL_TITLE),
                lyrics = track.sections?.find { it?.type == LYRICS_SECTION }?.text.orEmpty(),
                shazamUrl = track.url,
                isrc = track.isrc,
            ),
        )
    }

    private fun List<ShazamDiscoveryMetadata?>?.textOf(title: String): String? = this?.find { it?.title == title }?.text

    /**
     * The DejaVu header stores the signed sample count plus a fixed padding of [RecognitionCaptureSpec.SAMPLE_RATE_HZ]
     * times 0.24 seconds at byte offset 40, little-endian. The signed duration is recovered from that count, so the
     * request never trusts a duration supplied from elsewhere.
     */
    @OptIn(ExperimentalEncodingApi::class)
    private fun signedDurationMillis(signature: String): Long {
        if (!signature.startsWith(SIGNATURE_URI_PREFIX)) throw IllegalStateException(SIGNATURE_UNREADABLE_MESSAGE)
        val header =
            try {
                Base64.Default.decode(signature.substring(SIGNATURE_URI_PREFIX.length))
            } catch (failure: IllegalArgumentException) {
                throw IllegalStateException(SIGNATURE_UNREADABLE_MESSAGE)
            }
        if (header.size < SIGNATURE_HEADER_BYTES) throw IllegalStateException(SIGNATURE_UNREADABLE_MESSAGE)
        val sampleCount = littleEndianInt(header, SAMPLE_COUNT_OFFSET).toLong() - HEADER_SAMPLE_PADDING
        if (sampleCount <= 0L) throw IllegalStateException(SIGNATURE_UNREADABLE_MESSAGE)
        return sampleCount * 1000L / RecognitionCaptureSpec.SAMPLE_RATE_HZ
    }

    private fun littleEndianInt(
        bytes: ByteArray,
        offset: Int,
    ): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)

    private fun randomUuid(): String {
        val bytes = random.nextBytes(16)
        bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x40).toByte()
        bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
        val hex = bytes.joinToString(separator = "") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
            "${hex.substring(16, 20)}-${hex.substring(20)}"
    }

    private fun backoffMillis(attempt: Int): Long = INITIAL_RETRY_DELAY_MILLIS * (1L shl attempt)

    private fun cachedTrack(signature: String): RecognizedTrack? {
        val cached = resultCache[signature] ?: return null
        if (clock.nowEpochMillis() - cached.storedAtMillis > CACHE_DURATION_MILLIS) {
            resultCache.remove(signature)
            return null
        }
        return cached.track
    }

    private fun cacheTrack(
        signature: String,
        track: RecognizedTrack,
    ) {
        val now = clock.nowEpochMillis()
        resultCache.entries.removeAll { now - it.value.storedAtMillis > CACHE_DURATION_MILLIS }
        resultCache[signature] = CachedTrack(storedAtMillis = now, track = track)
        while (resultCache.size > MAX_CACHED_TRACKS) {
            resultCache.remove(resultCache.keys.first())
        }
    }

    private class CachedTrack(
        val storedAtMillis: Long,
        val track: RecognizedTrack,
    )

    private companion object {
        const val DISCOVERY_URL = "https://amp.shazam.com/discovery/v5/en/US/android/-/tag"
        const val SIGNATURE_URI_PREFIX = "data:audio/vnd.shazam.sig;base64,"
        const val SIGNATURE_HEADER_BYTES = 48
        const val SAMPLE_COUNT_OFFSET = 40
        val HEADER_SAMPLE_PADDING: Long = (RecognitionCaptureSpec.SAMPLE_RATE_HZ * 0.24).toLong()

        const val MAX_ATTEMPTS = 3
        const val INITIAL_RETRY_DELAY_MILLIS = 2_000L
        const val MIN_REQUEST_INTERVAL_MILLIS = 1_000L
        const val REQUEST_TIMEOUT_MILLIS = 30_000L
        const val CACHE_DURATION_MILLIS = 300_000L
        const val MAX_CACHED_TRACKS = 16

        const val HTTP_NOT_FOUND = 404
        const val HTTP_TOO_MANY_REQUESTS = 429

        const val SONG_SECTION = "SONG"
        const val LYRICS_SECTION = "LYRICS"
        const val ALBUM_TITLE = "Album"
        const val RELEASED_TITLE = "Released"
        const val LABEL_TITLE = "Label"

        const val RATE_LIMITED_MESSAGE = "Shazam is rate limiting recognition requests"
        const val UNAVAILABLE_MESSAGE = "Shazam service temporarily unavailable"
        const val TIMED_OUT_MESSAGE = "Shazam request timed out"
        const val REPLY_UNREADABLE_MESSAGE = "Shazam reply could not be read"
        const val TRACK_UNREADABLE_MESSAGE = "Shazam reply had no usable track"
        const val SIGNATURE_UNREADABLE_MESSAGE = "Recognition signature has no readable sample count"

        val json =
            Json {
                isLenient = true
                ignoreUnknownKeys = true
                encodeDefaults = true
            }

        val USER_AGENTS =
            listOf(
                "Dalvik/2.1.0 (Linux; U; Android 5.0.2; VS980 4G Build/LRX22G)",
                "Dalvik/1.6.0 (Linux; U; Android 4.4.2; SM-T210 Build/KOT49H)",
                "Dalvik/2.1.0 (Linux; U; Android 5.1.1; SM-P905V Build/LMY47X)",
                "Dalvik/2.1.0 (Linux; U; Android 6.0.1; SM-G920F Build/MMB29K)",
                "Dalvik/2.1.0 (Linux; U; Android 5.0; SM-G900F Build/LRX21T)",
            )

        val TIMEZONES =
            listOf(
                "Europe/Paris",
                "Europe/London",
                "America/New_York",
                "America/Los_Angeles",
                "Asia/Tokyo",
                "Asia/Dubai",
            )
    }
}
