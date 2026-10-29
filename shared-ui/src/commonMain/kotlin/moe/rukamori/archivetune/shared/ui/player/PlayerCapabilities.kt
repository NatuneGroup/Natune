package moe.rukamori.archivetune.shared.ui.player

import moe.rukamori.archivetune.shared.domain.settings.*

enum class PlayerBackgroundStyle { DEFAULT, GRADIENT, CUSTOM, BLUR, COLORING, BLUR_GRADIENT, GLOW, GLOW_ANIMATED }
enum class PlayerSliderStyle { Standard, Wavy, Thick, Circular, Simple }
enum class MetrolistPresentation { MODERN, VINTAGE }

data class PlayerCapabilities(
    val nativeBackground: PlayerBackgroundStyle = PlayerBackgroundStyle.DEFAULT,
    val backgroundChoice: Boolean = true,
    val sharedControls: Boolean = false,
    val volume: Boolean = false,
    val ordinarySleeve: Boolean = false,
    val horizontalSwipe: Boolean = false,
    val artworkCanvas: Boolean = false,
    val monochrome: Boolean = false,
)

private val sharedControls = setOf("V1", "V2", "V3", "V4", "V6")
private val volumeRows = setOf("V7", "V8", "BITCHORD", "APPLE_MUSIC", "FLAMINGO")
private val sleeves = setOf("BITCHORD", "SIMPMUSIC", "SPATIALFLOW", "LOOPER", "FLAMINGO", "APPLE_MUSIC")
private val horizontalSwipes = setOf("SIMPMUSIC", "SPATIALFLOW", "LOOPER")
private val artworkCanvases = setOf("APPLE_MUSIC", "SPATIALFLOW", "LOOPER", "FLAMINGO")

val PlayerDesignStyle.capabilities: PlayerCapabilities
    get() = PlayerCapabilities(
        nativeBackground = when (this) {
            PlayerDesignStyle.APPLE_MUSIC, PlayerDesignStyle.SPATIALFLOW, PlayerDesignStyle.FLAMINGO -> PlayerBackgroundStyle.BLUR
            PlayerDesignStyle.BITCHORD, PlayerDesignStyle.TIKTOK -> PlayerBackgroundStyle.GLOW_ANIMATED
            PlayerDesignStyle.SIMPMUSIC -> PlayerBackgroundStyle.GRADIENT
            else -> PlayerBackgroundStyle.DEFAULT
        },
        backgroundChoice = this != PlayerDesignStyle.V8 && this != PlayerDesignStyle.V9,
        sharedControls = name in sharedControls,
        volume = name in volumeRows,
        ordinarySleeve = name in sleeves,
        horizontalSwipe = name in horizontalSwipes,
        artworkCanvas = name in artworkCanvases,
        monochrome = this == PlayerDesignStyle.V7 || this == PlayerDesignStyle.V8,
    )

/** A shared player preference whose effect depends on the selected style; mirrors the Android host's list. */
enum class PlayerStyleSetting { BACKGROUND, HIDE_THUMBNAIL, CORNER_RADIUS, BUTTONS_STYLE, SLIDER_STYLE, VOLUME_BAR }

private val sharedThumbnails = setOf("V1", "V2", "V3", "V4", "V6")

/** Whether this style's full player reads [setting]; Settings disables a row that would change nothing. */
fun PlayerDesignStyle.honors(setting: PlayerStyleSetting): Boolean = when (setting) {
    // V5 fills the sheet with an opaque primaryContainer field, so no backdrop can show through.
    PlayerStyleSetting.BACKGROUND -> capabilities.backgroundChoice && this != PlayerDesignStyle.V5
    PlayerStyleSetting.HIDE_THUMBNAIL -> name in sharedThumbnails || capabilities.ordinarySleeve ||
        name in setOf("V8", "V9", "V10", "METROLIST_KMP")
    PlayerStyleSetting.CORNER_RADIUS -> name in sharedThumbnails || capabilities.ordinarySleeve || name in setOf("V8", "V9")
    PlayerStyleSetting.BUTTONS_STYLE, PlayerStyleSetting.SLIDER_STYLE -> capabilities.sharedControls
    PlayerStyleSetting.VOLUME_BAR -> capabilities.volume
}

fun PlayerDesignStyle.resolveBackground(stored: PlayerBackgroundStyle): PlayerBackgroundStyle =
    if (!capabilities.backgroundChoice || stored == PlayerBackgroundStyle.DEFAULT) capabilities.nativeBackground else stored

fun effectivePlayerBackground(style: PlayerBackgroundStyle, blurOff: Boolean): PlayerBackgroundStyle =
    if (blurOff && style == PlayerBackgroundStyle.BLUR) PlayerBackgroundStyle.GRADIENT else style

fun playerVolumeVisible(style: PlayerDesignStyle, canonical: Boolean?, legacyFlamingo: Boolean?): Boolean =
    if (style == PlayerDesignStyle.FLAMINGO) legacyFlamingo ?: canonical ?: true else canonical ?: true

/** Reads never consolidate/migrate storage. Explicit edits belong to M6's atomic preference owner. */
data class PlayerPreferences(
    val style: PlayerDesignStyle,
    val background: PlayerBackgroundStyle,
    val slider: PlayerSliderStyle,
    val secondaryButtons: Boolean,
    val hideSleeve: Boolean,
    val sleeveRadius: Float,
    val swipeArtwork: Boolean,
    val showVolume: Boolean,
    val disableBlur: Boolean,
    val disableAnimations: Boolean,
    val customImageUri: String,
    val customBlur: Float,
    val customContrast: Float,
    val customBrightness: Float,
    val flamingoMotion: Boolean,
    val metrolistPresentation: MetrolistPresentation,
)

/** The sleeve is transparent but still laid out, so morphs and pagers keep their bounds. */
val PlayerPreferences.sleeveHidden: Boolean get() = hideSleeve && style.honors(PlayerStyleSetting.HIDE_THUMBNAIL)

fun PlayerPreferences.sleeveRadiusOr(native: Float): Float =
    if (style.honors(PlayerStyleSetting.CORNER_RADIUS)) sleeveRadius else native

fun PreferenceSnapshot.playerPreferences(): PlayerPreferences {
    val style = selectedPlayerDesignStyle()
    fun bounded(value: Float?, default: Float, range: ClosedFloatingPointRange<Float>) =
        value?.takeIf { it.isFinite() }?.coerceIn(range) ?: default
    return PlayerPreferences(
        style = style,
        background = this[PlayerBackgroundStyleKey].toEnum(PlayerBackgroundStyle.DEFAULT),
        slider = this[SliderStyleKey].toEnum(PlayerSliderStyle.Standard),
        secondaryButtons = this[PlayerButtonsStyleKey] == "SECONDARY",
        hideSleeve = this[HidePlayerThumbnailKey] ?: false,
        sleeveRadius = bounded(this[ThumbnailCornerRadiusKey], 8f, 0f..64f),
        swipeArtwork = this[SwipeThumbnailKey] ?: true,
        showVolume = showPlayerVolumeBar(style),
        disableBlur = this[DisableBlurKey] ?: false,
        disableAnimations = this[DisableAnimationsKey] ?: false,
        customImageUri = this[PlayerCustomImageUriKey] ?: "",
        customBlur = bounded(this[PlayerCustomBlurKey], 0f, 0f..100f),
        customContrast = bounded(this[PlayerCustomContrastKey], 1f, 0f..2f),
        customBrightness = bounded(this[PlayerCustomBrightnessKey], 1f, 0f..2f),
        flamingoMotion = this[FlamingoBackgroundEffectKey] ?: false,
        metrolistPresentation = this[MetrolistPresentationKey].toEnum(MetrolistPresentation.MODERN),
    )
}
