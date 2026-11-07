package moe.rukamori.archivetune.shared.data.provider

import io.ktor.client.call.body
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import moe.rukamori.archivetune.innertube.InnerTube
import moe.rukamori.archivetune.innertube.YouTube
import moe.rukamori.archivetune.innertube.models.YouTubeClient

/**
 * The raw `browse` of a podcast show page. Core's typed podcast page keeps neither the episode shelf's cursor nor a
 * row that omits its thumbnail, so the desktop reads the same response it sends and reduces it with the shared
 * parser. The client is the retained InnerTube one, pointed at the persisted sign-in before every read, as the
 * account listings do; it never routes through a proxy.
 */
internal object CoreYouTubePodcastBrowse {
    private val client by lazy { InnerTube() }
    private val lock = Mutex()

    suspend fun read(browseId: String?, continuation: String?): JsonObject =
        lock.withLock {
            client.applyAuthState(YouTube.currentPlaybackAuthState())
            client.locale = YouTube.locale
            client.browse(
                client = YouTubeClient.WEB_REMIX,
                browseId = browseId,
                continuation = continuation,
                setLogin = true,
            ).body()
        }
}
