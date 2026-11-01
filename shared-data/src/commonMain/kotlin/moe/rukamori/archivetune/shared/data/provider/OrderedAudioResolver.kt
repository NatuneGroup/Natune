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
data class AudioAttempt(val source: AudioSourceType, val result: AudioAttemptResult,
    val unavailableReason: AudioProviderUnavailableReason? = null)
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
        val chain = ExternalChain()
        // The external sources share one whole-chain budget; YouTube below keeps its own per-tier deadlines.
        withTimeoutOrNull(FALLBACK_CHAIN_MS) { resolveExternal(request, settings, pinned, rejected, chain) }?.let { return it }
        // The chain deadline can cut an attempt in flight. That source timed out; it did not find no match.
        chain.inFlight?.let { chain.attempts += AudioAttempt(it, AudioAttemptResult.TIMEOUT) }
        val attempts = chain.attempts
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

    /** Attempts recorded so far; [inFlight] names the source whose attempt has not finished. */
    private class ExternalChain {
        val attempts = mutableListOf<AudioAttempt>()
        var inFlight: AudioSourceType? = null
    }

    /** Wraps a provider answer so its own budget expiring (null) stays distinct from a provider's no-match (audio null). */
    private class ProviderOutcome(val audio: PreparedAudio?)

    private suspend fun resolveExternal(request: AudioResolveRequest, settings: PlaybackSourceSnapshot,
        pinned: AudioSourceType?, rejected: Set<AudioSourceType>, chain: ExternalChain): AudioResolution.Resolved? {
        for (source in PlaybackSourceChain.configuredChain(settings, pinned?.takeUnless { it in rejected }, rejected).distinct()) {
            if (source in rejected || source !in settings.enabled ||
                request.purpose == AudioPurpose.DOWNLOAD && source == AudioSourceType.SPOTIFY) continue
            val provider = providers[source]
            if (provider == null) { chain.attempts += AudioAttempt(source, AudioAttemptResult.UNAVAILABLE); continue }
            chain.inFlight = source
            val outcome = try {
                withTimeoutOrNull(sourceBudgetMs(source)) { ProviderOutcome(provider.resolve(request)) }
            } catch (auth: TerminalPlaybackAuthentication) { throw auth }
            catch (cancel: CancellationException) { throw cancel }
            catch (failure: AudioProviderUnavailable) {
                chain.inFlight = null
                chain.attempts += AudioAttempt(source, AudioAttemptResult.UNAVAILABLE, failure.reason); continue
            }
            catch (failure: ProviderRequestTimeout) {
                chain.inFlight = null
                chain.attempts += AudioAttempt(source, AudioAttemptResult.TIMEOUT); continue
            }
            catch (failure: Exception) {
                chain.inFlight = null
                chain.attempts += AudioAttempt(source, AudioAttemptResult.FAILED); continue
            }
            chain.inFlight = null
            if (outcome == null) { chain.attempts += AudioAttempt(source, AudioAttemptResult.TIMEOUT); continue }
            val audio = outcome.audio
            if (audio == null) { chain.attempts += AudioAttempt(source, AudioAttemptResult.NO_MATCH); continue }
            require(audio.source == source) { "Provider returned another source" }
            if (!audio.matchesRequest(request)) {
                chain.attempts += AudioAttempt(source, AudioAttemptResult.REJECTED_METADATA); continue
            }
            chain.attempts += AudioAttempt(source, AudioAttemptResult.RESOLVED)
            return AudioResolution.Resolved(audio, chain.attempts.toList())
        }
        return null
    }

    /**
     * Per-source budgets carry the values of the Android `resolveAudioSourceAttemptTimeoutMs`: Spotify, Amazon and QQ
     * get 20 s, everything else 8 s. A slow source must not spend the whole chain budget, or the sources after it
     * would never be tried.
     */
    private fun sourceBudgetMs(source: AudioSourceType): Long = when (source) {
        AudioSourceType.SPOTIFY, AudioSourceType.AMAZON, AudioSourceType.QQ -> SLOW_SOURCE_ATTEMPT_MS
        else -> PROVIDER_ATTEMPT_MS
    }

    private companion object {
        const val PROVIDER_ATTEMPT_MS = 8_000L
        const val SLOW_SOURCE_ATTEMPT_MS = 20_000L
        const val FALLBACK_CHAIN_MS = 30_000L
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
    // An Amazon suggestion is a metadata choice, never an authoritative Amazon id. Check the returned metadata
    // against that explicit choice instead of either rejecting it against the original title or trusting it blindly.
    val target = request.amazonPick?.takeIf { source == AudioSourceType.AMAZON }?.let {
        TrackMatching.Target(it.title, listOf(it.artist), it.album, it.durationMs)
    } ?: request.target
    return TitleMatch.evaluate(target.title, target.artists, target.album, target.durationMs, stream).accepted
}
