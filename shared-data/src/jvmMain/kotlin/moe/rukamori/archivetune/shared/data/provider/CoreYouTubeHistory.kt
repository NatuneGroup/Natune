package moe.rukamori.archivetune.shared.data.provider

import io.ktor.client.call.body
import java.net.ProxySelector
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import moe.rukamori.archivetune.innertube.InnerTube
import moe.rukamori.archivetune.innertube.YouTube
import moe.rukamori.archivetune.innertube.models.MusicMultiRowListItemRenderer
import moe.rukamori.archivetune.innertube.models.MusicShelfRenderer
import moe.rukamori.archivetune.innertube.models.YouTubeClient
import moe.rukamori.archivetune.innertube.pages.HistoryPage
import moe.rukamori.archivetune.innertube.pages.PlaylistPage
import moe.rukamori.archivetune.shared.domain.repository.ContentAccountChanged

/**
 * Core's high-level history page omits its cursor. Its public lower-level browse accepts one, without changing
 * core or staging credentials in the global player. Each transport owns its paging pin; the private client and
 * mutex are shared, and every read applies that transport's route before sending.
 */
internal class CoreYouTubeHistory {
    private val session = HistoryProxySession()

    private companion object {
        val client by lazy { InnerTube() }
        val lock = Mutex()
        @OptIn(ExperimentalSerializationApi::class)
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

        fun clearProxy() {
            if (client.proxyUsername != null) client.proxyUsername = null
            if (client.proxyPassword != null) client.proxyPassword = null
            if (client.proxy != null) client.proxy = null
        }
    }

    suspend fun page(continuation: String?): YouTubeHistoryPage = lock.withLock {
        val auth = YouTube.currentPlaybackAuthState()
        val route = try {
            session.routeFor(continuation, auth.cookie, auth.dataSyncId) {
                selectHistoryProxyRoute(
                    ProxySelector.getDefault(), YouTube.proxy, YouTube.proxyUsername, YouTube.proxyPassword,
                )
            }
        } catch (changed: ContentAccountChanged) {
            clearProxy()
            throw changed
        }
        client.applyAuthState(auth)
        client.locale = YouTube.locale
        if (client.proxy != route.proxy) client.proxy = route.proxy
        if (client.proxyUsername != route.username) client.proxyUsername = route.username
        if (client.proxyPassword != route.password) client.proxyPassword = route.password
        if (client.dns != YouTube.dns) client.dns = YouTube.dns
        val response = client.browse(
            client = YouTubeClient.WEB_REMIX,
            browseId = if (continuation == null) "FEmusic_history" else null,
            continuation = continuation,
            setLogin = true,
        ).body<JsonObject>()
        val current = YouTube.currentPlaybackAuthState()
        if (auth.cookie != current.cookie || auth.dataSyncId != current.dataSyncId) {
            session.reset()
            clearProxy()
            throw ContentAccountChanged()
        }
        val page = parseYouTubeHistoryPage(response) { cell ->
            val song = if (cell["musicResponsiveListItemRenderer"] != null) {
                val shelf = json.decodeFromJsonElement<MusicShelfRenderer>(
                    JsonObject(mapOf("contents" to kotlinx.serialization.json.JsonArray(listOf(cell)))),
                )
                HistoryPage.fromMusicShelfRenderer(shelf).songs.firstOrNull()
            } else {
                cell["musicMultiRowListItemRenderer"]?.let {
                    PlaylistPage.fromMusicMultiRowListItemRenderer(json.decodeFromJsonElement<MusicMultiRowListItemRenderer>(it))
                }
            }
            song?.toSongCatalogueEntry()
        }
        session.acceptNext(page.continuation)
        page
    }
}
