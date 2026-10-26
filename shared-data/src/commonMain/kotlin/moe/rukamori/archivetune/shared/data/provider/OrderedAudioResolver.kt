/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.data.provider

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import moe.rukamori.archivetune.shared.domain.repository.*
import moe.rukamori.archivetune.shared.domain.source.*

interface YouTubeResolutionPort {
    suspend fun primary(request: AudioResolveRequest): PreparedAudio?
    suspend fun fallback(tier: YouTubeFallbackTier, request: AudioResolveRequest): PreparedAudio?
}
enum class AudioAttemptResult { UNAVAILABLE, NO_MATCH, REJECTED_METADATA, TIMEOUT, FAILED, RESOLVED }
data class AudioAttempt(val source: AudioSourceType, val result: AudioAttemptResult)
sealed interface AudioResolution {
    data class Resolved(val audio: PreparedAudio, val attempts: List<AudioAttempt>) : AudioResolution
    data class Unavailable(val attempts: List<AudioAttempt>) : AudioResolution
    /** NOT an engine locator: preserve the exact URI for the existing native scheme handler. */
    data class NativeRoute(val uri: String) : AudioResolution
}

/** Reuses M1 source priority/title/auth/tier policies. It owns neither queue nor resolved-URL cache. */
class OrderedAudioResolver(providers: List<AudioProviderRepository>, private val youtube: YouTubeResolutionPort) {
    private val providers = providers.associateBy { it.source }
    init {
        require(this.providers.size == providers.size)
        require(AudioSourceType.YOUTUBE !in this.providers) { "YouTube tiers have one terminal owner" }
    }
    suspend fun resolve(request: AudioResolveRequest, settings: PlaybackSourceSnapshot,
        pinned: AudioSourceType? = null, rejected: Set<AudioSourceType> = emptySet(),
        nativeUri: String? = null): AudioResolution {
        if (nativeUri != null) {
            require(nativeUri.startsWith("telegram://") || nativeUri.startsWith("tidal-dash://") ||
                nativeUri.startsWith("deezer://")) { "Unknown native source route" }
            require(request.purpose == AudioPurpose.PLAYBACK) { "Native routes require the host download preparer" }
            return AudioResolution.NativeRoute(nativeUri)
        }
        val attempts = mutableListOf<AudioAttempt>()
        for (source in PlaybackSourceChain.configuredChain(settings, pinned, rejected).distinct()) {
            if (source in rejected || source !in settings.enabled ||
                request.purpose == AudioPurpose.DOWNLOAD && source == AudioSourceType.SPOTIFY) continue
            val provider = providers[source]
            if (provider == null) { attempts += AudioAttempt(source, AudioAttemptResult.UNAVAILABLE); continue }
            val audio = try {
                withTimeoutOrNull(30_000) { provider.resolve(request) }
            } catch (auth: TerminalPlaybackAuthentication) { throw auth }
            catch (cancel: CancellationException) { throw cancel }
            catch (failure: ProviderRequestTimeout) {
                attempts += AudioAttempt(source, AudioAttemptResult.TIMEOUT); continue
            }
            catch (failure: Exception) {
                attempts += AudioAttempt(source, AudioAttemptResult.FAILED); continue
            }
            if (audio == null) { attempts += AudioAttempt(source, AudioAttemptResult.NO_MATCH); continue }
            require(audio.source == source) { "Provider returned another source" }
            if (!audio.matchesRequest(request)) {
                attempts += AudioAttempt(source, AudioAttemptResult.REJECTED_METADATA); continue
            }
            attempts += AudioAttempt(source, AudioAttemptResult.RESOLVED)
            return AudioResolution.Resolved(audio, attempts.toList())
        }
        if (PlaybackSourceChain.youTubeTerminalAllowed(settings, rejected)) {
            var failure = false
            val primary = try { withTimeoutOrNull(30_000) { youtube.primary(request) } }
                catch (auth: TerminalPlaybackAuthentication) { throw auth }
                catch (cancel: CancellationException) { throw cancel }
                catch (error: Exception) { failure = true; null }
            if (primary != null && primary.source == AudioSourceType.YOUTUBE && primary.matchesRequest(request))
                return AudioResolution.Resolved(primary, attempts + AudioAttempt(AudioSourceType.YOUTUBE, AudioAttemptResult.RESOLVED))
            var terminalAuth: TerminalPlaybackAuthentication? = null
            val recovered = YouTubeFailsafePolicy.recoverAfterPrimary(YouTubeResolveFailure.OTHER) { tier, timeout ->
                if (terminalAuth != null) return@recoverAfterPrimary null
                withTimeoutOrNull(timeout) {
                    val audio = try { youtube.fallback(tier, request) }
                        catch (auth: TerminalPlaybackAuthentication) { terminalAuth = auth; null }
                    audio?.takeIf { it.source == AudioSourceType.YOUTUBE && it.matchesRequest(request) }
                }
            }
            terminalAuth?.let { throw it }
            if (recovered is YouTubeRecovery.Resolved)
                return AudioResolution.Resolved(recovered.value, attempts + AudioAttempt(AudioSourceType.YOUTUBE, AudioAttemptResult.RESOLVED))
            attempts += AudioAttempt(AudioSourceType.YOUTUBE, if (failure) AudioAttemptResult.FAILED else AudioAttemptResult.NO_MATCH)
        }
        return AudioResolution.Unavailable(attempts.toList())
    }
}

internal fun PreparedAudio.matchesRequest(request: AudioResolveRequest): Boolean {
    val stream = when (this) {
        is PreparedAudio.Progressive -> stream
        is PreparedAudio.DeezerEncrypted -> stream
        is PreparedAudio.Native -> stream
        // Evidence only. This empty URI never escapes as a playback/download locator.
        is PreparedAudio.TidalManifest -> DirectStream("", "", "", null, "Tidal manifest", source,
            track.item.title, track.artists.joinToString(", "), track.album, track.item.durationMs, trustedDirectId)
    }
    return TitleMatch.evaluate(request.target.title, request.target.artists, request.target.album,
        request.target.durationMs, stream).accepted
}
