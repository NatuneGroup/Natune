package moe.rukamori.archivetune.shared.domain.settings

/** A legacy XML merge must not inherit this installation's already-migrated experience marker. */
fun PreferenceEditor.normalizeRestoredSettings(restoredKeyNames: Set<String>) {
    if (AppleMusicExperienceKey.name in restoredKeyNames && AppleMusicSettingsMigratedKey.name !in restoredKeyNames) {
        val surfaces = listOf(AppleMusicMenusKey, AppleMusicSearchBarKey, AppleMusicPaletteKey)
        if (this[AppleMusicExperienceKey] == true) {
            this[AppleMusicSettingsMigratedKey] = null
            surfaces.filterNot { it.name in restoredKeyNames }.forEach { this[it] = null }
        } else {
            surfaces.filterNot { it.name in restoredKeyNames }.forEach { this[it] = false }
            this[AppleMusicSettingsMigratedKey] = true
        }
    }
    migrateAppleMusicSettings()
    normalizeSettingsConflicts()
}
