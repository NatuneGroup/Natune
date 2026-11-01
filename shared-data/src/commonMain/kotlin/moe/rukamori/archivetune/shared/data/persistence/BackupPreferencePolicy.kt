package moe.rukamori.archivetune.shared.data.persistence

enum class BackupCategory { LIBRARY, ACCOUNT, SETTINGS }

/** Host vault ciphertext/keys are NEVER preference values and must never enter an archive. */
object BackupPreferencePolicy {
    // Prefix of the vault-backed provider account slots (id, generation, cookies per provider and scope).
    private const val ACCOUNT_SLOT_PREFIX = "account."
    private val accountKeys = setOf(
        "innerTubeCookie", "visitorData", "dataSyncId", "poToken", "poTokenGvs", "poTokenPlayer",
        "poTokenSourceUrl", "webClientPoTokenEnabled", "accountName", "accountEmail", "accountChannelHandle",
        "useLoginForBrowse", "lastfmSession", "lastfmUsername", "lastfmProvider", "lastfmCustomEndpoint",
        "lastfmApiKeyOverride", "lastfmSecretOverride", "librefmApiKeyOverride", "librefmSecretOverride",
        "customScrobbleApiKeyOverride", "customScrobbleSecretOverride", "lastfmCredentialsMigrated",
        "listenbrainz_token", "discordToken", "discordRefreshToken", "discordTokenExpiresAt", "discordUsername",
        "discordName", "discordAvatarUrl", "poolTidalAccounts", "poolQobuzAccounts", "poolDeezerAccounts",
        "poolAppleMusicAccounts", "poolAmazonAccounts", "poolAccountsLastRefreshAtMillis", "proxyUsername",
        "proxyPassword", "spotify_sp_dc", "spotify_sp_key", "spotify_access_token", "spotify_access_token_expires_at",
        "spotify_account_name", "spotify_account_avatar_url", "tidalAccessToken", "tidalRefreshToken", "tidalTokenExpiry",
        "tidalSubscription", "tidalAuthFlow", "tidalCountryCode", "tidalUserId", "tidalNeedsRelogin", "tidal_account_name",
        "tidalCookie", "qobuzTokens",
        "spotifyStreamSpDc", "spotifyStreamSpKey", "spotifyStreamAccessToken", "spotifyStreamAccessTokenExpiresAt",
        "spotifyStreamUserId", "spotifyStreamAccountName", "innerTubeOAuthToken", "innerTubeOAuthRefreshToken",
        "accountImageUrl", "savedAccounts", "deezerArl", "deezerAccountName", "deezerAccountPremium",
        "amazonTurnstileJwt", "amazonTurnstileJwtExpiryMs", "amazonBypassToken", "paxsenixApiKey", "ai_api_key",
        "deeplApiKey", "openRouterApiKey", "telegramAccountName", "telegramAccountPhone",
        // Logical vault secrets for Apple Music, QQ Music and Listen Together. They must stay ACCOUNT-only
        // so a SETTINGS-only export can never carry them, even though they are ordinary preference names.
        "appleMusicMediaUserToken", "appleMusicDevToken", "qqMusicMusickey", "qqMusicCredential",
        "listenTogetherSessionToken",
    )
    private val deviceKeys = setOf("scheduledBackupEnabled", "scheduledBackupDirectoryUri", "scheduledBackupDirectoryName",
        "spotifyStreamDeviceId",
        // Retired preferences whose leftovers never export: the user-entered Lyprio server pair is gone
        // (the provider is build-configured now) and its signing key was a credential.
        "lyprioEndpoint", "lyprioSigningKey")

    fun includes(name: String, selected: Set<BackupCategory>): Boolean {
        if (name in deviceKeys) return false
        // Vault blobs cannot be made portable simply by selecting ACCOUNT, so a raw reference never exports.
        if (name.startsWith("secure_store_")) return false
        // The vault-backed account store routes each provider account's id, generation and cookies under this prefix.
        // They are account state, so a SETTINGS-only export must never fall through and carry them.
        val account = name in accountKeys || name.startsWith(ACCOUNT_SLOT_PREFIX) ||
            name.startsWith("spotify_stream_") || name.startsWith("SpotifyStream")
        return if (account) BackupCategory.ACCOUNT in selected else BackupCategory.SETTINGS in selected
    }
}
