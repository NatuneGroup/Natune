package moe.rukamori.archivetune.discord

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** OAuth Bearer credentials enter the documented Social SDK API, never Gateway IDENTIFY. */
internal object DiscordSocialSdkBackend : DiscordPresenceBackend {
    private val mutex = Mutex()
    private var activeToken: String? = null

    override val available: Boolean get() = DiscordNativeSdkBridge.available
    override val started: Boolean get() = DiscordNativeSdkBridge.ready.value

    override suspend fun update(accessToken: String, activity: DiscordPresenceActivity): Result<Unit> =
        mutex.withLock {
            try {
                if (activeToken != accessToken || !started) {
                    DiscordNativeSdkBridge.updateToken(accessToken)
                    DiscordNativeSdkBridge.awaitReady()
                    activeToken = accessToken
                }
                DiscordNativeSdkBridge.updatePresence(activity)
                Result.success(Unit)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Result.failure(error)
            }
        }

    override suspend fun clear(): Result<Unit> =
        mutex.withLock {
            if (!started) return@withLock Result.success(Unit)
            try {
                DiscordNativeSdkBridge.clearPresence()
                Result.success(Unit)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Result.failure(error)
            }
        }

    override suspend fun close(): Result<Unit> =
        mutex.withLock {
            activeToken = null
            DiscordNativeSdkBridge.close()
            Result.success(Unit)
        }
}
