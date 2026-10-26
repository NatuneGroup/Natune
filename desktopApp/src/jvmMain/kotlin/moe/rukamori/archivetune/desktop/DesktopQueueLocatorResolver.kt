package moe.rukamori.archivetune.desktop

import io.ktor.client.HttpClient
import moe.rukamori.archivetune.desktop.playback.DesktopPlaybackPort
import moe.rukamori.archivetune.desktop.playback.requiresAdmittedRoute
import moe.rukamori.archivetune.desktop.provider.DesktopDeezerSource
import moe.rukamori.archivetune.desktop.provider.DesktopPlaybackPool
import moe.rukamori.archivetune.desktop.provider.DesktopQqMusicSource
import moe.rukamori.archivetune.desktop.provider.DesktopTidalSessionRefresh
import moe.rukamori.archivetune.desktop.provider.DesktopTidalSource
import moe.rukamori.archivetune.desktop.provider.desktopJioSaavnSource
import moe.rukamori.archivetune.shared.data.provider.AudioAttempt
import moe.rukamori.archivetune.shared.data.provider.AudioResolution
import moe.rukamori.archivetune.shared.data.provider.KtorQobuzBackupRepository
import moe.rukamori.archivetune.shared.data.provider.OrderedAudioResolver
import moe.rukamori.archivetune.shared.data.stream.NativeRouteOutcome
import moe.rukamori.archivetune.shared.domain.playback.PlaybackLocator
import moe.rukamori.archivetune.shared.domain.playback.PreparedPlaintextHandle
import moe.rukamori.archivetune.shared.domain.repository.AudioProviderRepository
import moe.rukamori.archivetune.shared.domain.repository.AudioResolveRequest
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.PreparedAudio
import moe.rukamori.archivetune.shared.domain.repository.QueueLocatorResolver
import moe.rukamori.archivetune.shared.domain.repository.toPlaybackLocator
import moe.rukamori.archivetune.shared.domain.settings.AudioSourceOrderKey
import moe.rukamori.archivetune.shared.domain.settings.DeezerAudioQualityKey
import moe.rukamori.archivetune.shared.domain.settings.QqMusicAudioQualityKey
import moe.rukamori.archivetune.shared.domain.settings.SaavnAudioQualityKey
import moe.rukamori.archivetune.shared.domain.settings.TidalAudioQualityKey
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.domain.settings.audioSourceEnabled
import moe.rukamori.archivetune.shared.domain.source.AudioSourceConfig
import moe.rukamori.archivetune.shared.domain.source.AudioSourceType
import moe.rukamori.archivetune.shared.domain.source.PlaybackSourceSnapshot
import moe.rukamori.archivetune.shared.domain.source.TrackMatching
import moe.rukamori.archivetune.shared.ui.settings.VaultProviderAccountStore

/** The local-file provider id. A local song has no provider to search, so it is refused until a local owner exists. */
private const val LOCAL_PROVIDER = "local"

/** The most prepared views kept for release. A view that a lease has claimed is left alone by [DesktopPlaybackPort]. */
private const val MAX_UNCLAIMED_VIEWS = 32

/**
 * Resolves one queue occurrence to the locator the engine may open. The ordered source chain decides which provider
 * answers. An anonymous progressive or deezer-encrypted answer maps to its ordinary locator. A personal progressive,
 * native or TIDAL answer becomes a prepared plaintext view through the port's admitted route, which reads the account
 * cookies. A refused answer sends the occurrence down the chain, so neither ciphertext nor a guessed URL reaches the
 * engine. When no source answers, the load fails and names the attempts it made rather than substituting a silent
 * success.
 */
internal class DesktopQueueLocatorResolver(
    private val audio: OrderedAudioResolver,
    private val port: DesktopPlaybackPort,
    private val preferences: () -> PreferenceSnapshot?,
) : QueueLocatorResolver {
    /** Guards [issued] and [closed] together, so a view prepared during shutdown is released instead of leaked. */
    private val lock = Any()
    private val issued = ArrayDeque<PreparedPlaintextHandle>()
    private var closed = false

    override suspend fun locate(occurrence: ContentOccurrence): PlaybackLocator {
        check(synchronized(lock) { !closed }) { "the desktop source resolver is closed" }
        if (occurrence.item.ref.provider == LOCAL_PROVIDER) {
            throw IllegalStateException("local files are not connected on this desktop host")
        }
        val request = occurrence.toResolveRequest(preferences())
        var refused = emptySet<AudioSourceType>()
        while (true) {
            val resolution = audio.resolve(request, desktopSourceSnapshot(preferences()), rejected = refused)
            val prepared = when (resolution) {
                is AudioResolution.Resolved -> resolution.audio
                is AudioResolution.Unavailable ->
                    throw IllegalStateException(unavailableReason(occurrence, resolution.attempts))
                is AudioResolution.NativeRoute -> error("the desktop resolver never requests a native route")
            }
            // Anonymous progressive audio keeps its ordinary locator. A personal stream reads its cookies only
            // through the admitted route, so the engine never receives it as a bare remote URL.
            if (prepared is PreparedAudio.Progressive && !prepared.requiresAdmittedRoute()) {
                return PlaybackLocator.Remote(prepared.stream.uri, prepared.stream.headers)
            }
            when (prepared) {
                is PreparedAudio.DeezerEncrypted -> return prepared.toPlaybackLocator()
                is PreparedAudio.Progressive, is PreparedAudio.Native, is PreparedAudio.TidalManifest ->
                    when (val outcome = port.prepareNative(prepared)) {
                        is NativeRouteOutcome.Ready -> return remember(outcome.handle)
                        is NativeRouteOutcome.Refused -> refused = refused + prepared.source
                    }
            }
        }
    }

    /**
     * Keeps a view until its lease claims it or it expires. Expired views and the oldest views beyond the cap are
     * released here, so a dropped occurrence never holds its transport open for the rest of the session.
     */
    private fun remember(handle: PreparedPlaintextHandle): PlaybackLocator {
        val released = mutableListOf<PreparedPlaintextHandle>()
        val accepted = synchronized(lock) {
            if (closed) return@synchronized false
            val now = System.currentTimeMillis()
            val iterator = issued.iterator()
            while (iterator.hasNext()) {
                val view = iterator.next()
                val expiresAt = view.expiresAtEpochMs
                if (expiresAt != null && expiresAt <= now) {
                    iterator.remove()
                    released += view
                }
            }
            issued.addLast(handle)
            while (issued.size > MAX_UNCLAIMED_VIEWS) released += issued.removeFirst()
            true
        }
        released.forEach { port.releasePrepared(it) }
        if (!accepted) {
            port.releasePrepared(handle)
            error("the desktop source resolver is closed")
        }
        return PlaybackLocator.PreparedPlaintext(handle)
    }

    /**
     * Shutdown. Every view no lease has claimed is released now. A claimed view belongs to its engine lease, and the
     * port ignores a release for it, so this never closes a transport that is playing.
     */
    fun close() {
        val views = synchronized(lock) {
            closed = true
            issued.toList().also { issued.clear() }
        }
        views.forEach { port.releasePrepared(it) }
    }
}

/**
 * The source snapshot one resolution reads: the stored order and the stored toggles. Spotify streaming stays off until
 * its account owner reports ready. Without stored settings the built-in defaults apply, with the same Spotify gate.
 */
internal fun desktopSourceSnapshot(preferences: PreferenceSnapshot?): PlaybackSourceSnapshot {
    val order = AudioSourceConfig.parseOrder(preferences?.get(AudioSourceOrderKey))
    val enabled = AudioSourceType.entries.filterTo(linkedSetOf<AudioSourceType>()) { source ->
        when (preferences) {
            null -> AudioSourceConfig.DEFAULT_ENABLED.getValue(source) && source != AudioSourceType.SPOTIFY
            else -> preferences.audioSourceEnabled(source, spotifyStreamingReady = false)
        }
    }
    return PlaybackSourceSnapshot(order = order, enabled = enabled)
}

/**
 * The audio providers this host connects, each over the one HTTP client of the graph, in no particular order: the
 * stored source order decides which one answers first. TIDAL, Deezer and QQ read the personal account leases of
 * [accounts] on every resolution, so a sign-out or a replaced account stops them at once. TIDAL and Deezer also read the
 * Playback Pool accounts of [pool] while its switch is on. JioSaavn is composed only when
 * its decoder key is supplied. Qobuz, Apple and Amazon stay out of this list until their account, digest or instance
 * owners publish them on this host; a missing source answers as an unavailable attempt, never as a guessed URL. YouTube
 * is the terminal tier and is passed to the resolver separately.
 */
internal fun desktopAudioProviders(
    http: HttpClient,
    accounts: VaultProviderAccountStore,
    preferences: () -> PreferenceSnapshot?,
    tidalSessionRefresh: DesktopTidalSessionRefresh? = null,
    pool: DesktopPlaybackPool? = null,
): List<AudioProviderRepository> = listOfNotNull(
    DesktopTidalSource(http, accounts, preferences, tidalSessionRefresh, pool),
    KtorQobuzBackupRepository(http),
    DesktopDeezerSource(http, accounts, pool),
    desktopJioSaavnSource(http),
    DesktopQqMusicSource(http, accounts),
)

private fun ContentOccurrence.toResolveRequest(preferences: PreferenceSnapshot?): AudioResolveRequest = AudioResolveRequest(
    mediaId = item.ref.id,
    target = TrackMatching.Target(
        title = item.title,
        artists = item.artists.map { it.name },
        album = item.album,
        durationMs = item.durationMs,
    ),
    directIds = item.ref.directSourceId()?.let { mapOf(it) }.orEmpty(),
    qualities = desktopSourceQualities(preferences),
)

/** A catalog id is authoritative only for the source that issued it. */
private fun moe.rukamori.archivetune.shared.domain.repository.ContentRef.directSourceId(): Pair<AudioSourceType, String>? =
    when (provider) {
        "youtube" -> AudioSourceType.YOUTUBE to id
        "tidal" -> AudioSourceType.TIDAL to id
        "deezer" -> AudioSourceType.DEEZER to id
        "jiosaavn" -> AudioSourceType.JIOSAAVN to id
        "qq" -> AudioSourceType.QQ to id
        else -> null
    }

/**
 * The stored per-source qualities in the vocabulary each repository accepts. A stored value that a repository does not
 * know is left out, so that repository keeps its own default instead of failing the request.
 */
internal fun desktopSourceQualities(preferences: PreferenceSnapshot?): Map<AudioSourceType, String> = buildMap {
    when (preferences?.get(TidalAudioQualityKey) ?: "FLAC") {
        "AAC_320" -> put(AudioSourceType.TIDAL, "HIGH")
        "FLAC" -> put(AudioSourceType.TIDAL, "LOSSLESS")
        "HI_RES_LOSSLESS" -> put(AudioSourceType.TIDAL, "HI_RES_LOSSLESS")
    }
    preferences?.get(DeezerAudioQualityKey)?.takeIf { it in setOf("FLAC", "MP3_320", "MP3_128") }
        ?.let { put(AudioSourceType.DEEZER, it) }
    when (preferences?.get(SaavnAudioQualityKey) ?: "QUALITY_320") {
        "QUALITY_320" -> put(AudioSourceType.JIOSAAVN, "320")
        "QUALITY_160" -> put(AudioSourceType.JIOSAAVN, "160")
        "QUALITY_96" -> put(AudioSourceType.JIOSAAVN, "96")
    }
    preferences?.get(QqMusicAudioQualityKey)?.takeIf { it in setOf("LOSSLESS", "HIGH", "STANDARD") }
        ?.let { put(AudioSourceType.QQ, it) }
}

private fun unavailableReason(occurrence: ContentOccurrence, attempts: List<AudioAttempt>): String {
    val tried = attempts.joinToString(separator = ", ") { "${it.source.name} ${it.result.name}" }
    return "no connected source resolved ${occurrence.item.ref.provider} on this desktop host" +
        if (tried.isEmpty()) "" else " ($tried)"
}
