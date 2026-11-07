package moe.rukamori.archivetune.shared.domain.deviceconnect

import moe.rukamori.archivetune.shared.domain.queue.QueueAdvancePolicy
import moe.rukamori.archivetune.shared.domain.queue.QueueRepeatMode
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence

/** The effect a remote command asks the target to perform, still independent of any player API. */
sealed interface DeviceCommandIntent {
    data object Play : DeviceCommandIntent
    data object Pause : DeviceCommandIntent
    data class Seek(val positionMs: Long) : DeviceCommandIntent
    data object Next : DeviceCommandIntent
    data object Previous : DeviceCommandIntent
    data class Select(val occurrenceId: String, val positionMs: Long) : DeviceCommandIntent
    data class ReplaceQueue(
        val entries: List<ContentOccurrence>,
        val startOccurrenceId: String,
        val playing: Boolean,
        val staysFinite: Boolean,
    ) : DeviceCommandIntent
    data class Add(val entries: List<ContentOccurrence>) : DeviceCommandIntent
    data class PlayNext(val entries: List<ContentOccurrence>) : DeviceCommandIntent
    data class Remove(val occurrenceIds: Set<String>) : DeviceCommandIntent
    data class Move(val occurrenceId: String, val beforeOccurrenceId: String?) : DeviceCommandIntent
    data object Clear : DeviceCommandIntent
    data class Shuffle(val enabled: Boolean) : DeviceCommandIntent
    data class Speed(val value: Float) : DeviceCommandIntent
    data class Pitch(val value: Float) : DeviceCommandIntent
    data class Volume(val value: Float) : DeviceCommandIntent
    data class Repeat(val mode: QueueRepeatMode) : DeviceCommandIntent
}

/**
 * Validates a controller command before the target applies it. The target is the single writer; a revision
 * mismatch is answered with a typed stale error so the controller re-pulls instead of overwriting.
 */
object DeviceCommandPolicy {
    fun validate(
        command: DeviceRemoteCommand,
        sessionId: String?,
        queueRevision: Long,
        trackRevision: Long,
        queueOccurrenceIds: Set<String>,
    ): DeviceConnectError? {
        if (command.expectedSessionId != null && command.expectedSessionId != sessionId) {
            return DeviceConnectError(DeviceConnectErrorCode.SESSION_GONE, "This playback session has ended")
        }
        if (command.expectedQueueRevision != null && command.expectedQueueRevision != queueRevision) {
            return DeviceConnectError(DeviceConnectErrorCode.STALE_QUEUE, "The queue changed. Try again.")
        }
        if (command.expectedTrackRevision != null && command.expectedTrackRevision != trackRevision) {
            return DeviceConnectError(DeviceConnectErrorCode.STALE_TRACK, "The track changed. Try again.")
        }
        if (command.entries.size > DeviceConnectLimits.MAX_QUEUE_ENTRIES) {
            return DeviceConnectError(DeviceConnectErrorCode.TOO_LARGE, "This queue is too large to sync.")
        }
        if (command.entries.any { it.provider == "local" }) {
            return DeviceConnectError(
                DeviceConnectErrorCode.UNSHAREABLE_QUEUE,
                "This queue contains local files and cannot be shared.",
            )
        }
        when (command.action) {
            DevicePlaybackAction.SEEK -> if (command.positionMs == null) return invalid()
            DevicePlaybackAction.SELECT ->
                if (command.occurrenceId.isNullOrBlank() || command.occurrenceId !in queueOccurrenceIds) {
                    return DeviceConnectError(DeviceConnectErrorCode.STALE_QUEUE, "The track is no longer queued.")
                }
            DevicePlaybackAction.REPLACE_QUEUE -> {
                if (command.entries.isEmpty()) return invalid()
                if (command.entries.size > DeviceConnectLimits.MAX_QUEUE_ENTRIES) {
                    return DeviceConnectError(DeviceConnectErrorCode.TOO_LARGE, "This queue is too large to sync.")
                }
            }
            DevicePlaybackAction.ADD, DevicePlaybackAction.PLAY_NEXT -> if (command.entries.isEmpty()) return invalid()
            DevicePlaybackAction.REMOVE ->
                if (command.entries.isEmpty() && command.occurrenceId.isNullOrBlank()) return invalid()
            DevicePlaybackAction.MOVE -> if (command.occurrenceId.isNullOrBlank()) return invalid()
            DevicePlaybackAction.SHUFFLE -> if (command.enabled == null) return invalid()
            DevicePlaybackAction.SPEED -> if (command.speed == null || !command.speed.isFinite() || command.speed <= 0f) return invalid()
            DevicePlaybackAction.PITCH -> if (command.pitch == null || !command.pitch.isFinite() || command.pitch <= 0f) return invalid()
            DevicePlaybackAction.VOLUME -> if (command.volume == null || command.volume !in 0f..1f) return invalid()
            DevicePlaybackAction.REPEAT ->
                if (command.repeatMode == null || QueueRepeatMode.entries.none { it.storedValue == command.repeatMode }) return invalid()
            else -> Unit
        }
        return null
    }

    /** Maps a validated command to its target-independent intent. Null means the action is not representable. */
    fun intent(command: DeviceRemoteCommand, playOrder: List<String>, currentOccurrenceId: String?, repeatMode: QueueRepeatMode): DeviceCommandIntent? {
        val entries = command.entries.mapNotNull { it.toContentOccurrenceOrNull() }
        if (command.entries.isNotEmpty() && entries.size != command.entries.size) return null
        return when (command.action) {
            DevicePlaybackAction.PLAY -> DeviceCommandIntent.Play
            DevicePlaybackAction.PAUSE -> DeviceCommandIntent.Pause
            DevicePlaybackAction.SEEK -> command.positionMs?.let(DeviceCommandIntent::Seek)
            DevicePlaybackAction.NEXT -> {
                val next = currentOccurrenceId?.let { QueueAdvancePolicy.successor(repeatMode, playOrder, it) }
                next?.let { DeviceCommandIntent.Select(it, 0) }
            }
            DevicePlaybackAction.PREVIOUS -> {
                val current = currentOccurrenceId ?: return null
                val position = playOrder.indexOf(current)
                if (position < 0) return null
                val previous = when {
                    position > 0 -> playOrder[position - 1]
                    repeatMode == QueueRepeatMode.ALL && playOrder.isNotEmpty() -> playOrder.last()
                    else -> null
                }
                previous?.let { DeviceCommandIntent.Select(it, 0) }
            }
            DevicePlaybackAction.SELECT -> command.occurrenceId?.let { DeviceCommandIntent.Select(it, command.positionMs ?: 0) }
            DevicePlaybackAction.REPLACE_QUEUE -> {
                val start = command.occurrenceId ?: entries.firstOrNull()?.occurrenceId ?: return null
                DeviceCommandIntent.ReplaceQueue(
                    entries = entries,
                    startOccurrenceId = start,
                    playing = command.enabled ?: true,
                    staysFinite = command.index?.let { it != 0 } ?: false,
                )
            }
            DevicePlaybackAction.ADD -> DeviceCommandIntent.Add(entries)
            DevicePlaybackAction.PLAY_NEXT -> DeviceCommandIntent.PlayNext(entries)
            DevicePlaybackAction.REMOVE -> {
                val ids = buildSet {
                    command.occurrenceId?.takeIf { it.isNotBlank() }?.let(::add)
                    command.entries.mapNotNullTo(this) { it.occurrenceId.takeIf(String::isNotBlank) }
                }
                if (ids.isEmpty()) null else DeviceCommandIntent.Remove(ids)
            }
            DevicePlaybackAction.MOVE -> {
                val id = command.occurrenceId ?: return null
                val before = command.toIndex?.let { index -> playOrder.getOrNull(index) }
                DeviceCommandIntent.Move(id, before)
            }
            DevicePlaybackAction.CLEAR -> DeviceCommandIntent.Clear
            DevicePlaybackAction.SHUFFLE -> command.enabled?.let(DeviceCommandIntent::Shuffle)
            DevicePlaybackAction.SPEED -> command.speed?.let(DeviceCommandIntent::Speed)
            DevicePlaybackAction.PITCH -> command.pitch?.let(DeviceCommandIntent::Pitch)
            DevicePlaybackAction.VOLUME -> command.volume?.let(DeviceCommandIntent::Volume)
            DevicePlaybackAction.REPEAT -> command.repeatMode?.let { DeviceCommandIntent.Repeat(QueueRepeatMode.fromStoredValue(it)) }
        }
    }

    private fun invalid(): DeviceConnectError =
        DeviceConnectError(DeviceConnectErrorCode.INVALID_MESSAGE, "The command is incomplete.")
}
