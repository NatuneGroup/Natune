/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.ui.home.variants

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.ContentRef

/**
 * What the player is on. The owner supplies it; the variants compare against it and never change it.
 * [trackRef] marks the active song and [albumRef] marks the active album, as the baseline does.
 */
data class HomeVariantNowPlaying(
    val isPlaying: Boolean = false,
    val trackRef: ContentRef? = null,
    val albumRef: ContentRef? = null,
)

/** A host image slot for artwork that is not a content item (account avatar, section thumbnail). */
typealias HomeVariantImage = @Composable (url: String, modifier: Modifier) -> Unit

/**
 * The artwork frame ratio (width over height) for one item's card, asked as each card is drawn. The owner maps the real
 * crop preference and video-frame metadata here. Square is only the unknown or default fallback, not a stand-in for a
 * known wide frame; a wide frame keeps its own proportions and drops the card's hairline border. Hero carousels, list
 * rows and speed-dial tiles keep their fixed frames and never read this.
 */
typealias HomeVariantThumbnailAspectRatio = (item: ContentItem) -> Float

/**
 * The ratio a card actually draws with. Artists stay square because they are drawn as circles, as the baseline rule has
 * it. Any other owner value must be finite and positive; anything else falls back to square rather than failing layout.
 */
internal fun HomeVariantThumbnailAspectRatio.frameRatioFor(item: ContentItem): Float {
    if (item.ref.kind == ContentKind.ARTIST) return 1f
    val ratio = this(item)
    return if (ratio.isFinite() && ratio > 0f) ratio else 1f
}

/** One chip in a category row. [key] is the owner's stable identity, so selection survives a reload. */
data class HomeVariantChip(val key: String, val title: String)

/**
 * One remote shelf as the owner delivers it, in endpoint order. [thumbnailUrl] is drawn through [HomeVariantImage]
 * and [label] is the subtitle line. The owner decides where the header leads from [key].
 */
data class HomeVariantSection(
    val key: String,
    val title: String,
    val label: String = "",
    val thumbnailUrl: String? = null,
    val items: List<ContentOccurrence> = emptyList(),
)

/**
 * A "Similar to" discovery deck. [source] is the item the deck is similar to and [openRef] is where its header leads:
 * the album for a song source, the item itself for album and artist sources, and null for playlists (no action).
 * [items] are the first items of the deck, in order.
 */
data class HomeVariantDeck(
    val key: String,
    val source: ContentItem,
    val openRef: ContentRef?,
    val items: List<ContentOccurrence> = emptyList(),
)

enum class HomeVariantQuickPickLayout { CARD, LIST }

/**
 * Quick picks after the owner's mode choice. [remote] is set only while the remote quick-picks mode is active, and the
 * remote section wins over [local] when it has items, as the baseline does.
 * [localFromHistory] picks the history glyph for last-listen mode; [localVisible] is false in don't-show mode.
 */
data class HomeVariantQuickPicks(
    val remote: HomeVariantSection? = null,
    val local: List<ContentOccurrence> = emptyList(),
    val localFromHistory: Boolean = false,
    val localVisible: Boolean = true,
)

/**
 * A playback intent. The owner builds the real queue from it, so the variant never picks a queue owner or fakes one.
 * Each variant documents which surface sends which intent and whether a tap on an already-playing song toggles
 * play and pause instead. Rukamori toggles for speed-dial, keep-listening and forgotten-favourite songs and for quick
 * picks, and never for remote shelves, similar decks or the random speed-dial tile. BitChord toggles for speed-dial
 * and keep-listening songs and for remote YouTube songs ([Watch]), and never for recents, jump back in or forgotten
 * favourites.
 */
sealed interface HomeVariantPlay {
    /**
     * A finite list queue over [occurrences] that starts at [startIndex]; Next walks the same list in order.
     * When [toggleIfActive] is true and the item at [startIndex] is already playing, the owner toggles play and pause.
     */
    data class Shelf(
        val title: String,
        val occurrences: List<ContentOccurrence>,
        val startIndex: Int,
        val toggleIfActive: Boolean,
    ) : HomeVariantPlay

    /** A single-item list queue, used for podcast episodes. [title] is the podcast name or the episode title. */
    data class Single(val title: String, val occurrence: ContentOccurrence) : HomeVariantPlay

    /**
     * A quick-pick tap. The owner always toggles when the track is already playing. Otherwise local tracks queue
     * alone, YouTube tracks start a radio, and a remote-section pick in [HomeVariantQuickPickLayout.LIST] starts that
     * section's own watch queue, matching the baseline. [sectionKey] is null for local picks.
     */
    data class QuickPick(
        val occurrence: ContentOccurrence,
        val sectionKey: String?,
        val layout: HomeVariantQuickPickLayout,
    ) : HomeVariantPlay

    /**
     * A YouTube song that starts its own watch queue (the song's radio) from [occurrence]. The baseline BitChord remote
     * shelves play this way rather than as a finite shelf, and the owner toggles when the song already plays.
     */
    data class Watch(val occurrence: ContentOccurrence) : HomeVariantPlay
}
