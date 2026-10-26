package moe.rukamori.archivetune.desktop.provider

import io.ktor.client.HttpClient
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.parseQueryString
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import moe.rukamori.archivetune.innertube.NewPipeUtils
import moe.rukamori.archivetune.shared.domain.repository.PreparedAudio
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod

private const val WATCH_URL_PREFIX = "https://www.youtube.com/watch?v="

/**
 * The NewPipe tier of the desktop YouTube chain, the desktop counterpart of the Android `NewPipeStreamRepository`.
 *
 * NewPipe's own YouTube stream extractor runs first: its player request, client set and signature and n-parameter
 * transforms share nothing with the primary walk. If it yields nothing usable, the visitor-capable clients are asked
 * directly and every ciphered format is deciphered through NewPipe's JavaScript player, which is the one thing the
 * primary walk cannot do. Neither path carries the signed-in session: a track the signed-in walk was refused is often
 * served to a plain visitor. NewPipe's process-wide downloader is the one `:core` installs.
 */
internal class DesktopNewPipeTier(
    private val http: HttpClient,
    private val locale: () -> Locale = { Locale.getDefault() },
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
        var failure: Throwable? = null
        try {
            fromStreamExtractor(videoId, quality, trusted)?.let { return it }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            failure = error
        }
        for (client in ANONYMOUS_CLIENTS) {
            try {
                fromPlayerClient(videoId, client, quality, trusted, visitorData)?.let { return it }
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
            // Touching the object runs its initializer, which installs the downloader NewPipe needs.
            if (NewPipe.getDownloader() == null) NewPipeUtils.hashCode()
            // The stream extractor otherwise asks a third-party dislike service for every track; nothing reads it.
            ServiceList.YouTube.setFetchDislike(false)
            ready = true
        }
    }

    private suspend fun fromStreamExtractor(
        videoId: String,
        quality: DesktopYouTubeAudioQuality,
        trusted: Boolean,
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
                uri = url,
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
                    uri = url,
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

    /** The signed, n-transformed URL of one format, deciphering a `signatureCipher` or `cipher` entry when needed. */
    private fun playableUrl(videoId: String, directUrl: String?, signatureCipher: String?, legacyCipher: String?): String {
        val base = directUrl ?: run {
            val cipher = signatureCipher ?: legacyCipher ?: throw ParsingException("Could not find format url")
            val params = parseQueryString(cipher)
            val signature = params["s"] ?: throw ParsingException("Could not parse cipher signature")
            val signatureParam = params["sp"]?.takeIf(String::isNotBlank) ?: "signature"
            val url = params["url"] ?: throw ParsingException("Could not parse cipher url")
            val deciphered = withPlayerCacheRecovery { YoutubeJavaScriptPlayerManager.deobfuscateSignature(videoId, signature) }
            URLBuilder(url).apply { parameters[signatureParam] = deciphered }.buildString()
        }
        if (Url(base).parameters["n"].isNullOrBlank()) return base
        return withPlayerCacheRecovery {
            YoutubeJavaScriptPlayerManager.getUrlWithThrottlingParameterDeobfuscated(videoId, base)
        }
    }

    /** A rotated player script leaves NewPipe's cached deobfuscation stale; one cache clear and retry recovers it. */
    private inline fun <T> withPlayerCacheRecovery(block: () -> T): T {
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

    private companion object {
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
