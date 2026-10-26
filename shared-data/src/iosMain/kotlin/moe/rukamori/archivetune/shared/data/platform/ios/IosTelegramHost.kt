package moe.rukamori.archivetune.shared.data.platform.ios

import kotlinx.cinterop.ExperimentalForeignApi
import moe.rukamori.archivetune.shared.domain.telegram.TelegramApiCredentials
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSBundle
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask

/**
 * The Telegram api_id and api_hash this build was given, or null when either is missing. Xcode writes them into the
 * Info.plist from the ARCHIVETUNE_TELEGRAM_API_ID and ARCHIVETUNE_TELEGRAM_API_HASH build settings. There is no built-in
 * fallback identity, so a build without them keeps Telegram not configured.
 */
fun iosTelegramApiCredentials(): TelegramApiCredentials? {
    val bundle = NSBundle.mainBundle
    val id = (bundle.objectForInfoDictionaryKey("ArchiveTuneTelegramApiId") as? String)?.trim()?.toIntOrNull() ?: 0
    val hash = (bundle.objectForInfoDictionaryKey("ArchiveTuneTelegramApiHash") as? String)?.trim().orEmpty()
    // An unset build setting can reach the plist as its literal reference, which is not a usable identity.
    if (id <= 0 || hash.isBlank() || hash.startsWith("$(")) return null
    return TelegramApiCredentials(id, hash)
}

/** The version string TDLib reports in the account's session list. */
fun iosApplicationVersion(): String =
    (NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String)?.takeIf { it.isNotBlank() }
        ?: "0"

/** The per-user directory for the TDLib database and files, under Application Support. */
@OptIn(ExperimentalForeignApi::class)
fun iosTelegramStorageRoot(): String {
    val manager = NSFileManager.defaultManager
    val support = (manager.URLsForDirectory(NSApplicationSupportDirectory, NSUserDomainMask).first() as NSURL).path
        ?: error("Application Support is unavailable")
    val root = "$support/telegram"
    manager.createDirectoryAtPath(root, true, null, null)
    return root
}

/** True when an earlier sign-in left a TDLib database, so starting the client restores that session. */
fun iosTelegramHasSession(storageRoot: String): Boolean =
    NSFileManager.defaultManager.fileExistsAtPath("$storageRoot/db")
