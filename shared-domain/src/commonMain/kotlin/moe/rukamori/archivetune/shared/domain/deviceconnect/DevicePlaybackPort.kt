package moe.rukamori.archivetune.shared.domain.deviceconnect

/**
 * The host's live queue and player as Device Connect sees them. The shared [QueueDevicePlaybackPort]
 * implements this over the ordinary QueueController/MusicPlayerController, so no host opens a second
 * player or mirror queue.
 */
interface DevicePlaybackPort {
    /** Null when this device has no queue to transfer. */
    suspend fun capture(): DevicePlaybackSnapshot?

    /** Applies a transfer snapshot. The caller has already resolved any "continue playback?" prompt. */
    suspend fun apply(snapshot: DevicePlaybackSnapshot): DeviceConnectError?

    /** Captures the final local snapshot, then silences local output before a transfer becomes remote. */
    suspend fun pauseForRemote(): DevicePlaybackSnapshot?

    /** Applies a pulled-back snapshot as a normal local load. */
    suspend fun resume(snapshot: DevicePlaybackSnapshot): DeviceConnectError?

    /** Validates and applies one remote command. Null means applied. */
    suspend fun command(message: DeviceSyncMessage): DeviceConnectError?

    /** Host volume, where the platform exposes one. Null leaves VOLUME unsupported. */
    suspend fun setVolume(volume: Float): Boolean = false

    /** The host may render a remote-session banner or change its output route here. */
    fun onRemoteSessionChanged(session: DeviceSessionState?) = Unit
}
