package moe.rukamori.archivetune.desktop

import io.ktor.client.HttpClient
import moe.rukamori.archivetune.desktop.provider.DesktopAmazonSource
import moe.rukamori.archivetune.desktop.provider.DesktopAppleMusicSource
import moe.rukamori.archivetune.desktop.provider.DesktopPlaybackPool
import moe.rukamori.archivetune.desktop.provider.DesktopQobuzSource
import moe.rukamori.archivetune.desktop.provider.DesktopSourceAdmissions
import moe.rukamori.archivetune.desktop.provider.DesktopSpotifyEchoClient
import moe.rukamori.archivetune.desktop.provider.DesktopSpotifyEchoConfiguration
import moe.rukamori.archivetune.desktop.provider.DesktopTidalSource
import moe.rukamori.archivetune.shared.data.provider.KtorAmazonSourceSuggestions
import moe.rukamori.archivetune.shared.data.provider.KtorDeezerRepository
import moe.rukamori.archivetune.shared.domain.repository.AudioProviderRepository
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.domain.repository.ContentRequest
import moe.rukamori.archivetune.shared.domain.repository.ContentSearchFilter
import moe.rukamori.archivetune.shared.domain.repository.ContentSurface
import moe.rukamori.archivetune.shared.domain.repository.TypedContentRepository
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.domain.source.AudioSourceType
import moe.rukamori.archivetune.shared.ui.host.TidalSessionRefresh
import moe.rukamori.archivetune.shared.ui.menu.playerqueue.PlayerSourceSearchResult
import moe.rukamori.archivetune.shared.ui.settings.VaultProviderAccountStore

/**
 * One session-lifetime owner for playback and source search. The playback port must read [admissions], not only the
 * persisted account slots, because verified Qobuz lists and the separate streaming profile have runtime-only leases.
 */
internal class DesktopAudioSources(
    http: HttpClient,
    accounts: VaultProviderAccountStore,
    private val preferences: () -> PreferenceSnapshot?,
    val admissions: DesktopSourceAdmissions,
    tidalSessionRefresh: TidalSessionRefresh? = null,
    pool: DesktopPlaybackPool? = null,
    cacheDirectory: java.nio.file.Path = java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "natune-librespot"),
    modules: DesktopModules? = null,
) {
    private val librespot = DesktopSpotifyLibrespotAudio(http, preferences, admissions, cacheDirectory)
    fun bindLibrespotAccessToken(provider: suspend () -> String?) { librespot.accessToken = provider }
    fun revokeLibrespot() { moe.rukamori.archivetune.spotify.librespot.SpotifyLibrespotNative.close() }
    private val qobuz = DesktopQobuzSource(http, preferences, admissions, pool)
    private val spotify = DesktopSpotifySource(http, preferences, admissions,
        DesktopSpotifyEchoConfiguration.supplied()?.let { DesktopSpotifyEchoClient(http, it) }, librespot)
    private val retained = desktopAudioProviders(http, accounts, preferences, tidalSessionRefresh, pool)
    val providers: List<AudioProviderRepository> = retained +
        listOf(qobuz, DesktopAppleMusicSource(http, preferences), DesktopAmazonSource(http, preferences), spotify) + listOfNotNull(modules)
    private val deezerCatalogue = KtorDeezerRepository(http, System::currentTimeMillis)
    private val amazonSuggestions = KtorAmazonSourceSuggestions(http)

    private val searcher = DesktopSourceSearch(buildMap {
        put(AudioSourceType.TIDAL, retained.filterIsInstance<DesktopTidalSource>().single()::search)
        put(AudioSourceType.QOBUZ, qobuz::search)
        put(AudioSourceType.AMAZON, { query -> amazonSuggestions.search(query) })
        put(AudioSourceType.DEEZER, { query -> searchPage(deezerCatalogue, query) })
        retained.filterIsInstance<TypedContentRepository>().forEach { repository ->
            val source = when (repository.provider) {
                ContentProvider.QOBUZ_BACKUP -> AudioSourceType.QOBUZ_BACKUP
                ContentProvider.JIOSAAVN -> AudioSourceType.JIOSAAVN
                else -> return@forEach
            }
            put(source, { query -> searchPage(repository, query) })
        }
    })

    val searchableSources: Set<AudioSourceType> get() = searcher.searchableSources
    val amazonSearchEnabled: Boolean get() = AudioSourceType.AMAZON in desktopSourceSnapshot(preferences()).enabled

    suspend fun search(
        youtube: TypedContentRepository,
        query: String,
        sourceFilter: AudioSourceType?,
        includeAmazon: Boolean,
    ): List<PlayerSourceSearchResult> = searcher.search(query, sourceFilter, includeAmazon && amazonSearchEnabled) {
        searchPage(youtube, it)
    }

    suspend fun close() {
        admissions.close()
        spotify.close()
        qobuz.close()
    }

    private suspend fun searchPage(repository: TypedContentRepository, query: String) = repository.page(
        ContentRequest(repository.provider, ContentSurface.SEARCH, text = query, filter = ContentSearchFilter.SONGS),
        continuation = null,
    ).items.map { it.item }
}

internal fun desktopAudioSources(
    http: HttpClient,
    accounts: VaultProviderAccountStore,
    preferences: () -> PreferenceSnapshot?,
    admissions: DesktopSourceAdmissions,
    tidalSessionRefresh: TidalSessionRefresh? = null,
    pool: DesktopPlaybackPool? = null,
    cacheDirectory: java.nio.file.Path = java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "natune-librespot"),
    modules: DesktopModules? = null,
): DesktopAudioSources = DesktopAudioSources(http, accounts, preferences, admissions, tidalSessionRefresh, pool, cacheDirectory, modules)
