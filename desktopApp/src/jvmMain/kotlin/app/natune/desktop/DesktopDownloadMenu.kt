package app.natune.desktop

import app.natune.shared.domain.repository.ContentDownload
import app.natune.shared.domain.repository.ContentKind
import app.natune.shared.domain.repository.ContentProvider
import app.natune.shared.domain.repository.ContentRef
import app.natune.shared.domain.repository.DownloadPhase
import app.natune.shared.ui.menu.CollectionDownloadState
import app.natune.shared.ui.menu.playerqueue.PlayerQueueDownloadState

internal fun ContentRef.desktopDownloadId(): String = "$provider:$id"

internal fun desktopDownloadSupported(ref: ContentRef): Boolean =
    ref.kind in setOf(ContentKind.SONG, ContentKind.EPISODE) &&
        ref.provider !in setOf(ContentProvider.LOCAL.id, ContentProvider.TELEGRAM.id, ContentProvider.SPOTIFY_STREAMING.id) &&
        !ref.id.startsWith("telegram://")

internal fun desktopDownloadMenuState(transfers: List<ContentDownload>, ref: ContentRef): CollectionDownloadState =
    when (transfers.firstOrNull { it.id == ref.desktopDownloadId() && it.ref == ref }?.phase) {
        DownloadPhase.COMPLETED -> CollectionDownloadState.COMPLETED
        DownloadPhase.QUEUED, DownloadPhase.DOWNLOADING, DownloadPhase.REMOVING -> CollectionDownloadState.IN_PROGRESS
        DownloadPhase.STOPPED, DownloadPhase.FAILED, null -> CollectionDownloadState.NOT_DOWNLOADED
    }

internal fun desktopPlayerDownloadState(transfers: List<ContentDownload>, ref: ContentRef): PlayerQueueDownloadState =
    when (desktopDownloadMenuState(transfers, ref)) {
        CollectionDownloadState.COMPLETED -> PlayerQueueDownloadState.COMPLETED
        CollectionDownloadState.IN_PROGRESS -> PlayerQueueDownloadState.IN_PROGRESS
        CollectionDownloadState.NOT_DOWNLOADED -> PlayerQueueDownloadState.NOT_DOWNLOADED
    }
