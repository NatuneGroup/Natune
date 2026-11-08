/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.discord

import kotlinx.coroutines.CancellationException

object DiscordSocialPresenceClient {
    private val backend: DiscordPresenceBackend
        get() = if (DiscordSocialSdkBackend.available) DiscordSocialSdkBackend else NoDiscordPresenceBackend

    val isAvailable: Boolean get() = backend.available
    val isStarted: Boolean get() = backend.started

    suspend fun updatePresence(accessToken: String, activity: DiscordPresenceActivity): Result<Unit> {
        if (accessToken.isBlank()) return Result.failure(DiscordAuthorizationRequiredException())
        return backend.update(accessToken.trim(), activity)
    }

    suspend fun clearPresence(accessToken: String? = null): Result<Unit> = backend.clear()

    suspend fun close(): Result<Unit> = backend.close()

    fun setOnTransportInvalidated(listener: ((String) -> Unit)?) {
        DiscordNativeSdkBridge.onDisconnected = listener?.let { callback -> { callback("social_sdk_disconnected") } }
    }

    // The Social SDK owns reconnect policy, so playback-driven updates need no extra backoff.
    fun reconnectBackoffRemainingMs(): Long = 0L

    suspend fun authorize(context: android.content.Context): Result<DiscordAuthSession> {
        if (!isAvailable) return Result.failure(DiscordSdkUnavailableException())
        return try {
            val grant = DiscordNativeSdkBridge.authorize()
            val session = DiscordOAuthRepository.storeSdkGrant(context, grant)
            DiscordNativeSdkBridge.updateToken(session.accessToken)
            DiscordNativeSdkBridge.awaitReady()
            Result.success(session)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    internal suspend fun refreshAuthorization(context: android.content.Context, refreshToken: String): DiscordAuthSession? {
        if (!isAvailable) return null
        return try {
            DiscordOAuthRepository.storeSdkGrant(context, DiscordNativeSdkBridge.refresh(refreshToken))
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
    }
}

class DiscordAuthorizationRequiredException : IllegalStateException("Link and authorize a Discord account first")
