package moe.rukamori.archivetune.constants

/** A shared player preference whose effect depends on the selected [PlayerDesignStyle]. */
enum class PlayerStyleSetting {
    BACKGROUND,
    HIDE_THUMBNAIL,
    CORNER_RADIUS,
    SWIPE_THUMBNAIL,
    BUTTONS_STYLE,
    SLIDER_STYLE,
    VOLUME_BAR,
}

/** Styles that draw the shared Thumbnail, which reads the sleeve and swipe preferences itself. */
private val SharedThumbnailStyles =
    setOf(PlayerDesignStyle.V1, PlayerDesignStyle.V2, PlayerDesignStyle.V3, PlayerDesignStyle.V4, PlayerDesignStyle.V6)

/** Whether the style reads the hidden-sleeve flag of the player presentation; V10 hides its die-cut cover but keeps its own shape. */
val PlayerDesignStyle.presentsHiddenSleeve: Boolean
    get() = hasOrdinarySleeve || this == PlayerDesignStyle.V8 || this == PlayerDesignStyle.V9 || this == PlayerDesignStyle.V10

val PlayerDesignStyle.presentsSleeveRadius: Boolean
    get() = hasOrdinarySleeve || this == PlayerDesignStyle.V8 || this == PlayerDesignStyle.V9

/** BitChord drags its sleeve and Flamingo's landscape page swipes, so both answer to the swipe preference too. */
val PlayerDesignStyle.presentsArtworkSwipe: Boolean
    get() = hasHorizontalArtworkSkip || this == PlayerDesignStyle.BITCHORD || this == PlayerDesignStyle.FLAMINGO

/**
 * Whether the full player of this style reads [setting]. Settings disable a row (or, when another surface such as the
 * mini player still reads it, annotate it) instead of offering a value that would change nothing.
 */
fun PlayerDesignStyle.honors(setting: PlayerStyleSetting): Boolean =
    when (setting) {
        // V5 fills the whole sheet with an opaque primaryContainer field, so no backdrop can show through.
        PlayerStyleSetting.BACKGROUND -> supportsBackgroundChoice && this != PlayerDesignStyle.V5
        PlayerStyleSetting.HIDE_THUMBNAIL -> this in SharedThumbnailStyles || presentsHiddenSleeve
        PlayerStyleSetting.CORNER_RADIUS -> this in SharedThumbnailStyles || presentsSleeveRadius
        PlayerStyleSetting.SWIPE_THUMBNAIL -> this in SharedThumbnailStyles || presentsArtworkSwipe
        PlayerStyleSetting.BUTTONS_STYLE -> supportsSharedButtons
        PlayerStyleSetting.SLIDER_STYLE -> supportsSharedSeekStyle
        PlayerStyleSetting.VOLUME_BAR -> hasVolumeRow
    }
