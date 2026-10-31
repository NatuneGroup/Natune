package moe.rukamori.archivetune.desktop

import moe.rukamori.archivetune.innertube.models.SongItem
import moe.rukamori.archivetune.shared.domain.repository.ContentCredit
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.domain.repository.ContentRef

internal fun SongItem.desktopContentItem(): ContentItem = ContentItem(
    ref = ContentRef(ContentProvider.YOUTUBE.id, if (isPodcast) ContentKind.EPISODE else ContentKind.SONG, id),
    title = title,
    subtitle = artists.joinToString(", ") { it.name },
    artworkUri = thumbnail,
    durationMs = duration?.takeIf { it >= 0 }?.let { it * 1000L },
    explicit = explicit,
    album = album?.name,
    albumId = album?.id?.takeIf(String::isNotBlank),
    artists = artists.mapNotNull { artist ->
        artist.name.takeIf(String::isNotBlank)?.let { ContentCredit(it, artist.id?.takeIf(String::isNotBlank)) }
    },
)
