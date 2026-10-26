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
