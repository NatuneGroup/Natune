package moe.rukamori.archivetune.kmp.data

import io.ktor.client.call.body
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import moe.rukamori.archivetune.innertube.InnerTube
import moe.rukamori.archivetune.innertube.YouTube
import moe.rukamori.archivetune.innertube.models.MusicMultiRowListItemRenderer
import moe.rukamori.archivetune.innertube.models.MusicShelfRenderer
import moe.rukamori.archivetune.innertube.models.SongItem
import moe.rukamori.archivetune.innertube.models.YouTubeClient
import moe.rukamori.archivetune.innertube.pages.HistoryPage
import moe.rukamori.archivetune.innertube.pages.PlaylistPage
import moe.rukamori.archivetune.shared.data.provider.HistoryBrowsePage
import moe.rukamori.archivetune.shared.data.provider.HistoryBrowseSection
import moe.rukamori.archivetune.shared.data.provider.YouTubeHistoryRoutingUnavailable
import moe.rukamori.archivetune.shared.data.provider.parseYouTubeHistoryPage
import moe.rukamori.archivetune.shared.domain.repository.ContentAccountChanged

/** Shared by the retained Android history screen and its KMP transport; core and its gitlink stay read-only. */
object AndroidYouTubeHistory {
    private val client by lazy { InnerTube() }
    private val lock = Mutex()
    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    suspend fun page(continuation: String?): HistoryBrowsePage<SongItem> = lock.withLock {
        val auth = YouTube.currentPlaybackAuthState()
        if (YouTube.ipRotationActiveCount.value > 0) {
            // Keep the original first page and its routing. The private client cannot borrow core's internal selector.
            if (continuation != null) throw YouTubeHistoryRoutingUnavailable()
            val page = YouTube.musicHistory().getOrThrow()
            val current = YouTube.currentPlaybackAuthState()
            if (auth.cookie != current.cookie || auth.dataSyncId != current.dataSyncId) throw ContentAccountChanged()
            return@withLock HistoryBrowsePage(
                page.sections.orEmpty().map { HistoryBrowseSection(it.title, it.songs) }, paginationUnavailable = true,
            )
        }
        client.applyAuthState(auth)
        client.locale = YouTube.locale
        if (client.proxy != YouTube.proxy) client.proxy = YouTube.proxy
        if (client.proxyUsername != YouTube.proxyUsername) client.proxyUsername = YouTube.proxyUsername
        if (client.proxyPassword != YouTube.proxyPassword) client.proxyPassword = YouTube.proxyPassword
        if (client.dns != YouTube.dns) client.dns = YouTube.dns
        val response = client.browse(
            client = YouTubeClient.WEB_REMIX,
            browseId = if (continuation == null) "FEmusic_history" else null,
            continuation = continuation,
            setLogin = true,
        ).body<JsonObject>()
        val current = YouTube.currentPlaybackAuthState()
        if (auth.cookie != current.cookie || auth.dataSyncId != current.dataSyncId) throw ContentAccountChanged()
        if (YouTube.ipRotationActiveCount.value > 0) throw YouTubeHistoryRoutingUnavailable()
        parseYouTubeHistoryPage(response) { cell ->
            if (cell["musicResponsiveListItemRenderer"] != null) {
                val shelf = json.decodeFromJsonElement<MusicShelfRenderer>(JsonObject(mapOf("contents" to JsonArray(listOf(cell)))))
                HistoryPage.fromMusicShelfRenderer(shelf).songs.firstOrNull()
            } else {
                cell["musicMultiRowListItemRenderer"]?.let {
                    PlaylistPage.fromMusicMultiRowListItemRenderer(json.decodeFromJsonElement<MusicMultiRowListItemRenderer>(it))
                }
            }
        }
    }
}
