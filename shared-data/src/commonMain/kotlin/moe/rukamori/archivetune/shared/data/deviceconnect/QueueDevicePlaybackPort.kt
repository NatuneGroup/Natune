package moe.rukamori.archivetune.shared.data.deviceconnect

import moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceCommandIntent
import moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceCommandPolicy
import moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceConnectError
import moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceConnectErrorCode
import moe.rukamori.archivetune.shared.domain.deviceconnect.DevicePlaybackPort
import moe.rukamori.archivetune.shared.domain.deviceconnect.DevicePlaybackSnapshot
import moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceQueueHash
import moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceSessionState
import moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceSyncMessage
import moe.rukamori.archivetune.shared.domain.deviceconnect.toContentOccurrenceOrNull
import moe.rukamori.archivetune.shared.domain.deviceconnect.toDeviceQueueEntry
import moe.rukamori.archivetune.shared.domain.queue.QueueRepeatMode
import moe.rukamori.archivetune.shared.domain.queue.UserQueuedKind
import moe.rukamori.archivetune.shared.domain.repository.QueueController
import moe.rukamori.archivetune.shared.domain.repository.QueueEditingCommands
import kotlin.time.Clock

/**
 * The one command mapping shared by Android, desktop and iOS. It applies the same target-authoritative
 * rules and queue calls on every host; a host only supplies its existing QueueController and an optional
 * platform volume/repeat bridge.
 */
class QueueDevicePlaybackPort(
    private val queue: QueueController,
    private val editing: QueueEditingCommands? = null,
    private val nowEpochMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val repeatMode: () -> QueueRepeatMode = { QueueRepeatMode.OFF },
    private val setRepeatMode: ((QueueRepeatMode) -> Unit)? = null,
    private val setPlatformVolume: ((Float) -> Unit)? = null,
) : DevicePlaybackPort {
    override suspend fun capture(): DevicePlaybackSnapshot? {
        val state = queue.state.value
        val player = queue.player.snapshot.value ?: return null
        val entries = state.entries.map { it.toDeviceQueueEntry() }
        val current = state.currentOccurrenceId
        val position = queue.player.positionMs() ?: player.positionMs
        return DevicePlaybackSnapshot(
            sessionId = "",
            queueRevision = state.revision,
            trackRevision = player.playbackGeneration,
            stateRevision = state.revision,
            entries = entries,
            currentOccurrenceId = current,
            playOrder = state.playOrder,
            shuffle = state.shuffleEnabled,
            repeatMode = repeatMode().storedValue,
            playing = player.playWhenReady && !player.isEnded,
            positionMs = position.coerceAtLeast(0),
            durationMs = player.durationMs,
            speed = player.playbackSpeed,
            pitch = 1f,
            ready = !player.isBuffering,
            finite = true,
            staysFinite = state.staysFinite,
            anchorEpochMs = nowEpochMs(),
            queueHash = DeviceQueueHash.of(entries),
            accounting = null,
        )
    }

    override suspend fun apply(snapshot: DevicePlaybackSnapshot): DeviceConnectError? {
        if (!snapshot.isStructurallyValid()) return invalid("The transferred queue is malformed")
        if (snapshot.entries.any { it.provider == "local" }) return unshareable()
        val occurrences = snapshot.entries.map { it.toContentOccurrenceOrNull() ?: return invalid("The transferred queue has an unknown entry") }
        val start = snapshot.currentOccurrenceId ?: occurrences.firstOrNull()?.occurrenceId ?: return invalid("The transferred queue is empty")
        return runCatching {
            queue.replace(occurrences, start, snapshot.playing, snapshot.staysFinite)
            queue.player.seekTo(snapshot.positionMs)
            queue.player.setPlaybackSpeed(snapshot.speed)
            if (snapshot.pitch != 1f) queue.player.setPlaybackPitch(snapshot.pitch)
            editing?.setShuffle(snapshot.shuffle)
            setRepeatMode?.invoke(snapshot.repeatModeValue())
            if (snapshot.playing) queue.player.play() else queue.player.pause()
            null
        }.getOrElse { failed("This device could not start the transferred queue") }
    }

    override suspend fun pauseForRemote(): DevicePlaybackSnapshot? {
        val snapshot = capture() ?: return null
        queue.player.pause()
        return snapshot
    }

    override suspend fun resume(snapshot: DevicePlaybackSnapshot): DeviceConnectError? = apply(snapshot)

    override suspend fun command(message: DeviceSyncMessage): DeviceConnectError? {
        val command = message.command ?: return invalid("The command is empty")
        val state = queue.state.value
        val trackRevision = queue.player.snapshot.value?.playbackGeneration ?: 0L
        val ids = state.entries.map { it.occurrenceId }.toSet()
        DeviceCommandPolicy.validate(command, message.sessionId, state.revision, trackRevision, ids)?.let { return it }
        val intent = DeviceCommandPolicy.intent(command, state.playOrder, state.currentOccurrenceId, repeatMode())
            ?: return invalid("The command cannot be represented on this device")
        return runCatching {
            when (intent) {
                DeviceCommandIntent.Play -> queue.player.play()
                DeviceCommandIntent.Pause -> queue.player.pause()
                is DeviceCommandIntent.Seek -> queue.player.seekTo(intent.positionMs)
                is DeviceCommandIntent.Select -> queue.select(intent.occurrenceId, intent.positionMs)
                is DeviceCommandIntent.ReplaceQueue ->
                    queue.replace(intent.entries, intent.startOccurrenceId, intent.playing, intent.staysFinite)
                is DeviceCommandIntent.Add -> queue.insert(intent.entries, UserQueuedKind.ADD_TO_QUEUE)
                is DeviceCommandIntent.PlayNext -> queue.insert(intent.entries, UserQueuedKind.PLAY_NEXT)
                is DeviceCommandIntent.Remove -> queue.remove(intent.occurrenceIds)
                is DeviceCommandIntent.Move -> queue.move(intent.occurrenceId, intent.beforeOccurrenceId)
                DeviceCommandIntent.Clear -> {
                    val clear = editing ?: return notAllowed("This device cannot clear upcoming songs remotely")
                    clear.clearUpcoming()
                }
                is DeviceCommandIntent.Shuffle -> {
                    val shuffle = editing ?: return notAllowed("This device cannot change shuffle remotely")
                    if (!shuffle.setShuffle(intent.enabled)) return notAllowed("Shuffle is not available now")
                }
                is DeviceCommandIntent.Speed -> queue.player.setPlaybackSpeed(intent.value)
                is DeviceCommandIntent.Pitch -> queue.player.setPlaybackPitch(intent.value)
                is DeviceCommandIntent.Volume -> {
                    setPlatformVolume ?: return notAllowed("This device has no remote volume")
                    setPlatformVolume(intent.value)
                }
                is DeviceCommandIntent.Repeat -> {
                    setRepeatMode ?: return notAllowed("This device cannot change repeat remotely")
                    setRepeatMode(intent.mode)
                }
            }
            null
        }.getOrElse { failed("The command could not be applied") }
    }

    override suspend fun setVolume(volume: Float): Boolean {
        val setter = setPlatformVolume ?: return false
        setter(volume)
        return true
    }

    override fun onRemoteSessionChanged(session: DeviceSessionState?) = Unit

    private fun invalid(message: String) = DeviceConnectError(DeviceConnectErrorCode.INVALID_MESSAGE, message)

    private fun unshareable(message: String = "This queue contains local files and cannot be shared.") =
        DeviceConnectError(DeviceConnectErrorCode.UNSHAREABLE_QUEUE, message)

    private fun failed(message: String) = DeviceConnectError(DeviceConnectErrorCode.NOT_ALLOWED, message)

    private fun notAllowed(message: String) = DeviceConnectError(DeviceConnectErrorCode.NOT_ALLOWED, message)
}
