package moe.rukamori.archivetune.audiosource

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import moe.rukamori.archivetune.constants.*
import moe.rukamori.archivetune.tidal.TidalAudioProvider
import moe.rukamori.archivetune.qobuz.QobuzAudioProvider
import moe.rukamori.archivetune.deezer.DeezerAudioProvider
import moe.rukamori.archivetune.applemusic.AppleMusicAudioProvider
import moe.rukamori.archivetune.utils.PoolAccountManager
import moe.rukamori.archivetune.utils.get

/** Built-in sources do not need modules; account-only routes do need an available account. */
internal fun availableBuiltinSource(source: AudioSourceType, dataStore: DataStore<Preferences>, preferences: Preferences? = null): Boolean {
    fun text(key: Preferences.Key<String>) =
        (if (preferences != null) preferences[key].orEmpty() else dataStore.get(key, "")).trim()
    return when (source) {
        AudioSourceType.TIDAL -> text(TidalAccessTokenKey).isNotEmpty() ||
            text(TidalInstancesKey).isNotEmpty() || TidalAudioProvider.activeInstanceUrls.isNotEmpty() || PoolAccountManager.tidalAccounts().isNotEmpty()
        AudioSourceType.QOBUZ -> runCatching { org.json.JSONArray(text(QobuzTokensKey)).length() > 0 }.getOrDefault(false) || text(QobuzInstancesKey).isNotEmpty() || QobuzAudioProvider.hasBackends()
        AudioSourceType.DEEZER -> text(DeezerArlKey).isNotEmpty() || text(DeezerInstancesKey).isNotEmpty() || DeezerAudioProvider.hasBackends()
        AudioSourceType.APPLE -> AppleMusicAudioProvider.isAvailable() || PoolAccountManager.appleMusicAccounts().isNotEmpty()
        AudioSourceType.QQ -> text(moe.rukamori.archivetune.constants.QqMusicUinKey).isNotEmpty() &&
            text(moe.rukamori.archivetune.constants.QqMusicMusickeyKey).isNotEmpty()
        AudioSourceType.AMAZON -> AmazonInstances.merge(
            local = AmazonInstances.parseBaseUrls(text(AmazonInstancesKey)), localBypassToken = text(AmazonBypassTokenKey),
            localTurnstileJwt = text(AmazonTurnstileJwtKey).takeIf { AmazonInstances.isJwtUsable(
                (preferences?.get(AmazonTurnstileJwtExpiryMsKey) ?: dataStore.get(AmazonTurnstileJwtExpiryMsKey, 0L)).takeIf { expiry -> expiry > 0L },
                System.currentTimeMillis()) }, pooled = PoolAccountManager.amazonInstances(), nowMs = System.currentTimeMillis()).isNotEmpty()
        else -> true
    }
}
