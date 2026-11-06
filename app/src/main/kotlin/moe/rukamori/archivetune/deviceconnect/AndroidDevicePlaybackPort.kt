package moe.rukamori.archivetune.deviceconnect

import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import moe.rukamori.archivetune.extensions.toMediaItem
import moe.rukamori.archivetune.models.MediaMetadata
import moe.rukamori.archivetune.playback.MusicService
import moe.rukamori.archivetune.playback.queues.ListQueue
import moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceCommandIntent
import moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceCommandPolicy
import moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceConnectError
import moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceConnectErrorCode
import moe.rukamori.archivetune.shared.domain.deviceconnect.DevicePlaybackPort
import moe.rukamori.archivetune.shared.domain.deviceconnect.DevicePlaybackSnapshot
import moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceQueueEntry
import moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceQueueHash
import moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceSyncMessage
import moe.rukamori.archivetune.shared.domain.deviceconnect.toDeviceQueueEntry
import moe.rukamori.archivetune.shared.domain.queue.QueueRepeatMode
import moe.rukamori.archivetune.utils.isLocalMediaId
import moe.rukamori.archivetune.telegram.isTelegramMediaId

/**
 * Bridges Device Connect to the existing Media3 player. It never opens a second player: transfers load
 * the normal ListQueue path and remote commands use the same Player methods the UI uses.
 */
class AndroidDevicePlaybackPort(private val service: MusicService) : DevicePlaybackPort {
    override suspend fun capture(): DevicePlaybackSnapshot? {
        val player = service.player
        if (player.mediaItemCount == 0) return null
        val entries = (0 until player.mediaItemCount).map { index -> player.getMediaItemAt(index).toEntry(index) }
        val currentIndex = player.currentMediaItemIndex.coerceIn(0, entries.lastIndex)
        val current = entries[currentIndex]
        val item = player.currentMediaItem
        return DevicePlaybackSnapshot(
            queueRevision = player.mediaItemCount.toLong(),
            trackRevision = player.currentMediaItemIndex.toLong(),
            stateRevision = player.mediaItemCount.toLong(),
            entries = entries,
            currentOccurrenceId = current.occurrenceId,
            playOrder = entries.map { it.occurrenceId },
            shuffle = player.shuffleModeEnabled,
            repeatMode = player.repeatMode,
            playing = player.playWhenReady,
            positionMs = player.currentPosition.coerceAtLeast(0),
            durationMs = item?.mediaMetadata?.durationMs?.takeIf { it >= 0 },
            speed = player.playbackParameters.speed,
            pitch = player.playbackParameters.pitch,
            volume = service.playerVolume.value,
            ready = player.playbackState != Player.STATE_BUFFERING,
            finite = true,
            staysFinite = false,
            anchorEpochMs = System.currentTimeMillis(),
            queueHash = DeviceQueueHash.of(entries),
        )
    }

    override suspend fun apply(snapshot: DevicePlaybackSnapshot): DeviceConnectError? {
        if (!snapshot.isStructurallyValid()) return invalid("The transferred queue is malformed")
        if (snapshot.entries.any { it.provider == "local" }) return unshareable()
        val items = snapshot.entries.mapNotNull { it.toMediaItemOrNull() }
        if (items.size != snapshot.entries.size || items.isEmpty()) return invalid("The transferred queue has an unknown entry")
        val start = snapshot.entries.indexOfFirst { it.occurrenceId == snapshot.currentOccurrenceId }.coerceAtLeast(0)
        return runCatching {
            service.playQueue(
                ListQueue(items = items, startIndex = start, position = snapshot.positionMs, finite = snapshot.staysFinite),
                playWhenReady = snapshot.playing,
            )
            val player = service.player
            player.seekTo(start, snapshot.positionMs)
            player.repeatMode = snapshot.repeatMode
            player.shuffleModeEnabled = snapshot.shuffle
            player.playbackParameters = PlaybackParameters(snapshot.speed, snapshot.pitch)
            snapshot.volume?.let { service.playerVolume.value = it.coerceIn(0f, 1f) }
            if (snapshot.playing) player.play() else player.pause()
            null
        }.getOrElse { unshareable("This device could not start the transferred queue") }
    }

    override suspend fun pauseForRemote(): DevicePlaybackSnapshot? {
        val snapshot = capture() ?: return null
        service.player.pause()
        return snapshot
    }

    override suspend fun resume(snapshot: DevicePlaybackSnapshot): DeviceConnectError? = apply(snapshot)

    override suspend fun command(message: DeviceSyncMessage): DeviceConnectError? {
        val command = message.command ?: return invalid()
        val snapshot = capture() ?: return invalid()
        val error = DeviceCommandPolicy.validate(
            command,
            message.sessionId,
            snapshot.queueRevision,
            snapshot.trackRevision,
            snapshot.entries.mapTo(mutableSetOf()) { it.occurrenceId },
        )
        if (error != null) return error
        val intent = DeviceCommandPolicy.intent(
            command,
            snapshot.playOrder,
            snapshot.currentOccurrenceId,
            QueueRepeatMode.fromStoredValue(snapshot.repeatMode),
        ) ?: return invalid()
        val player = service.player
        return runCatching {
            when (intent) {
                DeviceCommandIntent.Play -> player.play()
                DeviceCommandIntent.Pause -> player.pause()
                DeviceCommandIntent.Next, DeviceCommandIntent.Previous ->
                    return notAllowed("The queue does not expose a resolved successor")
                is DeviceCommandIntent.Seek -> player.seekTo(intent.positionMs)
                is DeviceCommandIntent.Select -> {
                    val index = snapshot.entries.indexOfFirst { it.occurrenceId == intent.occurrenceId }
                    if (index < 0) return notAllowed()
                    player.seekTo(index, intent.positionMs)
                    player.play()
                }
                is DeviceCommandIntent.ReplaceQueue -> {
                    val replacement = snapshot.copy(
                        entries = command.entries,
                        currentOccurrenceId = intent.startOccurrenceId,
                        playing = intent.playing,
                        staysFinite = intent.staysFinite,
                        queueHash = DeviceQueueHash.of(command.entries),
                    )
                    return apply(replacement)
                }
                is DeviceCommandIntent.Add -> player.addMediaItems(intent.entries.mapNotNull { it.toDeviceQueueEntry().toMediaItemOrNull() })
                is DeviceCommandIntent.PlayNext -> {
                    val items = intent.entries.mapNotNull { it.toDeviceQueueEntry().toMediaItemOrNull() }
                    player.addMediaItems((player.currentMediaItemIndex + 1).coerceIn(0, player.mediaItemCount), items)
                }
                is DeviceCommandIntent.Remove -> {
                    val indices = intent.occurrenceIds.mapNotNull { id ->
                        (0 until player.mediaItemCount).firstOrNull { player.getMediaItemAt(it).mediaId == id.split('#').first() }
                    }.distinct().sortedDescending()
                    indices.forEach { player.removeMediaItem(it) }
                }
                is DeviceCommandIntent.Move -> {
                    val from = (0 until player.mediaItemCount).firstOrNull {
                        player.getMediaItemAt(it).mediaId == intent.occurrenceId.split('#').first()
                    } ?: return notAllowed()
                    val to = intent.beforeOccurrenceId?.let { before ->
                        (0 until player.mediaItemCount).firstOrNull {
                            player.getMediaItemAt(it).mediaId == before.split('#').first()
                        }
                    } ?: player.mediaItemCount - 1
                    player.moveMediaItem(from, to.coerceIn(0, player.mediaItemCount - 1))
                }
                DeviceCommandIntent.Clear -> {
                    val from = player.currentMediaItemIndex + 1
                    if (from < player.mediaItemCount) player.removeMediaItems(from, player.mediaItemCount - from)
                }
                is DeviceCommandIntent.Shuffle -> player.shuffleModeEnabled = intent.enabled
                is DeviceCommandIntent.Speed -> player.playbackParameters = PlaybackParameters(intent.value, player.playbackParameters.pitch)
                is DeviceCommandIntent.Pitch -> player.playbackParameters = PlaybackParameters(player.playbackParameters.speed, intent.value)
                is DeviceCommandIntent.Volume -> service.playerVolume.value = intent.value.coerceIn(0f, 1f)
                is DeviceCommandIntent.Repeat -> player.repeatMode = intent.mode.storedValue
            }
            null
        }.getOrElse { notAllowed("The command could not be applied") }
    }

    override suspend fun setVolume(volume: Float): Boolean {
        service.playerVolume.value = volume.coerceIn(0f, 1f)
        return true
    }

    private fun MediaItem.toEntry(index: Int): DeviceQueueEntry {
        val tag = localConfiguration?.tag as? MediaMetadata
        val metadata = mediaMetadata
        val itemId = mediaId.orEmpty()
        val provider = when {
            itemId.isLocalMediaId() -> "local"
            itemId.isTelegramMediaId() -> "telegram"
            else -> "youtube"
        }
        return DeviceQueueEntry(
            occurrenceId = "$itemId#$index",
            provider = provider,
            kind = "SONG",
            id = itemId,
            title = tag?.title ?: metadata.title?.toString().orEmpty(),
            subtitle = tag?.artists?.joinToString { it.name } ?: metadata.artist?.toString().orEmpty(),
            artworkUri = tag?.thumbnailUrl ?: metadata.artworkUri?.toString(),
            durationMs = tag?.duration?.toLong()?.times(1_000) ?: metadata.durationMs?.takeIf { it >= 0 },
            explicit = tag?.explicit ?: false,
            album = tag?.album?.title ?: metadata.albumTitle?.toString(),
            albumId = tag?.album?.id,
            isrc = tag?.isrc,
            isVideo = tag?.isMusicVideo ?: false,
            setVideoId = tag?.setVideoId,
            artists = tag?.artists?.map {
                moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceArtist(it.name, it.id)
            }.orEmpty(),
        )
    }

    private fun DeviceQueueEntry.toMediaItemOrNull(): MediaItem? {
        if (provider == "local" || id.isBlank()) return null
        val metadata = MediaMetadata(
            id = id,
            title = title,
            artists = artists.map { MediaMetadata.Artist(it.id, it.name) },
            duration = ((durationMs ?: 0L) / 1_000L).toInt(),
            thumbnailUrl = artworkUri,
            album = album?.let { MediaMetadata.Album(albumId.orEmpty(), it) },
            setVideoId = setVideoId,
            explicit = explicit,
            isMusicVideo = isVideo,
            isrc = isrc,
        )
        return metadata.toMediaItem()
    }

    private fun invalid(message: String = "The command is malformed") = DeviceConnectError(DeviceConnectErrorCode.INVALID_MESSAGE, message)

    private fun notAllowed(message: String = "The command is not available on this device") =
        DeviceConnectError(DeviceConnectErrorCode.NOT_ALLOWED, message)

    private fun unshareable(message: String = "This queue contains local files and cannot be shared.") =
        DeviceConnectError(DeviceConnectErrorCode.UNSHAREABLE_QUEUE, message)

}
