package moe.rukamori.archivetune.shared.data.provider

import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import moe.rukamori.archivetune.shared.domain.repository.*
import moe.rukamori.archivetune.shared.domain.source.*

/** The mirror has search/stream, NOT album/library/offset paging APIs. */
class KtorQobuzBackupRepository(client: HttpClient, base: String = "https://mls.kouzu.in") :
    TypedContentRepository, AudioProviderRepository {
    override val provider = ContentProvider.QOBUZ_BACKUP
    override val source = AudioSourceType.QOBUZ_BACKUP
    private val base = endpoint(base)
    private val http = ProviderHttp(client)
    override suspend fun item(ref: ContentRef): ContentItem = throw UnsupportedContentOperation()
    override suspend fun page(request: ContentRequest, continuation: String?): ContentPage {
        require(request.provider == provider && request.surface == ContentSurface.SEARCH &&
            request.section == null && request.filter in setOf(ContentSearchFilter.ALL, ContentSearchFilter.SONGS))
        require(continuation == null) { "Backup mirror has no continuation protocol" }
        val query = requireNotNull(request.text).also { require(it.isNotBlank()) }
        val tracks = search(query)
        return ContentPage(tracks.mapIndexed { index, track ->
            ContentOccurrence("qobuz_backup:$index:${track.item.ref.id}", track.item)
        })
    }
    private suspend fun search(query: String): List<ProviderTrack> {
        val array = http.json("$base/api/search", mapOf("q" to query, "limit" to "50"),
            mapOf("x-request-source" to "muzo")).jsonArray
        return array.mapNotNull {
            val obj = it as? JsonObject ?: return@mapNotNull null
            val id = obj.text("id")?.trim()?.takeIf { VIDEO_ID.matches(it) } ?: return@mapNotNull null
            val title = obj.text("name")?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val artist = obj.text("artists")
            ProviderTrack(ContentItem(ContentRef(provider.id, ContentKind.SONG, id), title, artist.orEmpty(),
                "https://i.ytimg.com/vi/$id/hqdefault.jpg"), listOfNotNull(artist), null, null, obj.toString())
        }
    }
    override suspend fun resolve(request: AudioResolveRequest): PreparedAudio? {
        val directId = request.directIds[source]
        val matched = if (directId == null) {
            val tracks = search((listOf(request.target.title) + request.target.artists.take(1)).joinToString(" "))
            val best = TrackMatching.best(request.target, tracks.map { it.candidate() }) ?: return null
            tracks.first { it.item.ref.id == best.id }
        } else null
        val id = directId ?: requireNotNull(matched).item.ref.id
        require(VIDEO_ID.matches(id))
        val root = http.json("$base/api/stream", mapOf("id" to id), mapOf("x-request-source" to "muzo")).jsonObject
        val candidates = listOf("lossless", "flac", "url", "canvas_url", "video_url").mapNotNull { root.text(it) }.distinct()
        for (url in candidates) {
            val probe = try { http.probe(url) }
                catch (cancel: CancellationException) { throw cancel }
                catch (failure: Exception) { null }
            if (probe == null) continue
            val mime = probe.mime
            return PreparedAudio.Progressive(DirectStream(streamUrl(url), mime,
                if (mime == "audio/flac") "flac" else if (mime == "audio/mpeg") "mp3" else "mp4a.40.2",
                probe.length, "Qobuz backup", source, matched?.item?.title ?: request.target.title,
                matched?.artists?.joinToString(", ") ?: request.target.artists.joinToString(", "),
                matched?.album ?: request.target.album, matched?.item?.durationMs ?: request.target.durationMs,
                directId != null, probe.sampleRate, probe.bitDepth))
        }
        return null
    }
    private companion object { val VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$") }
}
