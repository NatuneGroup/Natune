package moe.rukamori.archivetune.scrobbling

import android.content.Context
import kotlinx.coroutines.flow.first
import moe.rukamori.archivetune.constants.LastFmProvider
import moe.rukamori.archivetune.utils.dataStore

/**
 * The API key and secret that a sign-in for [provider] must use. They are the same pair that
 * [LastFmServiceConfig] resolves for later scrobbling, so the session a login creates is signed with the
 * key that every later call uses. A blank key or secret means sign-in is not configured for this build.
 */
internal suspend fun lastFmSignInKeys(
    context: Context,
    provider: LastFmProvider,
): Pair<String, String> {
    val preferences = context.dataStore.data.first()
    val credentials = LastFmServiceConfig.credentialsForProvider(preferences, provider)
    val config =
        LastFmServiceConfig.fromValues(
            provider = provider,
            customEndpoint = "",
            apiKeyOverride = credentials.apiKey,
            secretOverride = credentials.secret,
        )
    return config.apiKey to config.secret
}
