package moe.rukamori.archivetune.desktop

import moe.rukamori.archivetune.shared.domain.repository.ContentDownload
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.domain.repository.DownloadPhase
import moe.rukamori.archivetune.shared.ui.menu.CollectionDownloadState
import moe.rukamori.archivetune.shared.ui.menu.playerqueue.PlayerQueueDownloadState

internal fun ContentRef.desktopDownloadId(): String = "$provider:$id"

internal fun desktopDownloadSupported(ref: ContentRef): Boolean =
    ref.kind == ContentKind.SONG &&
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
