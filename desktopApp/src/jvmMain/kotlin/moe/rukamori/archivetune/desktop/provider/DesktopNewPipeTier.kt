package moe.rukamori.archivetune.desktop.provider

import io.ktor.client.HttpClient
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import moe.rukamori.archivetune.shared.data.potoken.MintedPoTokens
import moe.rukamori.archivetune.shared.data.potoken.appendPoTokenToStreamUrl
import moe.rukamori.archivetune.shared.data.potoken.poTokenForWebClient
import moe.rukamori.archivetune.shared.domain.repository.PreparedAudio
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod

private const val WATCH_URL_PREFIX = "https://www.youtube.com/watch?v="

/**
 * The NewPipe tier of the desktop YouTube chain, the desktop counterpart of the Android `NewPipeStreamRepository`.
 *
 * NewPipe's own YouTube stream extractor runs first: its player request, client set and signature and n-parameter
 * transforms share nothing with the primary walk. If it yields nothing usable, the visitor-capable clients are asked
 * directly and every ciphered format is deciphered through NewPipe's JavaScript player, the same shared path the
 * primary walk now uses. Neither path carries the signed-in session: a track the signed-in walk was refused is often
 * served to a plain visitor. NewPipe's process-wide downloader is the one `:core` installs.
 */
internal class DesktopNewPipeTier(
    private val http: HttpClient,
    private val locale: () -> Locale = { Locale.getDefault() },
    private val mint: suspend (videoId: String, visitorData: String?) -> MintedPoTokens? = { _, _ -> null },
) {
    @Volatile
    private var ready = false

    suspend fun resolve(
        videoId: String,
        quality: DesktopYouTubeAudioQuality,
        trusted: Boolean,
        visitorData: String?,
    ): PreparedAudio? {
        ensureReady()
        // One mint per resolve feeds both paths, as the shipped authState feeds every NewPipe URL.
        // The mint stays optional and bounded well inside the 12 s tier budget: a stalled BotGuard
        // endpoint must not starve extraction, so a timed-out or failed mint just proceeds untokened.
        val minted =
            try {
                withTimeoutOrNull(MINT_BUDGET_MS) { mint(videoId, visitorData) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        var failure: Throwable? = null
        try {
            fromStreamExtractor(videoId, quality, trusted, minted)?.let { return it }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            failure = error
        }
        for (client in ANONYMOUS_CLIENTS) {
            try {
                fromPlayerClient(videoId, client, quality, trusted, visitorData, minted)?.let { return it }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failure?.let(error::addSuppressed)
                failure = error
            }
        }
        failure?.let { throw DesktopNewPipeException("NewPipe could not resolve a stream for $videoId", it) }
        return null
    }

    /**
     * The video-only stream of a music video for the player's video artwork, the desktop counterpart of Android's
     * `resolveVideoStreamUrl`. The visitor clients are asked in order; the first that names the video type decides it:
     * an art track or any other non-video type keeps the artwork, as Android shows video only for music videos. Formats
     * are deciphered the way audio formats are, so the URL plays with the client's user agent.
     */
    suspend fun resolveVideo(videoId: String, maxHeight: Int, visitorData: String?): DesktopVideoArtworkResult? {
        ensureReady()
        var failure: Throwable? = null
        for (client in VIDEO_CLIENTS) {
            try {
                val signatureTimestamp =
                    if (client.useSignatureTimestamp) {
                        runInterruptible(Dispatchers.IO) {
                            withPlayerCacheRecovery { YoutubeJavaScriptPlayerManager.getSignatureTimestamp(videoId) }
                        }
                    } else {
                        null
                    }
                val region = locale().country.takeIf { it.length == 2 }?.uppercase(Locale.US) ?: "US"
                val language = locale().toLanguageTag().ifBlank { "en" }
                val response = requestYouTubePlayer(
                    http, videoId, null, client, signatureTimestamp, visitorData, region, language, signIn = null,
                )
                if (!response.playabilityStatus().equals("OK", ignoreCase = true)) continue
                val type = response.videoDetails()?.stringOf("musicVideoType") ?: continue
                if (type !in MUSIC_VIDEO_TYPES) return DesktopVideoArtworkResult.NotAMusicVideo
                for (candidate in rankVideoFormats(response.videoFormats(), maxHeight)) {
                    val url = try {
                        runInterruptible(Dispatchers.IO) {
                            playableUrl(videoId, candidate.url, candidate.signatureCipher, candidate.cipher)
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        continue
                    }
                    return DesktopVideoArtworkResult.Stream(url, client.userAgent, candidate.height)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failure?.let(error::addSuppressed)
                failure = error
            }
        }
        failure?.let { throw DesktopNewPipeException("no video stream resolved for $videoId", it) }
        return null
    }

    private fun ensureReady() {
        if (ready) return
        synchronized(this) {
            if (ready) return
            DesktopYouTubeJavaScript.ensureExtractorReady()
            ready = true
        }
    }

    private suspend fun fromStreamExtractor(
        videoId: String,
        quality: DesktopYouTubeAudioQuality,
        trusted: Boolean,
        minted: MintedPoTokens?,
    ): PreparedAudio? {
        // The extractor blocks; runInterruptible lets the tier timeout interrupt its calls instead of abandoning them.
        val extractor = runInterruptible(Dispatchers.IO) {
            ServiceList.YouTube.getStreamExtractor(WATCH_URL_PREFIX + videoId).also { it.fetchPage() }
        }
        val streams = runInterruptible(Dispatchers.IO) { extractor.audioStreams }
        val formats = streams
            .filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.content.isNotBlank() }
            .map { it.toDesktopFormat() }
        val format = rankAudioFormats(formats, quality, networkMetered = false).firstOrNull() ?: return null
        val url = format.url ?: return null
        return PreparedAudio.Progressive(
            format.toDirectStream(
                // The extractor path names no client, so like resolveGvsPoToken(null) the video-bound
                // token travels whenever one was minted for this video.
                uri = appendPoTokenToStreamUrl(url, minted?.playerToken),
                label = "YouTube NewPipe ${format.bitrate / 1000} kbps",
                title = runCatching { extractor.name }.getOrNull(),
                author = runCatching { extractor.uploaderName }.getOrNull(),
                durationMs = runCatching { extractor.length }.getOrNull()?.takeIf { it > 0L }?.times(1_000L),
                trusted = trusted,
                userAgent = null,
            ),
        )
    }

    private suspend fun fromPlayerClient(
        videoId: String,
        client: DesktopYouTubeClient,
        quality: DesktopYouTubeAudioQuality,
        trusted: Boolean,
        visitorData: String?,
        minted: MintedPoTokens?,
    ): PreparedAudio? {
        val signatureTimestamp =
            if (client.useSignatureTimestamp) {
                runInterruptible(Dispatchers.IO) {
                    withPlayerCacheRecovery { YoutubeJavaScriptPlayerManager.getSignatureTimestamp(videoId) }
                }
            } else {
                null
            }
        val region = locale().country.takeIf { it.length == 2 }?.uppercase(Locale.US) ?: "US"
        val language = locale().toLanguageTag().ifBlank { "en" }
        val response = requestYouTubePlayer(
            http, videoId, null, client, signatureTimestamp, visitorData, region, language, signIn = null,
            poToken = poTokenForWebClient(client.clientName, minted),
        )
        if (!response.playabilityStatus().equals("OK", ignoreCase = true)) return null
        val details = response.videoDetails()
        for (candidate in rankAudioFormats(response.audioFormats(), quality, networkMetered = false)) {
            val url = try {
                runInterruptible(Dispatchers.IO) {
                    playableUrl(videoId, candidate.url, candidate.signatureCipher, candidate.cipher)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                continue
            }
            return PreparedAudio.Progressive(
                candidate.toDirectStream(
                    uri = appendPoTokenToStreamUrl(url, poTokenForWebClient(client.clientName, minted)),
                    label = "YouTube NewPipe ${client.friendlyName ?: client.clientName} ${candidate.bitrate / 1000} kbps",
                    title = details?.stringOf("title"),
                    author = details?.stringOf("author"),
                    durationMs = details?.stringOf("lengthSeconds")?.toLongOrNull()?.times(1_000L),
                    trusted = trusted,
                    userAgent = client.userAgent,
                ),
            )
        }
        return null
    }

    /** The signed, n-transformed URL of one format through the shared JavaScript-player path. */
    private fun playableUrl(videoId: String, directUrl: String?, signatureCipher: String?, legacyCipher: String?): String =
        DesktopYouTubeJavaScript.playableUrl(videoId, directUrl, signatureCipher, legacyCipher)

    /** A rotated player script leaves NewPipe's cached deobfuscation stale; one cache clear and retry recovers it. */
    private inline fun <T> withPlayerCacheRecovery(block: () -> T): T =
        DesktopYouTubeJavaScript.withPlayerCacheRecovery(block)

    private companion object {
        /** The BotGuard mint allowance inside the 12 s tier bound, leaving the rest to extraction. */
        const val MINT_BUDGET_MS = 3_000L

        /** Visitor-capable clients, most likely first, as the Android tier walks them. */
        val ANONYMOUS_CLIENTS = listOf(
            DesktopYouTubeClients.ANDROID_MUSIC,
            DesktopYouTubeClients.IOS,
            DesktopYouTubeClients.MAIN_CLIENT,
        )

        /** The music clients, which name the video type; Android asks the same profiles for video. */
        val VIDEO_CLIENTS = listOf(
            DesktopYouTubeClients.ANDROID_MUSIC,
            DesktopYouTubeClients.MAIN_CLIENT,
        )

        /** Official and user-uploaded music videos; an art track (ATV) is a song with a still picture. */
        val MUSIC_VIDEO_TYPES = setOf("MUSIC_VIDEO_TYPE_OMV", "MUSIC_VIDEO_TYPE_UGC")
    }
}

internal class DesktopNewPipeException(message: String, cause: Throwable?) : IllegalStateException(message, cause)

private fun AudioStream.toDesktopFormat(): DesktopAudioFormat {
    val item = itagItem
    val codecName = item?.codec?.takeIf { it.isNotBlank() } ?: codec?.takeIf { it.isNotBlank() }
    val baseMimeType = format?.mimeType ?: "audio/webm"
    return DesktopAudioFormat(
        itag = itag,
        url = content,
        signatureCipher = null,
        cipher = null,
        mimeType = if (codecName != null) "$baseMimeType; codecs=\"$codecName\"" else baseMimeType,
        bitrate = bitrate.takeIf { it > 0 } ?: (averageBitrate.coerceAtLeast(0) * 1000),
        audioSampleRate = item?.sampleRate?.takeIf { it > 0 },
        contentLength = item?.contentLength?.takeIf { it > 0L },
        // NewPipe marks the released track by appending "(original)" to its name; a stream without a track id is the
        // only track there is.
        isDefaultAudioTrack = audioTrackId == null || audioTrackName?.contains("(original)") == true,
    )
}
