package moe.rukamori.archivetune.shared.data.platform.ios

import moe.rukamori.archivetune.shared.data.provider.TidalClientCredentials
import platform.Foundation.NSBundle

/**
 * The TIDAL OAuth clients this build carries, read from Info.plist keys mapped to the ARCHIVETUNE_TIDAL_* build
 * settings — the same pattern as the Telegram api identity. There is no built-in client: a build without both the id
 * and the secret reports that sign-in path as not configured, exactly as the desktop does without its environment.
 */

/** The device-flow client: the device names first, then the unprefixed client names the Android build reads. */
fun iosTidalDeviceClient(): TidalClientCredentials? =
    iosBuildClient("ArchiveTuneTidalDeviceClientId", "ArchiveTuneTidalDeviceClientSecret")
        ?: iosBuildClient("ArchiveTuneTidalClientId", "ArchiveTuneTidalClientSecret")

/** The PKCE web-login client. */
fun iosTidalPkceClient(): TidalClientCredentials? =
    iosBuildClient("ArchiveTuneTidalPkceClientId", "ArchiveTuneTidalPkceClientSecret")

private fun iosBuildClient(idKey: String, secretKey: String): TidalClientCredentials? {
    val bundle = NSBundle.mainBundle
    val id = (bundle.objectForInfoDictionaryKey(idKey) as? String)?.trim().orEmpty()
    val secret = (bundle.objectForInfoDictionaryKey(secretKey) as? String)?.trim().orEmpty()
    return if (id.isBlank() || secret.isBlank()) null else TidalClientCredentials(id, secret)
}
