package moe.rukamori.archivetune.backup

/** Conservative archive bounds; a realistic music library database stays far below these. */
internal object AndroidRestoreLimits {
    const val MAX_TOTAL_BYTES = 1L * 1024 * 1024 * 1024
    const val MAX_ENTRY_BYTES = 512L * 1024 * 1024
    const val MAX_SETTINGS_BYTES = 16L * 1024 * 1024
    const val MAX_FONT_BYTES = 64L * 1024 * 1024
    const val MAX_FONT_ENTRIES = 64
    const val MIN_FREE_BYTES = 32L * 1024 * 1024
}

internal data class AndroidRestoreFont(
    val name: String,
    val length: Long,
)

/** The ready marker's font identity is hex-encoded so a harmless filename cannot break line parsing. */
internal object AndroidRestoreFontPolicy {
    fun isValidName(name: String): Boolean =
        name.isNotEmpty() && isBackupFontEntry("${BackupArchiveRepository.FONTS_ZIP_PREFIX}/$name")

    fun isValid(entry: AndroidRestoreFont): Boolean =
        isValidName(entry.name) && entry.length in 1..AndroidRestoreLimits.MAX_FONT_BYTES

    fun encode(entries: List<AndroidRestoreFont>): String =
        entries.sortedBy { it.name }.joinToString(",") { entry ->
            "${hex(entry.name)}:${entry.length}"
        }

    fun decode(text: String): List<AndroidRestoreFont>? {
        if (text.isBlank()) return emptyList()
        val entries = text.split(',').map { part ->
            val separator = part.lastIndexOf(':')
            if (separator <= 0) return null
            val name = unhex(part.substring(0, separator)) ?: return null
            val length = part.substring(separator + 1).toLongOrNull() ?: return null
            AndroidRestoreFont(name, length)
        }
        if (entries.size > AndroidRestoreLimits.MAX_FONT_ENTRIES) return null
        if (entries.any { !isValid(it) }) return null
        if (entries.map { it.name }.toSet().size != entries.size) return null
        return entries.sortedBy { it.name }
    }

    fun matches(expected: List<AndroidRestoreFont>, actual: List<AndroidRestoreFont>): Boolean =
        expected.sortedBy { it.name } == actual.sortedBy { it.name } &&
            expected.all(::isValid)

    fun isSafeStageId(value: String): Boolean =
        value.length in 1..64 && value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' || it == '-' }

    private fun hex(value: String): String = buildString {
        value.encodeToByteArray().forEach { byte ->
            append(HEX[(byte.toInt() shr 4) and 0xf])
            append(HEX[byte.toInt() and 0xf])
        }
    }

    private fun unhex(value: String): String? {
        if (value.isEmpty() || value.length % 2 != 0) return null
        val bytes = ByteArray(value.length / 2)
        for (index in bytes.indices) {
            val high = value[index * 2].digitToIntOrNull(16) ?: return null
            val low = value[index * 2 + 1].digitToIntOrNull(16) ?: return null
            bytes[index] = ((high shl 4) or low).toByte()
        }
        return runCatching { bytes.decodeToString(throwOnInvalidSequence = true) }.getOrNull()
    }

    private const val HEX = "0123456789abcdef"
}

internal object AndroidRestoreBlockedPolicy {
    /** A durable blocked marker means the launcher gate owns visibility; background starts stay cheap. */
    fun shouldSkipHeavyRecovery(markerExists: Boolean): Boolean = markerExists

    /** The first blocked launch may try the best-effort in-process screen; later starts stay quiet. */
    fun shouldAttemptBestEffortScreen(markerExistedAtStartup: Boolean): Boolean = !markerExistedAtStartup
}

/** Which preference keys a backup or restore carries for the selected categories. */
internal object BackupPreferenceSelection {
    private val accountPrefixes = listOf("account.", "spotify_stream_", "SpotifyStream")

    /**
     * The shared pool cache is sealed with a non-exportable Keystore key, so on any other install it is
     * unreadable ciphertext, and a restored refresh time would hold back the refetch that replaces it.
     */
    val DEVICE_BOUND_KEYS: Set<String> =
        setOf(
            "poolTidalAccounts",
            "poolQobuzAccounts",
            "poolDeezerAccounts",
            "poolAppleMusicAccounts",
            "poolAmazonAccounts",
            "poolAccountsLastRefreshAtMillis",
        )

    fun isAccountKey(name: String): Boolean =
        name in BackupArchiveRepository.ACCOUNT_PREFERENCE_KEYS || accountPrefixes.any { name.startsWith(it) }

    /** Sign-ins live in the same preferences file as settings, so either category needs that payload. */
    fun carriesPreferences(settings: Boolean, account: Boolean): Boolean = settings || account

    fun includes(name: String, settings: Boolean, account: Boolean): Boolean =
        when {
            name in DEVICE_BOUND_KEYS || name.startsWith("secure_store_") -> false
            isAccountKey(name) -> account
            else -> settings
        }

    /** A raw preferences file may only replace the live one when the selection filters nothing out. */
    fun replacesWholeFile(settings: Boolean, account: Boolean): Boolean = settings && account

    fun isConsistent(settings: Boolean, account: Boolean, kind: SettingsKind): Boolean =
        when (kind) {
            SettingsKind.NONE -> !settings
            SettingsKind.XML -> carriesPreferences(settings, account)
            SettingsKind.PB -> replacesWholeFile(settings, account)
        }
}

internal enum class AndroidRestoreSettingsRollbackAction {
    RESTORE_SNAPSHOT,
    DELETE_LIVE,
    FAIL_CLOSED,
}

internal object AndroidRestoreSettingsRollbackPolicy {
    fun decide(previousSettingsPresent: Boolean, snapshotExists: Boolean): AndroidRestoreSettingsRollbackAction =
        when {
            previousSettingsPresent && snapshotExists -> AndroidRestoreSettingsRollbackAction.RESTORE_SNAPSHOT
            !previousSettingsPresent && !snapshotExists -> AndroidRestoreSettingsRollbackAction.DELETE_LIVE
            else -> AndroidRestoreSettingsRollbackAction.FAIL_CLOSED
        }
}
