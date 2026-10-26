package moe.rukamori.archivetune.shared.data.lastfm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import moe.rukamori.archivetune.shared.domain.lastfm.LastFmApiException
import moe.rukamori.archivetune.shared.domain.lastfm.LastFmImage
import moe.rukamori.archivetune.shared.domain.lastfm.LastFmPage
import moe.rukamori.archivetune.shared.domain.lastfm.LastFmRecentTrack
import moe.rukamori.archivetune.shared.domain.lastfm.LastFmTopAlbum
import moe.rukamori.archivetune.shared.domain.lastfm.LastFmTopArtist
import moe.rukamori.archivetune.shared.domain.lastfm.LastFmTopTrack
import moe.rukamori.archivetune.shared.domain.lastfm.LastFmUserProfile

/**
 * Reads the dashboard and track-tag replies of Last.fm into domain models.
 *
 * Last.fm sends counts and timestamps as strings, returns one list entry as an object instead of an array, and leaves
 * out empty parts. So each field is read leniently, empty values become null or an empty list, and only the envelope
 * that carries the data is required. A reply without that envelope is an unreadable response, which the scrobble rule
 * treats as retryable.
 */
internal object LastFmDashboardResponses {
    fun userProfile(text: String): LastFmUserProfile {
        val user = root(text).obj("user") ?: throw unreadable()
        val registered = user.obj("registered")
        return LastFmUserProfile(
            name = user.text("name"),
            realName = user.text("realname"),
            profileUrl = user.text("url"),
            images = user.images(),
            country = user.text("country"),
            age = user.int("age"),
            gender = user.text("gender"),
            subscriber = user.int("subscriber"),
            playCount = user.long("playcount"),
            playlistCount = user.int("playlists"),
            registeredEpochSeconds = registered?.long("unixtime") ?: registered?.long("#text"),
        )
    }

    fun recentTracks(
        text: String,
        requestedPage: Int,
        requestedLimit: Int,
    ): LastFmPage<LastFmRecentTrack> {
        val container = root(text).obj("recenttracks") ?: throw unreadable()
        val items =
            container.objects("track").map { track ->
                LastFmRecentTrack(
                    name = track.text("name"),
                    artist = track.obj("artist")?.displayName(),
                    album = track.obj("album")?.displayName(),
                    url = track.text("url"),
                    images = track.images(),
                    playedAtEpochSeconds = track.obj("date")?.long("uts"),
                    isNowPlaying = track.obj("@attr")?.text("nowplaying") == "true",
                )
            }
        return container.page(items, requestedPage, requestedLimit)
    }

    fun topTracks(
        text: String,
        requestedPage: Int,
        requestedLimit: Int,
    ): LastFmPage<LastFmTopTrack> {
        val container = root(text).obj("toptracks") ?: throw unreadable()
        val items =
            container.objects("track").map { track ->
                LastFmTopTrack(
                    rank = track.obj("@attr")?.int("rank"),
                    name = track.text("name"),
                    artist = track.obj("artist")?.displayName(),
                    url = track.text("url"),
                    playCount = track.int("playcount"),
                    images = track.images(),
                )
            }
        return container.page(items, requestedPage, requestedLimit)
    }

    fun topArtists(
        text: String,
        requestedPage: Int,
        requestedLimit: Int,
    ): LastFmPage<LastFmTopArtist> {
        val container = root(text).obj("topartists") ?: throw unreadable()
        val items =
            container.objects("artist").map { artist ->
                LastFmTopArtist(
                    rank = artist.obj("@attr")?.int("rank"),
                    name = artist.text("name"),
                    url = artist.text("url"),
                    playCount = artist.int("playcount"),
                    images = artist.images(),
                )
            }
        return container.page(items, requestedPage, requestedLimit)
    }

    fun topAlbums(
        text: String,
        requestedPage: Int,
        requestedLimit: Int,
    ): LastFmPage<LastFmTopAlbum> {
        val container = root(text).obj("topalbums") ?: throw unreadable()
        val items =
            container.objects("album").map { album ->
                LastFmTopAlbum(
                    rank = album.obj("@attr")?.int("rank"),
                    name = album.text("name"),
                    artist = album.obj("artist")?.displayName(),
                    url = album.text("url"),
                    playCount = album.int("playcount"),
                    images = album.images(),
                )
            }
        return container.page(items, requestedPage, requestedLimit)
    }

    /** The community tag names of one track. A track whose reply has no tag list has no tags. */
    fun trackTags(text: String): List<String> {
        val track = root(text).obj("track") ?: throw unreadable()
        val tags = track.obj("toptags") ?: return emptyList()
        return tags.objects("tag").mapNotNull { it.text("name") }
    }
}

private fun root(text: String): JsonObject =
    (runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject) ?: throw unreadable()

private fun unreadable(): LastFmApiException =
    LastFmApiException(code = 0, message = "Last.fm returned an unreadable response", isApiError = false)

/** Reads the paging attributes of a listing. A missing attribute falls back to the page that was asked for. */
private fun <T> JsonObject.page(
    items: List<T>,
    requestedPage: Int,
    requestedLimit: Int,
): LastFmPage<T> {
    val attr = obj("@attr")
    return LastFmPage(
        items = items,
        page = attr?.int("page") ?: requestedPage,
        perPage = attr?.int("perPage") ?: requestedLimit,
        totalPages = attr?.int("totalPages"),
        total = attr?.int("total"),
    )
}

private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

private fun JsonObject.text(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

private fun JsonObject.int(key: String): Int? = text(key)?.toIntOrNull()

private fun JsonObject.long(key: String): Long? = text(key)?.toLongOrNull()

/** Accepts one entry as an object and several as an array, which is how Last.fm sends single-entry lists. */
private fun JsonObject.objects(key: String): List<JsonObject> =
    when (val value = this[key]) {
        is JsonArray -> value.mapNotNull { it as? JsonObject }
        is JsonObject -> listOf(value)
        else -> emptyList()
    }

/** Keeps only the artwork entries that have a URL, since Last.fm sends empty placeholders for missing art. */
private fun JsonObject.images(): List<LastFmImage> =
    objects("image").mapNotNull { image ->
        image.text("#text")?.let { url -> LastFmImage(url = url, size = image.text("size")) }
    }

/** Last.fm names an artist or album as `#text` in recent listings and as `name` in top listings. */
private fun JsonObject.displayName(): String? = text("#text") ?: text("name")
