package moe.rukamori.archivetune.desktop.provider

import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.parseQueryString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import moe.rukamori.archivetune.innertube.NewPipeUtils
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager

/**
 * The one JavaScript-player path the desktop tiers share. NewPipe's Rhino player already runs on this host (tier 3),
 * so the primary walk reuses it instead of adding a second runtime: [signatureTimestamp] names the player version a
 * `playbackContext` request has to state, and [playableUrl] undoes a `signatureCipher` and rewrites the throttling
 * `n` parameter exactly as the extractor does.
 *
 * Every method here blocks in the JavaScript interpreter and in NewPipe's downloader, so callers run them on an IO
 * dispatcher inside a timeout. Nothing is cached here beyond what NewPipe caches per player; [NewPipeDesktopYouTubeStreamCipher]
 * adds the short-lived timestamp cache the client walk needs because it asks once per resolve rather than per video.
 */
internal object DesktopYouTubeJavaScript {
    /** Installs NewPipe's downloader and turns off its dislike lookup; idempotent and process-wide. */
    fun ensureExtractorReady() {
        if (NewPipe.getDownloader() == null) NewPipeUtils.hashCode()
        ServiceList.YouTube.setFetchDislike(false)
    }

    /** The `signatureTimestamp` of the player script NewPipe holds for [videoId]. */
    fun signatureTimestamp(videoId: String): Int {
        ensureExtractorReady()
        return withPlayerCacheRecovery { YoutubeJavaScriptPlayerManager.getSignatureTimestamp(videoId) }
    }

    /**
     * The signed, n-transformed URL of one format. A direct [directUrl] is kept and only its `n` is rewritten; a
     * ciphered entry is deciphered with the player's signature transform first. A URL with no `n` is returned as is,
     * because asking the player for a transform that does not exist would only fail.
     */
    fun playableUrl(
        videoId: String,
        directUrl: String?,
        signatureCipher: String?,
        legacyCipher: String?,
    ): String {
        val base = directUrl ?: run {
            val cipher = signatureCipher ?: legacyCipher ?: throw ParsingException("Could not find format url")
            val params = parseQueryString(cipher)
            val signature = params["s"] ?: throw ParsingException("Could not parse cipher signature")
            val signatureParam = params["sp"]?.takeIf(String::isNotBlank) ?: "signature"
            val url = params["url"] ?: throw ParsingException("Could not parse cipher url")
            val deciphered =
                withPlayerCacheRecovery { YoutubeJavaScriptPlayerManager.deobfuscateSignature(videoId, signature) }
            URLBuilder(url).apply { parameters[signatureParam] = deciphered }.buildString()
        }
        if (Url(base).parameters["n"].isNullOrBlank()) return base
        return withPlayerCacheRecovery {
            YoutubeJavaScriptPlayerManager.getUrlWithThrottlingParameterDeobfuscated(videoId, base)
        }
    }

    /** A rotated player script leaves NewPipe's cached deobfuscation stale; one cache clear and retry recovers it. */
    fun <T> withPlayerCacheRecovery(block: () -> T): T {
        try {
            return block()
        } catch (error: Exception) {
            if (!error.isStalePlayerScript()) throw error
            runCatching { YoutubeJavaScriptPlayerManager.clearAllCaches() }
            try {
                return block()
            } catch (retry: Exception) {
                retry.addSuppressed(error)
                throw retry
            }
        }
    }

    private fun Throwable.isStalePlayerScript(): Boolean =
        generateSequence(this) { it.cause }.take(8).any {
            it is ParsingException && it.message?.contains("deobfuscation function", ignoreCase = true) == true
        }
}

/**
 * The cipher surface the primary client walk depends on. The walk is bounded by its own budget, so the surface is
 * asynchronous: the NewPipe implementation moves each blocking call to an IO dispatcher under a timeout, and a test
 * can answer synthetically without a network or a JavaScript interpreter.
 */
internal interface DesktopYouTubeStreamCipher {
    /** The player timestamp to state in the request, or null when it could not be obtained. */
    suspend fun signatureTimestamp(videoId: String): Int?

    /**
     * The playable URL for one format, or null when the format carries no usable URL. A direct URL that the `n`
     * transform could not rewrite is still returned, because an untransformed URL is throttled rather than unusable.
     */
    suspend fun playableUrl(
        videoId: String,
        url: String?,
        signatureCipher: String?,
        legacyCipher: String?,
    ): String?
}

/**
 * The shipped [DesktopYouTubeStreamCipher] over [DesktopYouTubeJavaScript].
 *
 * The timestamp is cached for [SIGNATURE_TIMESTAMP_TTL_MS] because every resolve of every track reads the same player
 * version, and re-reading it for each track would put a player-script download on the playback path. A cipher or `n`
 * transform failure is not fatal: a direct URL survives it untransformed, and a ciphered entry without a URL is
 * skipped so the next candidate or client can answer.
 */
internal class NewPipeDesktopYouTubeStreamCipher(
    private val nowMs: () -> Long = System::currentTimeMillis,
) : DesktopYouTubeStreamCipher {
    @Volatile
    private var timestamp: Pair<Int, Long>? = null

    override suspend fun signatureTimestamp(videoId: String): Int? {
        val cached = timestamp
        if (cached != null && nowMs() - cached.second in 0 until SIGNATURE_TIMESTAMP_TTL_MS) return cached.first
        val fetched =
            try {
                withTimeoutOrNull(SIGNATURE_TIMESTAMP_BUDGET_MS) {
                    runInterruptible(Dispatchers.IO) { DesktopYouTubeJavaScript.signatureTimestamp(videoId) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            } ?: return null
        timestamp = fetched to nowMs()
        return fetched
    }

    override suspend fun playableUrl(
        videoId: String,
        url: String?,
        signatureCipher: String?,
        legacyCipher: String?,
    ): String? {
        val transformed =
            try {
                withTimeoutOrNull(TRANSFORM_BUDGET_MS) {
                    runInterruptible(Dispatchers.IO) {
                        DesktopYouTubeJavaScript.playableUrl(videoId, url, signatureCipher, legacyCipher)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        // A direct URL stays playable without the n transform (throttled); a ciphered entry does not.
        return transformed ?: url
    }

    private companion object {
        /** Long enough for the first player-script read, short enough to leave the client walk its budget. */
        const val SIGNATURE_TIMESTAMP_BUDGET_MS = 5_000L
        const val TRANSFORM_BUDGET_MS = 6_000L

        /** The player version changes far less often than this; a stale value is rejected by the next response. */
        const val SIGNATURE_TIMESTAMP_TTL_MS = 10 * 60_000L
    }
}
