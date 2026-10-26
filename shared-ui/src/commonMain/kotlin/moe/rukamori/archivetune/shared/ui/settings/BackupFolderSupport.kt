package moe.rukamori.archivetune.shared.ui.settings

/**
 * The cloud or local provider a picked SAF tree URI belongs to. The Drive row names it so a local folder is never shown
 * as a cloud destination.
 */
internal enum class BackupFolderProvider { DRIVE, DROPBOX, NEXTCLOUD, ONEDRIVE, LOCAL, UNKNOWN }

/** The Android local-storage DocumentsProvider. A pick under it writes to the device, not to the cloud. */
private const val LOCAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

/** Known DocumentsProvider authorities. Any other authority is an unknown cloud folder, never local storage. */
private val PROVIDER_AUTHORITIES: Map<String, BackupFolderProvider> = mapOf(
    "com.google.android.apps.docs.storage" to BackupFolderProvider.DRIVE,
    "com.google.android.apps.docs.storage.legacy" to BackupFolderProvider.DRIVE,
    "com.dropbox.android.providers.DocumentsProvider" to BackupFolderProvider.DROPBOX,
    "org.nextcloud.documents" to BackupFolderProvider.NEXTCLOUD,
    "org.nextcloud.android.files.documentsStorageAccess" to BackupFolderProvider.NEXTCLOUD,
    "com.microsoft.skydrive.content.SkyDriveProvider" to BackupFolderProvider.ONEDRIVE,
    "com.onedrive.android.content.OneDriveDocumentsProvider" to BackupFolderProvider.ONEDRIVE,
    LOCAL_STORAGE_AUTHORITY to BackupFolderProvider.LOCAL,
)

/**
 * The authority of a `content://` tree URI, or null when the string has none. It is parsed here so the common code does
 * not need an Android Uri type, and it matches what the platform reports for the same string.
 */
internal fun folderAuthority(uri: String): String? {
    if (!uri.contains("://")) return null
    return uri.substringAfter("://").substringBefore('/').takeIf { it.isNotEmpty() }
}

/** The provider for [uri]. A URI with no recognised authority is treated as an unknown cloud folder. */
internal fun backupFolderProvider(uri: String): BackupFolderProvider {
    val authority = folderAuthority(uri) ?: return BackupFolderProvider.UNKNOWN
    return PROVIDER_AUTHORITIES[authority] ?: BackupFolderProvider.UNKNOWN
}

/** True when [uri] is a local-storage pick. The page asks the user to confirm before it saves such a folder. */
internal fun isLocalStorageFolder(uri: String): Boolean = folderAuthority(uri) == LOCAL_STORAGE_AUTHORITY
