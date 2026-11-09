/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback

import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.source.CompositeMediaSource
import androidx.media3.exoplayer.source.ForwardingTimeline
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.extensions.metadata
import moe.rukamori.archivetune.modules.ModuleAudioSelection
import moe.rukamori.archivetune.modules.ModuleDownloadRepository
import moe.rukamori.archivetune.modules.ModuleMediaStorage
import moe.rukamori.archivetune.modules.ResolveModuleAudioUseCase
import moe.rukamori.archivetune.modules.moduleMatchMetadata
import java.io.IOException

internal class ModuleMediaSourceFactory(
    private val delegate: MediaSource.Factory,
    private val resolver: ResolveModuleAudioUseCase,
    private val bindings: ModuleDownloadRepository,
    private val storage: ModuleMediaStorage,
    private val completedDownload: (String) -> Boolean,
    private val selected: (String, ModuleAudioSelection?) -> Unit,
    private val selectAudio: suspend (MediaItem) -> ModuleAudioSelection?,
) : MediaSource.Factory by delegate {
    override fun setDrmSessionManagerProvider(drmSessionManagerProvider: DrmSessionManagerProvider): MediaSource.Factory {
        delegate.setDrmSessionManagerProvider(drmSessionManagerProvider)
        return this
    }

    override fun setLoadErrorHandlingPolicy(loadErrorHandlingPolicy: LoadErrorHandlingPolicy): MediaSource.Factory {
        delegate.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
        return this
    }

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val configuration = mediaItem.localConfiguration ?: return delegate.createMediaSource(mediaItem)
        if (configuration.uri.scheme in listOf("content", "file", "android.resource", "http", "https", "telegram") ||
            mediaItem.metadata?.let { !it.moduleMatchMetadata().eligible } == true) return delegate.createMediaSource(mediaItem)
        return ModuleMediaSource(mediaItem, delegate, resolver, bindings, storage, completedDownload, selected, selectAudio)
    }
}

private class ModuleMediaSource(
    @Volatile private var item: MediaItem,
    private val factory: MediaSource.Factory,
    private val resolver: ResolveModuleAudioUseCase,
    private val bindings: ModuleDownloadRepository,
    private val storage: ModuleMediaStorage,
    private val completedDownload: (String) -> Boolean,
    private val selected: (String, ModuleAudioSelection?) -> Unit,
    private val selectAudio: suspend (MediaItem) -> ModuleAudioSelection?,
) : CompositeMediaSource<Unit>() {
    private var scope: CoroutineScope? = null
    private var child: MediaSource? = null
    private var failure: IOException? = null

    override fun getMediaItem(): MediaItem = item

    override fun canUpdateMediaItem(mediaItem: MediaItem): Boolean = moduleMediaItemCanUpdate(item, mediaItem)

    override fun updateMediaItem(mediaItem: MediaItem) {
        require(canUpdateMediaItem(mediaItem))
        // The child owns a resolved URI/cache key. Updating that child's item with the original
        // queue URI would change its audio source; only the outer presentation needs the new tags.
        item = mediaItem
    }

    override fun prepareSourceInternal(mediaTransferListener: TransferListener?) {
        super.prepareSourceInternal(mediaTransferListener)
        val owner = CoroutineScope(SupervisorJob() + Handler(checkNotNull(Looper.myLooper())).asCoroutineDispatcher())
        scope = owner
        val resolvingItem = item
        owner.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val completed = completedDownload(resolvingItem.mediaId)
                    val download = if (completed) bindings.find(resolvingItem.mediaId) else null
                    val audio = download ?: if (completed) null else selectAudio(resolvingItem)
                    if (audio != null) storage.record(audio)
                    audio to (download != null)
                }
                selected(resolvingItem.mediaId, result.first)
                val source = result.first?.let { storage.mediaSource(resolvingItem, it, result.second) } ?: factory.createMediaSource(resolvingItem)
                child = source
                prepareChildSource(Unit, source)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { failure = IOException("Audio source could not be prepared", error) }
        }
    }

    override fun maybeThrowSourceInfoRefreshError() {
        failure?.let { throw it }
        super.maybeThrowSourceInfoRefreshError()
    }

    override fun onChildSourceInfoRefreshed(childSourceId: Unit, mediaSource: MediaSource, newTimeline: Timeline) {
        refreshSourceInfo(object : ForwardingTimeline(newTimeline) {
            override fun getWindow(windowIndex: Int, window: Timeline.Window, defaultPositionProjectionUs: Long): Timeline.Window {
                return super.getWindow(windowIndex, window, defaultPositionProjectionUs).apply { mediaItem = item }
            }
        })
    }

    override fun createPeriod(id: MediaSource.MediaPeriodId, allocator: Allocator, startPositionUs: Long): MediaPeriod =
        checkNotNull(child).createPeriod(id, allocator, startPositionUs)

    override fun releasePeriod(mediaPeriod: MediaPeriod) { child?.releasePeriod(mediaPeriod) }

    override fun releaseSourceInternal() {
        scope?.cancel()
        scope = null
        super.releaseSourceInternal()
        child = null
        failure = null
    }
}

/** Playback configuration, recording identity, and source eligibility must survive an edit. */
internal fun moduleMediaItemCanUpdate(previous: MediaItem, updated: MediaItem): Boolean {
    val oldRawTag = previous.localConfiguration?.tag
    val newRawTag = updated.localConfiguration?.tag
    val oldTag = previous.metadata
    val newTag = updated.metadata
    if ((oldTag == null || newTag == null) && oldRawTag != newRawTag) return false
    if (oldTag?.canonicalMetadata() != newTag?.canonicalMetadata()) {
        // Permit display fields (including artwork) but never a different recording or source gate.
        if (oldTag == null || newTag == null || oldTag.canonicalMetadata().copy(
                title = newTag.canonicalMetadata().title,
                artists = newTag.canonicalMetadata().artists,
                thumbnailUrl = newTag.thumbnailUrl,
                album = newTag.album,
            ) != newTag.canonicalMetadata()) return false
    }
    return previous.buildUpon().setTag(updated.localConfiguration?.tag)
        .setMediaMetadata(updated.mediaMetadata).build() == updated
}
