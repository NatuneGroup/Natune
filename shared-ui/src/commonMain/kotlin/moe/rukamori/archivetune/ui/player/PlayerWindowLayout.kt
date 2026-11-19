package moe.rukamori.archivetune.ui.player

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * How a player surface arranges itself inside the box it was given. Android's
 * `Configuration.orientation` says PORTRAIT for the floating/pop-up, freeform and split-screen
 * windows a phone can put the app in, because those windows are usually still taller than they are
 * wide — they are simply a few hundred dp tall. Every player surface used to branch on that
 * orientation and then size itself against `screenHeightDp`, so a 420x400 dp pop-up was handed the
 * layout of a 420x900 dp phone: full-height weight(1f) artwork stages, 80 dp transport glyphs and
 * 104 dp headers that together needed more room than the window had. The artwork collapsed to a
 * sliver, the controls were pushed off the bottom and everything read as squashed.
 *
 * The decisions here are pure functions of the *container's* width and height in dp, so the same
 * window rules apply to a pop-up, a split-screen pane, a bottom sheet that is only partly expanded
 * and a desktop freeform window — and they are testable without Compose.
 */
enum class PlayerWindowMode {
    /** A full phone window (or a tablet one): the stacked, phone-first arrangement. */
    REGULAR,

    /** Under [PlayerWindowLayout.COMPACT_HEIGHT_DP]: smaller glyphs, gaps and artwork. */
    COMPACT,

    /** Under [PlayerWindowLayout.SHORT_HEIGHT_DP]: secondary rows are dropped as well. */
    SHORT,
}

/**
 * The layout rules shared by the player surfaces. Everything is measured in dp against the box the
 * surface is drawn in — never against the device screen, and never against the orientation.
 */
object PlayerWindowLayout {
    /**
     * Below this height the stacked phone arrangement stops fitting: its artwork stage, title
     * block, scrubber, transport row, volume row and action row together need roughly this much. It
     * matches the Material window size class "compact height" breakpoint, so a phone in landscape
     * (about 411 dp tall) and a pop-up window land on the same side of it, as they do.
     */
    const val COMPACT_HEIGHT_DP = 480f

    /** Below this height even the compact arrangement has to let secondary rows go. */
    const val SHORT_HEIGHT_DP = 360f

    /** A window this much wider than it is tall gets the two-pane arrangement. */
    const val WIDE_ASPECT_RATIO = 1.15f

    /**
     * ...and a short window this wide gets the two-pane arrangement too, even at a squarer aspect:
     * with only a few hundred dp of height, side-by-side controls (and a height-limited artwork)
     * keep every control legible where the stacked arrangement would leave no room for either.
     * Under it the panes would be too narrow for the transport row, so the compact stack wins.
     */
    const val SIDE_BY_SIDE_MIN_WIDTH_DP = 480f

    /** Side margins a stacked player leaves around its artwork. */
    const val STACKED_ARTWORK_HORIZONTAL_INSET_DP = 32f

    /** The height band a stacked player's artwork stage takes at [heightDp]. */
    fun artworkStageFraction(heightDp: Float): Float =
        if (heightDp < COMPACT_HEIGHT_DP) {
            0.46f
        } else {
            0.595f
        }

    /** The height band a stacked player's bottom controls take at [heightDp]. */
    fun controlsStageFraction(heightDp: Float): Float =
        if (heightDp < COMPACT_HEIGHT_DP) {
            0.50f
        } else {
            0.437f
        }

    /** The gap between a stacked player's controls stage top edge and its transport block. */
    fun controlsTopGapDp(heightDp: Float): Float =
        if (heightDp < COMPACT_HEIGHT_DP) {
            12f
        } else {
            52f
        }

    /** The spacer a stacked player leaves above its lyrics list, under the header. */
    fun lyricsTopInsetDp(heightDp: Float): Float =
        if (heightDp < COMPACT_HEIGHT_DP) {
            64f
        } else {
            104f
        }

    /** Which arrangement a window of [heightDp] gets. */
    fun modeFor(heightDp: Float): PlayerWindowMode =
        when {
            heightDp < SHORT_HEIGHT_DP -> PlayerWindowMode.SHORT
            heightDp < COMPACT_HEIGHT_DP -> PlayerWindowMode.COMPACT
            else -> PlayerWindowMode.REGULAR
        }

    /** True when the window is too short for the stacked phone arrangement. */
    fun isCompact(heightDp: Float): Boolean = heightDp < COMPACT_HEIGHT_DP

    /**
     * Whether this box should use the two-pane arrangement (artwork beside the controls) instead of
     * the stacked one. This replaces the old `Configuration.orientation == LANDSCAPE` test: it is
     * derived from the box, so a wide pop-up gets the two panes it has room for.
     */
    fun usesWideArrangement(widthDp: Float, heightDp: Float): Boolean =
        widthDp >= heightDp * WIDE_ASPECT_RATIO ||
            (heightDp < COMPACT_HEIGHT_DP && widthDp >= SIDE_BY_SIDE_MIN_WIDTH_DP)

    /**
     * The biggest square that fits one pane of a two-pane arrangement: half the window's width
     * (less [STACKED_ARTWORK_HORIZONTAL_INSET_DP]) and the height that is left once
     * [verticalReserveDp] for the title block, insets and controls has been taken out. The result
     * shrinks below any style's usual artwork size when the box has less room than that, which is
     * the point: an artwork that overflows its pane is exactly the clipping this replaces.
     */
    fun sideBySideArtworkSizeDp(
        widthDp: Float,
        heightDp: Float,
        verticalReserveDp: Float,
    ): Float {
        val paneWidth = widthDp / 2f - STACKED_ARTWORK_HORIZONTAL_INSET_DP
        val paneHeight = heightDp - verticalReserveDp
        return minOf(paneWidth, paneHeight).coerceAtLeast(0f)
    }

    /**
     * The biggest square artwork that fits a stacked player's window: [widthFraction] of its width
     * (the style's own proportion) capped by the share of the height the artwork stage may take.
     * Used by the styles that used to size their artwork from `screenWidthDp` alone, which in a
     * short window produced a square taller than the window.
     */
    fun stackedArtworkSizeDp(
        widthDp: Float,
        heightDp: Float,
        widthFraction: Float = 0.9f,
    ): Float {
        val fromWidth = widthDp * widthFraction
        val fromHeight = heightDp * artworkStageFraction(heightDp)
        return minOf(fromWidth, fromHeight).coerceAtLeast(0f)
    }

    /** [stackedArtworkSizeDp] for call sites that already hold [Dp] values. */
    fun stackedArtwork(
        width: Dp,
        height: Dp,
        widthFraction: Float = 0.9f,
    ): Dp = stackedArtworkSizeDp(width.value, height.value, widthFraction).dp

    /**
     * The largest square that fits a stage of [width] x [height] — the artwork slot of a style
     * whose stage is already the leftover space, so no width share is taken off the top.
     */
    fun fittedArtwork(width: Dp, height: Dp): Dp = stackedArtwork(width, height, widthFraction = 1f)

    /**
     * The vertical budget of a stacked player at [heightDp]: how much of the height the artwork
     * stage takes, how much the bottom controls take, and the gap above the transport block inside
     * them. Bundled so a style can read all three from one call at a shallow indentation.
     */
    fun stackedStages(heightDp: Float): PlayerStackedStages =
        PlayerStackedStages(
            artworkFraction = artworkStageFraction(heightDp),
            controlsFraction = controlsStageFraction(heightDp),
            topGap = controlsTopGapDp(heightDp).dp,
        )

    /**
     * Whether the stacked arrangement still has room for the volume row. Dropped only in the
     * shortest windows, where the transport block needs the room to keep its glyphs at size.
     */
    fun showsVolumeRow(heightDp: Float): Boolean = !dropsSecondaryRows(heightDp)

    /**
     * True when the window is so short that the player's secondary rows — the volume row first —
     * have to give way so the artwork and the transport keep a usable size.
     */
    fun dropsSecondaryRows(heightDp: Float): Boolean = heightDp < SHORT_HEIGHT_DP
}

/** [PlayerWindowLayout.stackedStages]'s answer: the stacked player's vertical split. */
class PlayerStackedStages(
    val artworkFraction: Float,
    val controlsFraction: Float,
    val topGap: Dp,
)
