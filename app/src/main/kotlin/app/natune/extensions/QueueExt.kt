/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package app.natune.extensions

import moe.rukamori.archivetune.innertube.models.WatchEndpoint
import app.natune.models.MediaMetadata
import app.natune.models.PersistQueue
import app.natune.models.QueueData
import app.natune.models.QueueType
import app.natune.playback.queues.ListQueue
import app.natune.playback.queues.LocalAlbumRadio
import app.natune.playback.queues.Queue
import app.natune.playback.queues.YouTubeAlbumRadio
import app.natune.playback.queues.YouTubeQueue
import app.natune.playback.queues.YouTubePlaylistQueue
import app.natune.playback.queues.FinitePlaylistSnapshot
import app.natune.playback.queues.FinitePlaylistPhase
import app.natune.playback.queues.isFiniteYouTubePlaylist

fun Queue.toPersistQueue(
    title: String?,
    items: List<MediaMetadata>,
    mediaItemIndex: Int,
    position: Long,
    timelineOccurrences: List<String?> = emptyList(),
): PersistQueue =
    when (this) {
        is ListQueue -> {
            PersistQueue(
                title = title,
                items = items,
                mediaItemIndex = mediaItemIndex,
                position = position,
                queueType = QueueType.LIST,
                finite = finite,
            )
        }

        is YouTubePlaylistQueue -> {
            PersistQueue(
                title, items, mediaItemIndex, position, QueueType.YOUTUBE_PLAYLIST,
                QueueData.YouTubePlaylistData(
                    playlistId = playlistId,
                    videoId = endpoint.videoId,
                    endpointParams = endpoint.params,
                    endpointIndex = endpoint.index,
                    playlistSetVideoId = endpoint.playlistSetVideoId,
                    snapshot = snapshot,
                    timelineOccurrences = timelineOccurrences,
                    needsReconciliation = snapshot.entries.isEmpty() && !confirmedExhausted,
                ),
            )
        }

        is YouTubeQueue -> {
            PersistQueue(
                title = title,
                items = items,
                mediaItemIndex = mediaItemIndex,
                position = position,
                queueType = QueueType.YOUTUBE,
                queueData =
                    QueueData.YouTubeData(
                        videoId = endpoint.videoId,
                        playlistId = endpoint.playlistId,
                        endpointParams = endpoint.params,
                        followAutomixPreview = followAutomixPreview,
                    ),
            )
        }

        is YouTubeAlbumRadio -> {
            PersistQueue(
                title = title,
                items = items,
                mediaItemIndex = mediaItemIndex,
                position = position,
                queueType = QueueType.YOUTUBE_ALBUM_RADIO,
                queueData =
                    QueueData.YouTubeAlbumRadioData(
                        playlistId = playlistId,
                        albumSongCount = albumSongCount,
                        continuation = continuation,
                        firstTimeLoaded = firstTimeLoaded,
                    ),
            )
        }

        is LocalAlbumRadio -> {
            PersistQueue(
                title = title,
                items = items,
                mediaItemIndex = mediaItemIndex,
                position = position,
                queueType = QueueType.LOCAL_ALBUM_RADIO,
                queueData =
                    QueueData.LocalAlbumRadioData(
                        albumId = albumWithSongs.album.id,
                        startIndex = startIndex,
                    ),
            )
        }

        else -> {
            PersistQueue(
                title = title,
                items = items,
                mediaItemIndex = mediaItemIndex,
                position = position,
                queueType = QueueType.LIST,
            )
        }
    }

fun PersistQueue.toContinuationQueue(): Queue =
    when (queueType) {
        is QueueType.LIST -> {
            ListQueue(
                title = title,
                items = items.map { it.toMediaItem() },
                startIndex = mediaItemIndex,
                position = position,
                finite = finite,
            )
        }

        is QueueType.YOUTUBE -> {
            val data =
                queueData as? QueueData.YouTubeData
                    ?: return ListQueue(title, items.map { it.toMediaItem() }, mediaItemIndex, position)
            if (isFiniteYouTubePlaylist(data.playlistId) && !data.followAutomixPreview) {
                YouTubePlaylistQueue(
                    endpoint = WatchEndpoint(playlistId = data.playlistId),
                    snapshot = FinitePlaylistSnapshot(title = title, phase = FinitePlaylistPhase.UNKNOWN),
                    legacyItems = items,
                )
            } else YouTubeQueue(
                endpoint =
                    WatchEndpoint(
                        videoId = data.videoId,
                        playlistId = data.playlistId,
                        params = data.endpointParams,
                    ),
                followAutomixPreview = data.followAutomixPreview,
            )
        }

        is QueueType.YOUTUBE_PLAYLIST -> {
            val data = queueData as? QueueData.YouTubePlaylistData
                ?: return ListQueue(title, items.map { it.toMediaItem() }, mediaItemIndex, position)
            YouTubePlaylistQueue(
                endpoint = WatchEndpoint(
                    videoId = data.videoId,
                    playlistId = data.playlistId,
                    params = data.endpointParams,
                    index = data.endpointIndex,
                    playlistSetVideoId = data.playlistSetVideoId,
                ),
                snapshot = data.snapshot,
                restoredTimelineKeys = data.timelineOccurrences,
                legacyItems = if (data.needsReconciliation) items else null,
            )
        }

        is QueueType.YOUTUBE_ALBUM_RADIO -> {
            val data =
                queueData as? QueueData.YouTubeAlbumRadioData
                    ?: return ListQueue(title, items.map { it.toMediaItem() }, mediaItemIndex, position)
            YouTubeAlbumRadio(
                playlistId = data.playlistId,
                albumSongCount = data.albumSongCount,
                continuation = data.continuation,
                firstTimeLoaded = data.firstTimeLoaded,
            )
        }

        is QueueType.LOCAL_ALBUM_RADIO -> {
            ListQueue(
                title = title,
                items = items.map { it.toMediaItem() },
                startIndex = mediaItemIndex,
                position = position,
            )
        }
    }
