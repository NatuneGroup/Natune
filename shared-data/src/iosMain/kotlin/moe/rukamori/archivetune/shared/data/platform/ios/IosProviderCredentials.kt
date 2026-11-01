package moe.rukamori.archivetune.shared.data.platform.ios

import moe.rukamori.archivetune.shared.data.lyrics.LyprioEndpoint
import moe.rukamori.archivetune.shared.data.provider.TidalClientCredentials
import platform.Foundation.NSBundle

/**
 * Provider credentials of the iOS host, read from the Info.plist keys Xcode writes from the
 * ARCHIVETUNE_* build settings (same names the desktop host reads from its environment). A blank or
 * unset answer means the feature is not configured; no value is ever logged or written to settings.
 * An unset build setting can reach the plist as its literal `$(...)` reference, which is not usable.
 */
fun iosBuildSetting(name: String): String {
    val value = (NSBundle.mainBundle.objectForInfoDictionaryKey(name) as? String)?.trim().orEmpty()
    return if (value.isEmpty() || value.startsWith("$(")) "" else value
}

fun iosTidalClient(idName: String, secretName: String): TidalClientCredentials? {
    val id = iosBuildSetting(idName)
    val secret = iosBuildSetting(secretName)
    if (id.isBlank() || secret.isBlank()) return null
    return TidalClientCredentials(id, secret)
}

object IosProviderCredentials {
    /**
     * The TIDAL device-flow client. A dedicated device client wins; otherwise the OAuth client that
     * issued the "oauth" sessions is used, so a session copied from Android can still be refreshed
     * with the same client.
     */
    val tidalDeviceClient: TidalClientCredentials?
        get() = iosTidalClient("ArchiveTuneTidalDeviceClientId", "ArchiveTuneTidalDeviceClientSecret")
            ?: iosTidalClient("ArchiveTuneTidalClientId", "ArchiveTuneTidalClientSecret")

    /** The TIDAL PKCE browser sign-in client. Without it the flow reports itself unavailable. */
    val tidalPkceClient: TidalClientCredentials?
        get() = iosTidalClient("ArchiveTuneTidalPkceClientId", "ArchiveTuneTidalPkceClientSecret")

    /**
     * The registered public OAuth client id of the Librespot Spotify streaming sign-in — a PKCE
     * public client, never a secret. Blank means the flow reports itself not configured.
     */
    val spotifyOauthClientId: String get() = iosBuildSetting("ArchiveTuneSpotifyOauthClientId")

    /**
     * The ArchivePool deployment of the Playback Pool, without a trailing slash, or null when the
     * feed is turned off. A blank value takes the same public default the other hosts use;
     * "off" turns the feed off for this launch.
     */
    val poolBaseUrl: String?
        get() {
            val configured = iosBuildSetting("ArchiveTuneSourceProviderUrl")
            if (configured.equals("off", ignoreCase = true)) return null
            return configured.ifBlank { DEFAULT_POOL_BASE_URL }.trimEnd('/').takeIf { it.startsWith("https://") }
        }

    /** The per-app pool read key, sent as a bearer token to every pool request. Blank means none. */
    val poolReadKey: String get() = iosBuildSetting("ArchiveTuneSourceProviderKey")

    /** The legacy base64 AES-256 key for pool deployments older than the v2 feed. Blank means none. */
    val poolLegacyClientKey: String get() = iosBuildSetting("ArchiveTunePoolClientKey")

    /** The JioSaavn media URL decoder key, exactly 8 bytes, or null when the source is not configured. */
    val jioSaavnDesKey: ByteArray?
        get() = iosBuildSetting("ArchiveTuneJioSaavnDesKey").encodeToByteArray().takeIf { it.size == 8 }

    /** The Cloudflare Turnstile sitekey of the Amazon authorization challenge. Blank means not configured. */
    val amazonTurnstileSitekey: String get() = iosBuildSetting("ArchiveTuneAmazonTurnstileSitekey")

    /** The HTTPS origin the Turnstile sitekey is locked to, without a trailing slash. Blank means not configured. */
    val amazonTurnstileOrigin: String get() = iosBuildSetting("ArchiveTuneAmazonTurnstileOrigin").trimEnd('/')

    /**
     * The built-in Lyprio lyrics server pair; both values must be present for [lyprioEndpoint] to
     * exist, and a user's own server entry in settings always wins over it. The key is never logged
     * or written to settings.
     */
    val lyprioBaseUrl: String get() = iosBuildSetting("ArchiveTuneLyprioBaseUrl")

    val lyprioHmacKey: String get() = iosBuildSetting("ArchiveTuneLyprioHmacKey")

    val lyprioEndpoint: LyprioEndpoint? get() = LyprioEndpoint.parse(lyprioBaseUrl, lyprioHmacKey)

    private const val DEFAULT_POOL_BASE_URL = "https://archivepool.vercel.app"
}
