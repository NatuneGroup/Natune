package moe.rukamori.archivetune.shared.data.platform.ios

import moe.rukamori.archivetune.shared.data.provider.TidalClientCredentials

/**
 * The TIDAL OAuth clients this build carries, read from Info.plist keys mapped to the ARCHIVETUNE_TIDAL_* build
 * settings — the same pattern as the Telegram api identity. There is no built-in client: a build without both the id
 * and the secret reports that sign-in path as not configured, exactly as the desktop does without its environment.
 * Both reads go through [iosTidalClient], which also refuses an unexpanded `$(...)` build-setting reference.
 */

/** The device-flow client: the device names first, then the unprefixed client names the Android build reads. */
fun iosTidalDeviceClient(): TidalClientCredentials? =
    iosTidalClient("ArchiveTuneTidalDeviceClientId", "ArchiveTuneTidalDeviceClientSecret")
        ?: iosTidalClient("ArchiveTuneTidalClientId", "ArchiveTuneTidalClientSecret")

/** The PKCE web-login client. */
fun iosTidalPkceClient(): TidalClientCredentials? =
    iosTidalClient("ArchiveTuneTidalPkceClientId", "ArchiveTuneTidalPkceClientSecret")
