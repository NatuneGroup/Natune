package moe.rukamori.archivetune.shared.domain.settings

/**
 * Decodes a stored enum name exactly as the app does: the name must match, case included, and anything else (or no
 * value at all) takes the default. A removed style therefore falls back instead of failing.
 */
inline fun <reified E : Enum<E>> String?.toEnum(defaultValue: E): E =
    enumValues<E>().firstOrNull { it.name == this } ?: defaultValue

/**
 * The player layouts. The declaration order and the names are the persisted contract: stored values are these names,
 * so renaming or reordering one changes what users already have saved.
 */
enum class PlayerDesignStyle {
    V1,
    V2,
    V3,
    V4,
    V5,
    V6,
    V7,
    V8,
    V9,
    APPLE_MUSIC,
    V10,
    BITCHORD,
    TIKTOK,
    SIMPMUSIC,
    SPATIALFLOW,
    LOOPER,
    FLAMINGO,
    METROLIST_KMP,
    ;

    companion object {
        /** The player a fresh install opens with. */
        val Default: PlayerDesignStyle = V4
    }
}

/** Which presentation the app shows: the Material 3 Expressive default, or the Apple Music experience. */
enum class InterfaceStyle {
    MATERIAL_EXPRESSIVE,
    APPLE_MUSIC,
}

/**
 * The bottom bar looks, in the baseline's declaration order. DEFAULT keeps the docked full-width bar, FLOATING
 * detaches it into a pill, YUMA_PLAYER and APPLE_MUSIC are the floating neutral capsules, and SUKISU renders the
 * SukiSU liquid-glass bar which fixes its own dimensions and ignores the bar tuning sliders.
 */
enum class NavigationBarStyle {
    DEFAULT,
    FLOATING,
    YUMA_PLAYER,
    APPLE_MUSIC,
    SUKISU,
}

/** When the bottom chrome folds into the one-row compact layout. */
enum class NavigationBarCompactBehavior {
    ADAPTIVE,
    ALWAYS_EXPANDED,
    ALWAYS_COMPACT,
}

/** Where the Search page's field sits. */
enum class SearchBarPosition {
    TOP,
    BOTTOM,
}

/** The mini player's backdrop material. */
enum class MiniPlayerBackgroundStyle {
    THEME,
    GRADIENT,
    GLOW,
    FROSTED,
    LIQUID_GLASS,
}

/** The Library tab's layout. It sets only the tab's layout and never the Apple Music experience switch. */
enum class LibraryStyle {
    DEFAULT,
    APPLE_MUSIC,
}

/** Which font the UI uses. [CUSTOM] takes its file from the custom font URI. */
enum class AppFontPreference {
    DEFAULT,
    SYSTEM,
    CUSTOM,
}
