/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.shared.domain.settings

/** Scrobbling service family. The enum names are the persisted [LastFMProviderKey] values. */
enum class LastFmProvider { LASTFM, LIBREFM, CUSTOM }

/**
 * Baseline scrobbling defaults. Build-time service credentials are supplied by the host to the policy functions
 * and are never stored or embedded in this module.
 */
object LastFmDefaults {
    const val DEFAULT_API_ENDPOINT = "https://ws.audioscrobbler.com/2.0/"
    const val LIBREFM_API_ENDPOINT = "https://libre.fm/2.0/"

    /**
     * Public compatibility identifiers sent to endpoints that accept any key. They are not account credentials;
     * real Last.fm always uses the host-supplied or user-supplied key.
     */
    const val COMPAT_API_KEY = "archivetune"
    const val COMPAT_SECRET = "archivetune"

    const val DEFAULT_SCROBBLE_DELAY_PERCENT = 0.5f
    const val DEFAULT_SCROBBLE_MIN_SONG_DURATION = 30
    const val DEFAULT_SCROBBLE_DELAY_SECONDS = 180
    val MIN_SONG_DURATION_RANGE: IntRange = 10..60
    val DELAY_PERCENT_RANGE: ClosedFloatingPointRange<Float> = 0.3f..0.95f
    val DELAY_SECONDS_RANGE: IntRange = 30..360
}

data class LastFmApiCredentials(val apiKey: String = "", val secret: String = "")

/** Pure port of the baseline service config; [endpointValid] is false only for an unusable custom endpoint. */
data class LastFmServiceConfig(
    val provider: LastFmProvider,
    val customEndpoint: String,
    val apiKeyOverride: String,
    val secretOverride: String,
    val endpoint: String,
    val apiKey: String,
    val secret: String,
    val endpointValid: Boolean,
) {
    val initialized: Boolean
        get() = endpointValid && apiKey.isNotBlank() && secret.isNotBlank()
}

fun PreferenceSnapshot.lastFmLoggedIn(): Boolean = !this[LastFMSessionKey].isNullOrBlank()

fun PreferenceSnapshot.lastFmProvider(): LastFmProvider =
    this[LastFMProviderKey].toLastFmProvider(LastFmProvider.LASTFM)

/**
 * Provider-scoped overrides. Before the one-time migration, the legacy shared pair belongs to the selected
 * non-Last.fm provider, which keeps an upgraded store readable without a startup write.
 */
fun PreferenceSnapshot.lastFmCredentialsFor(provider: LastFmProvider): LastFmApiCredentials {
    val scoped = when (provider) {
        LastFmProvider.LASTFM -> LastFmApiCredentials(
            this[LastFMApiKeyOverrideKey].orEmpty(),
            this[LastFMSecretOverrideKey].orEmpty(),
        )
        LastFmProvider.LIBREFM -> LastFmApiCredentials(
            this[LibreFMApiKeyOverrideKey].orEmpty(),
            this[LibreFMSecretOverrideKey].orEmpty(),
        )
        LastFmProvider.CUSTOM -> LastFmApiCredentials(
            this[CustomScrobbleApiKeyOverrideKey].orEmpty(),
            this[CustomScrobbleSecretOverrideKey].orEmpty(),
        )
    }
    val migrated = this[LastFMCredentialsMigratedKey] ?: false
    return if (!migrated && provider == lastFmProvider() && provider != LastFmProvider.LASTFM) {
        LastFmApiCredentials(this[LastFMApiKeyOverrideKey].orEmpty(), this[LastFMSecretOverrideKey].orEmpty())
    } else {
        scoped
    }
}

/** Host supplies its build-time default key and secret; blank defaults fall back to the user's own override. */
fun PreferenceSnapshot.lastFmServiceConfig(defaultApiKey: String, defaultSecret: String): LastFmServiceConfig {
    val provider = lastFmProvider()
    val credentials = lastFmCredentialsFor(provider)
    return lastFmServiceConfigFromValues(
        provider = provider,
        customEndpoint = this[LastFMCustomEndpointKey].orEmpty(),
        apiKeyOverride = credentials.apiKey,
        secretOverride = credentials.secret,
        defaultApiKey = defaultApiKey,
        defaultSecret = defaultSecret,
    )
}

fun lastFmServiceConfigFromValues(
    provider: LastFmProvider,
    customEndpoint: String,
    apiKeyOverride: String,
    secretOverride: String,
    defaultApiKey: String,
    defaultSecret: String,
): LastFmServiceConfig {
    val normalizedCustom = normalizeLastFmEndpointOrNull(customEndpoint)
    val endpoint = when (provider) {
        LastFmProvider.LASTFM -> LastFmDefaults.DEFAULT_API_ENDPOINT
        LastFmProvider.LIBREFM -> LastFmDefaults.LIBREFM_API_ENDPOINT
        LastFmProvider.CUSTOM -> normalizedCustom ?: LastFmDefaults.DEFAULT_API_ENDPOINT
    }
    val endpointValid = provider != LastFmProvider.CUSTOM || normalizedCustom != null
    val apiKey = when (provider) {
        LastFmProvider.LASTFM -> defaultApiKey.ifBlank { apiKeyOverride }
        LastFmProvider.LIBREFM, LastFmProvider.CUSTOM -> apiKeyOverride.ifBlank { LastFmDefaults.COMPAT_API_KEY }
    }
    val secret = when (provider) {
        LastFmProvider.LASTFM -> defaultSecret.ifBlank { secretOverride }
        LastFmProvider.LIBREFM, LastFmProvider.CUSTOM -> secretOverride.ifBlank { LastFmDefaults.COMPAT_SECRET }
    }
    return LastFmServiceConfig(
        provider = provider,
        customEndpoint = customEndpoint,
        apiKeyOverride = apiKeyOverride,
        secretOverride = secretOverride,
        endpoint = endpoint,
        apiKey = apiKey,
        secret = secret,
        endpointValid = endpointValid,
    )
}

/** Scrobbling needs a signed-in session and a usable configured service. */
fun PreferenceSnapshot.lastFmCanEnableScrobbling(defaultApiKey: String, defaultSecret: String): Boolean {
    val config = lastFmServiceConfig(defaultApiKey, defaultSecret)
    return lastFmLoggedIn() && config.initialized && config.endpointValid
}

/**
 * Common-code equivalent of the baseline endpoint normalization for ASCII http(s) URLs: no whitespace, query or
 * fragment; a non-empty ASCII host or bracketed IPv6 literal; default path `/2.0`; exactly one trailing slash.
 * Returns null for any endpoint the baseline would reject.
 */
fun normalizeLastFmEndpointOrNull(endpoint: String): String? {
    val input = endpoint.trim()
    if (input.isEmpty() || input.any { it.isWhitespace() || it == '?' || it == '#' }) return null
    val schemeEnd = input.indexOf("://")
    if (schemeEnd <= 0) return null
    val scheme = input.substring(0, schemeEnd).lowercase()
    if (scheme != "http" && scheme != "https") return null
    val afterScheme = input.substring(schemeEnd + 3)
    val authorityEnd = afterScheme.indexOf('/').let { if (it < 0) afterScheme.length else it }
    val authority = afterScheme.substring(0, authorityEnd)
    val rawPath = afterScheme.substring(authorityEnd)
    val at = authority.lastIndexOf('@')
    val userInfo = if (at >= 0) authority.substring(0, at) else null
    val hostPort = if (at >= 0) authority.substring(at + 1) else authority
    val (host, port) = splitLastFmHostPort(hostPort) ?: return null
    val path = rawPath.takeIf { it.isNotBlank() && it != "/" }?.trimEnd('/') ?: "/2.0"
    return buildString {
        append(scheme).append("://")
        if (userInfo != null) append(userInfo).append('@')
        append(host)
        if (port != null) append(':').append(port)
        append(path).append('/')
    }
}

private fun splitLastFmHostPort(hostPort: String): Pair<String, String?>? {
    val host: String
    val portPart: String?
    if (hostPort.startsWith("[")) {
        val close = hostPort.indexOf(']')
        if (close < 0) return null
        host = hostPort.substring(0, close + 1)
        if (!host.substring(1, host.length - 1).all { it.isLetterOrDigit() && it.code < 128 || it == ':' || it == '.' }) {
            return null
        }
        val tail = hostPort.substring(close + 1)
        portPart = when {
            tail.isEmpty() -> null
            tail.startsWith(":") -> tail.substring(1)
            else -> return null
        }
    } else {
        val colon = hostPort.lastIndexOf(':')
        host = if (colon >= 0) hostPort.substring(0, colon) else hostPort
        portPart = if (colon >= 0) hostPort.substring(colon + 1) else null
        if (!isLastFmHostName(host)) return null
    }
    val port = when {
        portPart == null || portPart.isEmpty() -> null
        portPart.all(Char::isDigit) && (portPart.toIntOrNull() ?: -1) in 0..65535 -> portPart
        else -> return null
    }
    return host to port
}

private fun isLastFmHostName(host: String): Boolean {
    if (host.isEmpty() || host.length > 253) return false
    return host.split('.').all { label ->
        label.isNotEmpty() && label.length <= 63 && label.first() != '-' && label.last() != '-' &&
            label.all { (it.isLetterOrDigit() && it.code < 128) || it == '-' }
    }
}

fun PreferenceEditor.setLastFmScrobbling(enabled: Boolean) {
    this[EnableLastFMScrobblingKey] = enabled
    if (!enabled) this[LastFMUseNowPlaying] = false
}

fun PreferenceEditor.setLastFmNowPlaying(enabled: Boolean) {
    this[LastFMUseNowPlaying] = enabled
}

/** Removes only the signed-in identity; the service owner clears its in-memory session separately. */
fun PreferenceEditor.clearLastFmSession() {
    this[LastFMUsernameKey] = null
    this[LastFMSessionKey] = null
}

/**
 * Baseline `saveServiceConfig`. Returns false without writing when a custom endpoint is unusable. Any change of
 * provider, endpoint or overrides clears the session and turns scrobbling and now-playing off.
 */
fun PreferenceEditor.saveLastFmServiceConfig(
    provider: LastFmProvider,
    customEndpoint: String,
    apiKeyOverride: String,
    secretOverride: String,
): Boolean {
    val normalizedEndpoint = if (provider == LastFmProvider.CUSTOM) {
        normalizeLastFmEndpointOrNull(customEndpoint) ?: return false
    } else {
        customEndpoint.trim()
    }
    val nextApiKey = apiKeyOverride.trim()
    val nextSecret = secretOverride.trim()
    val previous = snapshot().lastFmServiceConfig(defaultApiKey = "", defaultSecret = "")
    val changed = previous.provider != provider ||
        previous.customEndpoint != normalizedEndpoint ||
        previous.apiKeyOverride != nextApiKey ||
        previous.secretOverride != nextSecret

    this[LastFMProviderKey] = provider.name
    this[LastFMCustomEndpointKey] = normalizedEndpoint
    when (provider) {
        LastFmProvider.LASTFM -> {
            this[LastFMApiKeyOverrideKey] = nextApiKey
            this[LastFMSecretOverrideKey] = nextSecret
        }
        LastFmProvider.LIBREFM -> {
            this[LibreFMApiKeyOverrideKey] = nextApiKey
            this[LibreFMSecretOverrideKey] = nextSecret
        }
        LastFmProvider.CUSTOM -> {
            this[CustomScrobbleApiKeyOverrideKey] = nextApiKey
            this[CustomScrobbleSecretOverrideKey] = nextSecret
        }
    }
    this[LastFMCredentialsMigratedKey] = true
    if (changed) {
        clearLastFmSession()
        this[EnableLastFMScrobblingKey] = false
        this[LastFMUseNowPlaying] = false
    }
    return true
}

/** Baseline one-time move of the legacy shared override pair into the selected provider's scoped keys. */
fun PreferenceEditor.migrateLastFmSharedCredentials() {
    if (this[LastFMCredentialsMigratedKey] == true) return
    val provider = this[LastFMProviderKey].toLastFmProvider(LastFmProvider.LASTFM)
    val apiKey = this[LastFMApiKeyOverrideKey].orEmpty()
    val secret = this[LastFMSecretOverrideKey].orEmpty()
    when (provider) {
        LastFmProvider.LASTFM -> Unit
        LastFmProvider.LIBREFM -> {
            this[LibreFMApiKeyOverrideKey] = apiKey
            this[LibreFMSecretOverrideKey] = secret
            this[LastFMApiKeyOverrideKey] = null
            this[LastFMSecretOverrideKey] = null
        }
        LastFmProvider.CUSTOM -> {
            this[CustomScrobbleApiKeyOverrideKey] = apiKey
            this[CustomScrobbleSecretOverrideKey] = secret
            this[LastFMApiKeyOverrideKey] = null
            this[LastFMSecretOverrideKey] = null
        }
    }
    this[LastFMCredentialsMigratedKey] = true
}

/**
 * Completes a successful web sign-in in one edit. The provider is pinned first, so a change of service clears any
 * session that belonged to the previous one, then the new identity is stored. Returns false without writing when the
 * target endpoint is unusable.
 */
fun PreferenceEditor.commitLastFmSignIn(provider: LastFmProvider, username: String, sessionKey: String): Boolean {
    val credentials = snapshot().lastFmCredentialsFor(provider)
    val endpoint = this[LastFMCustomEndpointKey].orEmpty()
    if (!saveLastFmServiceConfig(provider, endpoint, credentials.apiKey, credentials.secret)) return false
    this[LastFMUsernameKey] = username
    this[LastFMSessionKey] = sessionKey
    return true
}

private fun String?.toLastFmProvider(default: LastFmProvider): LastFmProvider =
    LastFmProvider.entries.firstOrNull { it.name == this } ?: default
