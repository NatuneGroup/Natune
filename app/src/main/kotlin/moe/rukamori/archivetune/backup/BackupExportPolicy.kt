package moe.rukamori.archivetune.backup

/** Plaintext archives cannot safely carry account credentials, even when ACCOUNT was selected. */
internal object BackupExportPolicy {
    val DEFAULT_CATEGORIES = setOf(BackupArchiveCategory.LIBRARY, BackupArchiveCategory.SETTINGS)
    private val credentialFragments = listOf("cookie", "token", "password", "secret", "apikey", "credential", "jwt", "session")
    private val credentialNames = setOf("spotifyspdc", "spotifyspkey", "deezerarl", "qqmusicmusickey", "lypriosigningkey")

    fun includes(name: String, categories: Set<BackupArchiveCategory>): Boolean {
        val normalized = name.filter(Char::isLetterOrDigit).lowercase()
        if (credentialFragments.any(normalized::contains) || normalized in credentialNames) return false
        if (name in ScheduledBackupRepository.NON_PORTABLE_PREFERENCE_KEYS) return false
        return BackupPreferenceSelection.includes(name, settings = BackupArchiveCategory.SETTINGS in categories, account = false)
    }
}
