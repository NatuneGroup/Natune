package moe.rukamori.archivetune.discord

/** Playback uses this contract; transport and SDK availability stay outside MusicService policy. */
internal interface DiscordPresenceBackend {
    val available: Boolean
    val started: Boolean
    suspend fun update(accessToken: String, activity: DiscordPresenceActivity): Result<Unit>
    suspend fun clear(): Result<Unit>
    suspend fun close(): Result<Unit>
}

internal object NoDiscordPresenceBackend : DiscordPresenceBackend {
    override val available = false
    override val started = false
    override suspend fun update(accessToken: String, activity: DiscordPresenceActivity): Result<Unit> =
        Result.failure(DiscordSdkUnavailableException())
    override suspend fun clear(): Result<Unit> = Result.success(Unit)
    override suspend fun close(): Result<Unit> = Result.success(Unit)
}

internal class DiscordSdkUnavailableException : IllegalStateException("Discord Social SDK is not available in this build")
