package moe.rukamori.archivetune.shared.domain.settings

/**
 * Applies an interface choice as one edit. The experience switch, the library layout and the player design move
 * together, and the player style the experience displaced is remembered so that turning it off can give it back.
 *
 * Choosing the state the switch already has changes nothing, so reselecting Apple Music never overwrites the remembered
 * style. A player style picked by hand while the experience is on is newer than the remembered one and is kept.
 */
fun PreferenceEditor.applyInterfaceStyle(style: InterfaceStyle) {
    val enabled = style == InterfaceStyle.APPLE_MUSIC
    if ((this[AppleMusicExperienceKey] ?: PreferenceDefaults.appleMusicExperience) == enabled) return

    val playerStyle = this[PlayerDesignStyleKey].toEnum(PlayerDesignStyle.Default)
    this[AppleMusicExperienceKey] = enabled
    this[LibraryStyleKey] = (if (enabled) LibraryStyle.APPLE_MUSIC else LibraryStyle.DEFAULT).name

    if (enabled) {
        if (playerStyle != PlayerDesignStyle.APPLE_MUSIC) {
            this[StyleBeforeAppleMusicKey] = playerStyle.name
        }
        this[PlayerDesignStyleKey] = PlayerDesignStyle.APPLE_MUSIC.name
    } else if (playerStyle == PlayerDesignStyle.APPLE_MUSIC) {
        // A stored value that is removed, unknown, or Apple Music itself cannot be restored, so the default returns.
        val restored = this[StyleBeforeAppleMusicKey].toEnum(PlayerDesignStyle.Default)
        this[PlayerDesignStyleKey] =
            (if (restored == PlayerDesignStyle.APPLE_MUSIC) PlayerDesignStyle.Default else restored).name
    }
}

/**
 * Stores the Library layout. It never touches the experience switch or the player style, so picking a layout for one
 * tab cannot restyle the rest of the app.
 */
fun PreferenceEditor.setLibraryStyle(style: LibraryStyle) {
    this[LibraryStyleKey] = style.name
}

/** The presentation the switch selects. Material 3 Expressive unless the Apple Music experience is on. */
fun PreferenceSnapshot.interfaceStyle(): InterfaceStyle =
    if (this[AppleMusicExperienceKey, PreferenceDefaults.appleMusicExperience]) {
        InterfaceStyle.APPLE_MUSIC
    } else {
        InterfaceStyle.MATERIAL_EXPRESSIVE
    }

/**
 * The Library layout. A layout that was never stored seeds from the experience switch, which is where Apple Music users
 * were before the layout had its own key. Once stored, the layout is read on its own.
 */
fun PreferenceSnapshot.libraryStyle(): LibraryStyle {
    val seeded =
        if (this[AppleMusicExperienceKey, PreferenceDefaults.appleMusicExperience]) {
            LibraryStyle.APPLE_MUSIC
        } else {
            PreferenceDefaults.libraryStyle
        }
    return this[LibraryStyleKey].toEnum(seeded)
}
