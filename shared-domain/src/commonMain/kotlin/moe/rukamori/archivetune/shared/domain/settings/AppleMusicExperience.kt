package moe.rukamori.archivetune.shared.domain.settings

/** Only an explicit preset action coordinates surfaces; no surface reads the preset as a live override. */
fun PreferenceEditor.applyInterfaceStyle(style: InterfaceStyle) {
    if (style == InterfaceStyle.CUSTOM || snapshot().interfaceStyle() == style) return
    migrateAppleMusicSettings()
    val enabled = style == InterfaceStyle.APPLE_MUSIC
    val playerStyle = this[PlayerDesignStyleKey].toEnum(PlayerDesignStyle.Default)
    val navigationStyle = this[NavigationBarStyleKey].toEnum(NavigationBarStyle.DEFAULT)
    this[AppleMusicSettingsMigratedKey] = true
    this[AppleMusicExperienceKey] = enabled
    this[LibraryStyleKey] = (if (enabled) LibraryStyle.APPLE_MUSIC else LibraryStyle.DEFAULT).name
    appleMusicSurfaceKeys.forEach { this[it] = enabled }

    if (enabled) {
        if (playerStyle != PlayerDesignStyle.APPLE_MUSIC) this[StyleBeforeAppleMusicKey] = playerStyle.name
        if (navigationStyle != NavigationBarStyle.APPLE_MUSIC) this[NavigationStyleBeforeAppleMusicKey] = navigationStyle.name
        this[PlayerDesignStyleKey] = PlayerDesignStyle.APPLE_MUSIC.name
        this[NavigationBarStyleKey] = NavigationBarStyle.APPLE_MUSIC.name
    } else {
        // A manually selected non-Apple style is newer than the displaced style and stays selected.
        if (playerStyle == PlayerDesignStyle.APPLE_MUSIC) {
            val restored = this[StyleBeforeAppleMusicKey].toEnum(PlayerDesignStyle.Default)
            this[PlayerDesignStyleKey] =
                (if (restored == PlayerDesignStyle.APPLE_MUSIC) PlayerDesignStyle.Default else restored).name
        }
        if (navigationStyle == NavigationBarStyle.APPLE_MUSIC) {
            val restored = this[NavigationStyleBeforeAppleMusicKey].toEnum(NavigationBarStyle.DEFAULT)
            this[NavigationBarStyleKey] =
                (if (restored == NavigationBarStyle.APPLE_MUSIC) NavigationBarStyle.DEFAULT else restored).name
        }
    }
}

private val appleMusicSurfaceKeys =
    listOf(AppleMusicHeadersKey, AppleMusicMenusKey, AppleMusicSearchBarKey, AppleMusicPaletteKey)

/**
 * Materializes the old read-side overrides once, preserving the presentation of an existing install.
 * The marker is essential: revisiting settings must never reapply a preset over subsequent individual choices.
 */
fun PreferenceEditor.migrateAppleMusicSettings() {
    if (this[AppleMusicSettingsMigratedKey, false] || this[AppleMusicExperienceKey] != true) return
    val legacy = this[AppleMusicExperienceKey, false]
    appleMusicSurfaceKeys.forEach { if (this[it] == null) this[it] = legacy }
    if (legacy) {
        if (this[LibraryStyleKey] == null) this[LibraryStyleKey] = LibraryStyle.APPLE_MUSIC.name
        if (this[PlayerDesignStyleKey] == null) this[PlayerDesignStyleKey] = PlayerDesignStyle.APPLE_MUSIC.name
        val previousNavigation = this[NavigationBarStyleKey].toEnum(NavigationBarStyle.DEFAULT)
        if (previousNavigation != NavigationBarStyle.APPLE_MUSIC) {
            this[NavigationStyleBeforeAppleMusicKey] = previousNavigation.name
        }
        this[NavigationBarStyleKey] = NavigationBarStyle.APPLE_MUSIC.name
    }
    this[AppleMusicSettingsMigratedKey] = true
}

/** A single sub-control never edits the preset or any other surface. */
fun PreferenceEditor.setLibraryStyle(style: LibraryStyle) {
    this[LibraryStyleKey] = style.name
}

private fun PreferenceSnapshot.appleMusicSurface(key: BooleanPreferenceKey): Boolean =
    this[key] ?: (!this[AppleMusicSettingsMigratedKey, false] && this[AppleMusicExperienceKey, false])

fun PreferenceSnapshot.appleMusicHeaders(): Boolean = appleMusicSurface(AppleMusicHeadersKey)
fun PreferenceSnapshot.appleMusicMenus(): Boolean = appleMusicSurface(AppleMusicMenusKey)
fun PreferenceSnapshot.appleMusicSearchBar(): Boolean = appleMusicSurface(AppleMusicSearchBarKey)
fun PreferenceSnapshot.appleMusicPalette(): Boolean = appleMusicSurface(AppleMusicPaletteKey)

fun PreferenceSnapshot.selectedPlayerDesignStyle(): PlayerDesignStyle =
    this[PlayerDesignStyleKey].toEnum(
        if (!this[AppleMusicSettingsMigratedKey, false] && this[AppleMusicExperienceKey, false])
            PlayerDesignStyle.APPLE_MUSIC else PlayerDesignStyle.Default,
    )

fun PreferenceSnapshot.selectedNavigationBarStyle(): NavigationBarStyle =
    if (!this[AppleMusicSettingsMigratedKey, false] && this[AppleMusicExperienceKey, false]) {
        NavigationBarStyle.APPLE_MUSIC
    } else this[NavigationBarStyleKey].toEnum(NavigationBarStyle.DEFAULT)

/** An indicator, never a rendering gate: one Apple Music option is a mixed presentation, not the whole experience. */
fun PreferenceSnapshot.interfaceStyle(): InterfaceStyle {
    val surfaces = listOf(
        selectedPlayerDesignStyle() == PlayerDesignStyle.APPLE_MUSIC,
        libraryStyle() == LibraryStyle.APPLE_MUSIC,
        selectedNavigationBarStyle() == NavigationBarStyle.APPLE_MUSIC,
        appleMusicHeaders(), appleMusicMenus(), appleMusicSearchBar(), appleMusicPalette(),
    )
    return when {
        surfaces.all { it } -> InterfaceStyle.APPLE_MUSIC
        surfaces.none { it } -> InterfaceStyle.MATERIAL_EXPRESSIVE
        else -> InterfaceStyle.CUSTOM
    }
}

/**
 * The Library layout. A layout that was never stored seeds from the experience switch, which is where Apple Music users
 * were before the layout had its own key. Once stored, the layout is read on its own.
 */
fun PreferenceSnapshot.libraryStyle(): LibraryStyle {
    val seeded =
        if (!this[AppleMusicSettingsMigratedKey, false] && this[AppleMusicExperienceKey, false]) {
            LibraryStyle.APPLE_MUSIC
        } else {
            PreferenceDefaults.libraryStyle
        }
    return this[LibraryStyleKey].toEnum(seeded)
}
