package moe.rukamori.archivetune.shared.data.provider

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import moe.rukamori.archivetune.shared.domain.repository.ContentCredit
import moe.rukamori.archivetune.shared.domain.repository.ContentKind

/** History rows the native InnerTube response leaves as JSON, including episode rows inside dated shelves. */
internal fun historyEntry(cell: JsonObject): YouTubeEntry? {
    val row = cell.obj("musicResponsiveListItemRenderer") ?: cell.obj("musicMultiRowListItemRenderer") ?: return null
    val columns = (row["flexColumns"] as? JsonArray).orEmpty().mapNotNull {
        (it as? JsonObject)?.obj("musicResponsiveListItemFlexColumnRenderer")?.obj("text")
    }
    val title = row.obj("title") ?: columns.firstOrNull() ?: return null
    val titleText = title.text().takeIf(String::isNotBlank) ?: return null
    val runs = columns.flatMap { it.runs() } + row.obj("subtitle")?.runs().orEmpty() + title.runs()
    val endpoints = listOfNotNull(
        row.obj("navigationEndpoint"),
        row.obj("onTap"),
        row.obj("onTap")?.obj("innertubeCommand"),
        row.obj("overlay")?.obj("musicItemThumbnailOverlayRenderer")?.obj("content")
            ?.obj("musicPlayButtonRenderer")?.obj("playNavigationEndpoint"),
    ).flatMap { listOfNotNull(it, it.obj("innertubeCommand")) } +
        runs.mapNotNull { it.obj("navigationEndpoint") }
    val watch = endpoints.firstNotNullOfOrNull { it.obj("watchEndpoint") ?: it.obj("watchPlaylistEndpoint") }
    val videoId = row.obj("playlistItemData")?.string("videoId") ?: watch?.string("videoId") ?: return null
    val episode = endpoints.any {
        val watchType = (it.obj("watchEndpoint") ?: it.obj("watchPlaylistEndpoint"))
            ?.obj("watchEndpointMusicSupportedConfigs")?.obj("watchEndpointMusicConfig")?.string("musicVideoType")
        watchType == "MUSIC_VIDEO_TYPE_PODCAST_EPISODE" ||
            it.obj("browseEndpoint")?.string("browseId")?.startsWith("MPED") == true
    } || cell["musicMultiRowListItemRenderer"] != null
    val credits = runs.mapNotNull { run ->
        val id = run.obj("navigationEndpoint")?.obj("browseEndpoint")?.string("browseId") ?: return@mapNotNull null
        if (!id.startsWith("UC") && !id.startsWith("FEMC") && !(episode && id.startsWith("MPSP"))) return@mapNotNull null
        run.string("text")?.takeIf(String::isNotBlank)?.let { ContentCredit(it, id) }
    }.distinct()
    val album = runs.firstOrNull {
        it.obj("navigationEndpoint")?.obj("browseEndpoint")?.string("browseId")?.startsWith("MPRE") == true
    }
    val fixed = (row["fixedColumns"] as? JsonArray).orEmpty().mapNotNull {
        val column = it as? JsonObject
        (column?.obj("musicResponsiveListItemFixedColumnRenderer") ?: column?.obj("musicResponsiveListItemFlexColumnRenderer"))
            ?.obj("text")?.text()
    }
    val playbackDuration = row.obj("playbackProgress")?.obj("musicPlaybackProgressRenderer")?.obj("durationText")?.text()
    val metadata = columns.drop(1).flatMap { it.runs() } + row.obj("subtitle")?.runs().orEmpty()
    val duration = (listOfNotNull(playbackDuration) + fixed + metadata.mapNotNull { it.string("text") })
        .firstNotNullOfOrNull(::historyDuration)
    val thumbnail = row.obj("thumbnail")?.obj("musicThumbnailRenderer")?.obj("thumbnail")
        ?: row.obj("thumbnail")?.obj("musicThumbnailRenderer")
    val artwork = (thumbnail?.get("thumbnails") as? JsonArray)?.lastOrNull() as? JsonObject
    return YouTubeEntry(
        kind = if (episode) ContentKind.EPISODE else ContentKind.SONG,
        id = videoId,
        title = titleText,
        subtitle = credits.joinToString(", ") { it.name }.ifBlank { columns.getOrNull(1)?.text().orEmpty() },
        artworkUri = artwork?.string("url"),
        durationSeconds = duration,
        explicit = (row["badges"] as? JsonArray).orEmpty().any {
            (it as? JsonObject)?.obj("musicInlineBadgeRenderer")?.obj("icon")?.string("iconType") == "MUSIC_EXPLICIT_BADGE"
        },
        setVideoId = row.obj("playlistItemData")?.string("playlistSetVideoId") ?: watch?.string("playlistSetVideoId"),
        artists = credits,
        album = album?.string("text"),
        albumId = album?.obj("navigationEndpoint")?.obj("browseEndpoint")?.string("browseId"),
    )
}

private fun historyDuration(text: String): Int? {
    val parts = text.trim().split(':')
    if (parts.size !in 2..3 || parts.any { it.isEmpty() || it.any { ch -> !ch.isDigit() } }) return null
    var seconds = 0L
    for ((index, part) in parts.withIndex()) {
        val value = part.toLongOrNull() ?: return null
        if (index > 0 && value >= 60) return null
        if (seconds > (Int.MAX_VALUE - value) / 60) return null
        seconds = seconds * 60 + value
    }
    return seconds.toInt()
}

private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.runs(): List<JsonObject> = (this["runs"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
private fun JsonObject.text(): String = string("simpleText") ?: runs().joinToString("") { it.string("text").orEmpty() }
