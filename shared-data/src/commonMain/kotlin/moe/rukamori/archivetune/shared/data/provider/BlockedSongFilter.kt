package moe.rukamori.archivetune.shared.data.provider

import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.domain.settings.BlockedSongRef

/**
 * The recommendation-side song block list the Android host applies in `HomeViewModel.filterBlockedSongs`: a YouTube
 * song the user blocked drops out of the home, suggestion and radio feeds. Entries of another kind pass through
 * unchanged, as the Android filter does, and refs belonging to other providers cannot match a YouTube feed.
 */

fun List<YouTubeEntry>.filterBlockedSongs(blocked: Set<BlockedSongRef>): List<YouTubeEntry> {
    if (blocked.isEmpty()) return this
    return filter { entry ->
        entry.kind != ContentKind.SONG ||
            BlockedSongRef(ContentProvider.YOUTUBE.id, entry.id) !in blocked
    }
}

fun YouTubeShelf.filterBlockedSongs(blocked: Set<BlockedSongRef>): YouTubeShelf =
    if (blocked.isEmpty()) this else copy(entries = entries.filterBlockedSongs(blocked))

/** A shelf whose every entry was blocked leaves the feed, as the Android browse result drops its empty sections. */
@kotlin.jvm.JvmName("filterBlockedSongShelves")
fun List<YouTubeShelf>.filterBlockedSongs(blocked: Set<BlockedSongRef>): List<YouTubeShelf> {
    if (blocked.isEmpty()) return this
    return map { it.filterBlockedSongs(blocked) }.filter { it.entries.isNotEmpty() }
}

fun YouTubeShelfPage.filterBlockedSongs(blocked: Set<BlockedSongRef>): YouTubeShelfPage =
    if (blocked.isEmpty()) this else copy(shelves = shelves.filterBlockedSongs(blocked))
