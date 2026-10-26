/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.domain.lyrics.romanization

/**
 * One application-owned language_packs directory, not a second Files/bookmark or settings registry.
 * Reads/downloads enforce the factory's archive ceiling; prepare checks space and removes staging.
 * Download closes its writer before returning/cancelling; late callbacks cannot write after cleanup.
 * promote is a same-directory atomic replacement, only after the manager has verified all readiness.
 */
interface JapanesePackIo {
    suspend fun readInstalled(): ByteArray?
    suspend fun readStaged(): ByteArray?
    suspend fun prepare()
    suspend fun download(progress: (downloaded: Long, total: Long) -> Unit)
    suspend fun promote()
    suspend fun discardStaged()
    suspend fun removeInstalled()
    suspend fun inflateRaw(compressed: ByteArray, expectedSize: Int): ByteArray
}

sealed interface JapaneseLanguagePackState {
    data object Checking : JapaneseLanguagePackState
    data object NotInstalled : JapaneseLanguagePackState
    /** -1 is connecting/unknown length/validation. Never report 100 before actual readiness. */
    data class Downloading(val progressPercent: Int = -1) : JapaneseLanguagePackState
    data object Removing : JapaneseLanguagePackState
    data class Installed(val sizeBytes: Long) : JapaneseLanguagePackState
    /** Resource IDs belong to the existing settings/UI owner; never display a native exception. */
    data class Failed(val reason: JapanesePackFailure, val removalFailed: Boolean = false) : JapaneseLanguagePackState
}

enum class JapanesePackFailure { INVALID, INSTALL, REMOVE }

internal fun japanesePackAutomaticEligible(
    region: String?, installed: Boolean, choice: Boolean?, removed: Boolean, attempted: Boolean,
): Boolean = region.equals("JP", ignoreCase = true) && !installed && choice != false && !removed && !attempted

internal fun japanesePackShouldEnable(automatic: Boolean, starting: Boolean?, current: Boolean?, removed: Boolean): Boolean =
    if (automatic) current != false && !removed else current == starting

internal fun japanesePackProgress(downloaded: Long, total: Long): Int =
    if (total > 0) ((downloaded * 100L) / total).toInt().coerceAtMost(99) else -1
