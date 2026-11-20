package app.natune.desktop

import io.ktor.client.HttpClient
import app.natune.desktop.playback.DesktopPlaybackPort
import app.natune.desktop.playback.requiresAdmittedRoute
import app.natune.desktop.provider.DesktopDeezerSource
import app.natune.desktop.provider.DesktopPlaybackPool
import app.natune.desktop.provider.DesktopQqMusicSource
import app.natune.desktop.provider.DesktopTidalSource
import app.natune.desktop.provider.desktopJioSaavnSource
import app.natune.shared.data.provider.AudioAttempt
import app.natune.shared.data.provider.AudioResolution
import app.natune.shared.data.provider.KtorQobuzBackupRepository
import app.natune.shared.data.provider.OrderedAudioResolver
import app.natune.shared.data.stream.NativeRouteOutcome
import app.natune.shared.domain.playback.PlaybackLocator
import app.natune.shared.domain.playback.PreparedPlaintextHandle
import app.natune.shared.domain.repository.AudioProviderRepository
import app.natune.shared.domain.repository.AudioResolveRequest
import app.natune.shared.domain.repository.ContentOccurrence
import app.natune.shared.domain.repository.ContentItem
import app.natune.shared.domain.repository.PreparedAudio
import app.natune.shared.domain.repository.QueueLocatorResolver
import app.natune.shared.domain.repository.QueueSourceRecovery
import app.natune.shared.domain.repository.toPlaybackLocator
import app.natune.shared.domain.telegram.TelegramTrackRef
import app.natune.shared.domain.settings.PreferenceSnapshot
import app.natune.shared.domain.source.AudioSourceType
import app.natune.shared.domain.source.PlaybackSourceSnapshot
import app.natune.shared.domain.source.toPlaybackLocator
import app.natune.shared.ui.host.TidalSessionRefresh
import app.natune.shared.domain.settings.SongSourceOverrideKey
import app.natune.shared.domain.source.SongSourceOverride
import app.natune.shared.ui.host.hostSourceSnapshot
import app.natune.shared.ui.host.toAudioResolveRequest
import app.natune.shared.ui.host.unresolvedSourceReason
import app.natune.shared.ui.host.withSourcePins as sharedWithSourcePins
import app.natune.shared.ui.settings.VaultProviderAccountStore

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
    /**
     * The committed download file of a song, when one exists. It answers before the source chain so a
     * downloaded song keeps playing offline, the way Android's DownloadUtil serves a stored file first.
     */
    private val storedFile: suspend (provider: String, mediaId: String) -> java.nio.file.Path? = { _, _ -> null },
    private val cacheAudio: (ContentItem, PreparedAudio) -> Unit = { _, _ -> },
    private val sourceSelected: (ContentOccurrence, java.nio.file.Path?) -> Unit = { _, _ -> },
) : QueueLocatorResolver {
    /** Guards [issued] and [closed] together, so a view prepared during shutdown is released instead of leaked. */
    private val lock = Any()
    private val issued = ArrayDeque<PreparedPlaintextHandle>()
    private var closed = false

    override suspend fun locate(occurrence: ContentOccurrence): PlaybackLocator = locate(occurrence, QueueSourceRecovery())

    override suspend fun recover(occurrence: ContentOccurrence, recovery: QueueSourceRecovery): PlaybackLocator =
        locate(occurrence, recovery)

    private suspend fun locate(occurrence: ContentOccurrence, recovery: QueueSourceRecovery): PlaybackLocator {
        check(synchronized(lock) { !closed }) { "the desktop source resolver is closed" }
        if (occurrence.item.ref.provider == LOCAL_PROVIDER) {
            // A local ref's id is its absolute path. A missing file fails instead of naming another song.
            val file = java.io.File(occurrence.item.ref.id)
            if (file.isFile) {
                sourceSelected(occurrence, file.toPath())
                return PlaybackLocator.LocalFile(file.absolutePath)
            }
            throw IllegalStateException("the local file is missing: ${occurrence.item.ref.id}")
        }
        storedFile(occurrence.item.ref.provider, occurrence.item.ref.id)?.let { path ->
            sourceSelected(occurrence, path)
            return PlaybackLocator.LocalFile(path.toFile().absolutePath)
        }
        sourceSelected(occurrence, null)
        if (occurrence.item.ref.provider == "telegram") {
            val track = TelegramTrackRef.fromMediaId(occurrence.item.ref.id)
                ?: throw IllegalStateException("invalid Telegram media id: ${occurrence.item.ref.id}")
            return PlaybackLocator.TelegramFile(track)
        }
        val request = occurrence.toResolveRequest(preferences()).withSourcePins(preferences(), occurrence.item.ref.id)
            .copy(forceFreshStream = recovery.refreshSource != null)
        // A "play from" pin forces the attempt chain for this one song, as the Android service's per-song
        // source override does; a refused pinned source falls through to the ordinary chain.
        val pinned = SongSourceOverride.get(preferences()?.get(SongSourceOverrideKey), occurrence.item.ref.id)
            ?: AudioSourceType.MODULES.takeIf { occurrence.item.ref.provider == "modules" && occurrence.item.ref.id.startsWith("module:") }
        var refused = recovery.rejectedSources
        while (true) {
            val resolution = audio.resolve(request, recovery.settings(desktopSourceSnapshot(preferences())),
                pinned = recovery.pinned(pinned), rejected = refused)
            val prepared = when (resolution) {
                is AudioResolution.Resolved -> resolution.audio
                is AudioResolution.Unavailable ->
                    throw IllegalStateException(unavailableReason(occurrence, resolution.attempts))
                is AudioResolution.NativeRoute -> error("the desktop resolver never requests a native route")
            }
            // Anonymous progressive audio keeps its ordinary locator. A personal stream reads its cookies only
            // through the admitted route, so the engine never receives it as a bare remote URL.
            if (prepared is PreparedAudio.Progressive && !prepared.requiresAdmittedRoute()) {
                cacheAudio(occurrence.item, prepared)
                return prepared.stream.toPlaybackLocator()
            }
            when (prepared) {
                is PreparedAudio.DeezerEncrypted -> {
                    cacheAudio(occurrence.item, prepared)
                    return prepared.toPlaybackLocator()
                }
                is PreparedAudio.Progressive, is PreparedAudio.Native, is PreparedAudio.TidalManifest ->
                    when (val outcome = port.prepareNative(prepared)) {
                        is NativeRouteOutcome.Ready -> {
                            cacheAudio(occurrence.item, prepared)
                            return remember(outcome.handle, prepared.source)
                        }
                        is NativeRouteOutcome.Refused -> refused = refused + prepared.source
                    }
            }
        }
    }

    /**
     * Keeps a view until its lease claims it or it expires. Expired views and the oldest views beyond the cap are
     * released here, so a dropped occurrence never holds its transport open for the rest of the session.
     */
    private fun remember(handle: PreparedPlaintextHandle, source: AudioSourceType): PlaybackLocator {
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
        return PlaybackLocator.PreparedPlaintext(handle, source)
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

/** The source snapshot one resolution reads, as every host reads it. */
internal fun desktopSourceSnapshot(preferences: PreferenceSnapshot?): PlaybackSourceSnapshot =
    hostSourceSnapshot(preferences, spotifyStreamingReady = desktopSpotifyStreamingReady(preferences))
        .let { snapshot -> if (preferences?.get(app.natune.shared.domain.settings.ModulesEnabledKey) == true)
            snapshot.copy(enabled = snapshot.enabled + AudioSourceType.MODULES) else snapshot }

/**
 * The audio providers this host connects, each over the one HTTP client of the graph, in no particular order: the
 * stored source order decides which one answers first. TIDAL, Deezer and QQ read the personal account leases of
 * [accounts] on every resolution, so a sign-out or a replaced account stops them at once. TIDAL and Deezer also read the
 * Playback Pool accounts of [pool] while its switch is on. JioSaavn is composed only when
 * its retained decoder is available. [DesktopAudioSources] adds Qobuz, Apple, Amazon and the separate Spotify streaming
 * account over these same shared clients. YouTube is the terminal tier and is passed to the resolver separately.
 */
internal fun desktopAudioProviders(
    http: HttpClient,
    accounts: VaultProviderAccountStore,
    preferences: () -> PreferenceSnapshot?,
    tidalSessionRefresh: TidalSessionRefresh? = null,
    pool: DesktopPlaybackPool? = null,
): List<AudioProviderRepository> = listOfNotNull(
    DesktopTidalSource(http, accounts, preferences, tidalSessionRefresh, pool),
    KtorQobuzBackupRepository(http),
    DesktopDeezerSource(http, accounts, pool, preferences),
    desktopJioSaavnSource(http, preferences),
    DesktopQqMusicSource(http, accounts),
)

private fun ContentOccurrence.toResolveRequest(preferences: PreferenceSnapshot?): AudioResolveRequest =
    toAudioResolveRequest(preferences)

/** The per-source catalogue ids the menu's source chooser stored; the shared host owns the rule on every platform. */
internal fun AudioResolveRequest.withSourcePins(preferences: PreferenceSnapshot?, songId: String): AudioResolveRequest =
    sharedWithSourcePins(preferences, songId)

private fun unavailableReason(occurrence: ContentOccurrence, attempts: List<AudioAttempt>): String =
    unresolvedSourceReason(occurrence, attempts, host = "desktop")
