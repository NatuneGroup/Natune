package moe.rukamori.archivetune.backup

import moe.rukamori.archivetune.shared.data.backup.BackupSecretPolicy

/** Plaintext archives cannot safely carry account credentials, even when ACCOUNT was selected. */
internal object BackupExportPolicy {
    val DEFAULT_CATEGORIES = setOf(BackupArchiveCategory.LIBRARY, BackupArchiveCategory.SETTINGS)

    fun includes(name: String, categories: Set<BackupArchiveCategory>): Boolean {
        if (BackupSecretPolicy.isSecret(name)) return false
        if (name in ScheduledBackupRepository.NON_PORTABLE_PREFERENCE_KEYS) return false
        return BackupPreferenceSelection.includes(name, settings = BackupArchiveCategory.SETTINGS in categories, account = false)
    }
}
