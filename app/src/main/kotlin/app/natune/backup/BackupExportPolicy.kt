package app.natune.backup

import app.natune.shared.data.backup.BackupSecretPolicy

/** Plaintext archives cannot safely carry account credentials, even when ACCOUNT was selected. */
internal object BackupExportPolicy {
    val DEFAULT_CATEGORIES = setOf(BackupArchiveCategory.LIBRARY, BackupArchiveCategory.SETTINGS)

    /**
     * Responses the app downloads again on its own (release and commit feeds, the update manifest, the Spotify
     * library cache). They made up most of a real 672 KB settings.xml and are stale by the time a backup restores.
     */
    private val cachePrefixes = listOf("github_", "daily_nightly_releases_", "update_feed_manifest_")
    private val cacheNames = setOf("spotify_library_playlists_cache", "spotify_profile_cache_ts")

    fun isRefetchableCache(name: String): Boolean = name in cacheNames || cachePrefixes.any(name::startsWith)

    fun includes(name: String, categories: Set<BackupArchiveCategory>): Boolean {
        if (BackupSecretPolicy.isSecret(name)) return false
        if (name in ScheduledBackupRepository.NON_PORTABLE_PREFERENCE_KEYS) return false
        if (isRefetchableCache(name)) return false
        return BackupPreferenceSelection.includes(name, settings = BackupArchiveCategory.SETTINGS in categories, account = false)
    }
}
