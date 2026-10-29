package moe.rukamori.archivetune.shared.domain.songmetadata

import kotlinx.coroutines.flow.Flow
import moe.rukamori.archivetune.shared.domain.repository.ContentCredit
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.ContentPage
import moe.rukamori.archivetune.shared.domain.repository.ContentRef

/** Null fields follow the provider; an absent row follows it entirely. Identities and audio never change. */
data class SongMetadataOverride(
    val title: String? = null,
    val artistNames: List<String>? = null,
    val updatedAtMs: Long = 0L,
)

/** Retained only in an effective in-memory item. Persistence and provider resolution use [canonicalSong]. */
data class CanonicalSongMetadata(
    val title: String,
    val subtitle: String,
    val artists: List<ContentCredit>,
)

interface SongMetadataRepository {
    fun observeOverrides(): Flow<Map<ContentRef, SongMetadataOverride>>
    suspend fun overrides(): Map<ContentRef, SongMetadataOverride>
    /** Null resets one song. Completion means the edit/reset was committed. */
    suspend fun setOverride(song: ContentRef, metadata: SongMetadataOverride?)
}

fun ContentItem.canonicalSong(): ContentItem = canonicalSongMetadata?.let {
    copy(title = it.title, subtitle = it.subtitle, artists = it.artists, canonicalSongMetadata = null)
} ?: this

/** Providers without structured credits expose their artist text as the subtitle. */
fun ContentItem.songArtistNames(): List<String> =
    artists.map { it.name }.ifEmpty { listOfNotNull(subtitle.takeIf(String::isNotBlank)) }

fun buildSongMetadataOverride(
    song: ContentItem,
    enteredTitle: String,
    enteredArtistNames: List<String>,
    updatedAtMs: Long,
): SongMetadataOverride? {
    require(song.ref.kind == ContentKind.SONG)
    val canonical = song.canonicalSong()
    val title = enteredTitle.trim().takeIf { it.isNotEmpty() && it != canonical.title }
    val artists = enteredArtistNames.map(String::trim).filter(String::isNotEmpty).distinct()
        .takeIf { it.isNotEmpty() && it != canonical.songArtistNames() }
    return if (title == null && artists == null) null else SongMetadataOverride(title, artists, updatedAtMs)
}

fun ContentItem.withSongMetadataOverride(metadata: SongMetadataOverride?): ContentItem {
    val canonical = canonicalSong()
    if (ref.kind != ContentKind.SONG || metadata == null) return canonical
    val credits = metadata.artistNames?.map { name ->
        canonical.artists.firstOrNull { it.name == name } ?: ContentCredit(name)
    }
    return canonical.copy(
        title = metadata.title ?: canonical.title,
        subtitle = credits?.joinToString { it.name } ?: canonical.subtitle,
        artists = credits ?: canonical.artists,
        canonicalSongMetadata = CanonicalSongMetadata(canonical.title, canonical.subtitle, canonical.artists),
    )
}

/** A null map is an unbound presentation host; do not strip metadata already supplied by its repository. */
fun ContentItem.withSongMetadataOverrides(overrides: Map<ContentRef, SongMetadataOverride>?): ContentItem =
    if (overrides == null) this else withSongMetadataOverride(overrides[ref])

fun ContentOccurrence.withSongMetadataOverrides(overrides: Map<ContentRef, SongMetadataOverride>?): ContentOccurrence =
    copy(item = item.withSongMetadataOverrides(overrides))

fun ContentPage.withSongMetadataOverrides(overrides: Map<ContentRef, SongMetadataOverride>): ContentPage =
    copy(items = items.map { it.withSongMetadataOverrides(overrides) })

fun ContentItem.matchesSongSearch(query: String): Boolean =
    title.contains(query, ignoreCase = true) || songArtistNames().any { it.contains(query, ignoreCase = true) }
