package app.natune.playback

import androidx.media3.common.MediaItem
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import app.natune.modules.ModuleAudioSelection
import app.natune.modules.ModuleDownloadRepository
import app.natune.modules.ModuleMediaStorage
import app.natune.modules.ResolveModuleAudioUseCase

/**
 * Retains constant-bitrate seeking and the existing Apple/Spotify DRM provider on the standard
 * source. Module audio uses the upstream storage's progressive/HLS/DASH factory after source-order
 * selection; module configuration and credentials never reach the standard YouTube resolver.
 */
internal fun moduleMediaSourceFactory(
    dataSourceFactory: DataSource.Factory,
    drmProvider: DrmSessionManagerProvider,
    resolver: ResolveModuleAudioUseCase,
    bindings: ModuleDownloadRepository,
    storage: ModuleMediaStorage,
    completedDownload: (String) -> Boolean,
    selected: (String, ModuleAudioSelection?) -> Unit,
    selectAudio: suspend (MediaItem) -> ModuleAudioSelection?,
): MediaSource.Factory = ModuleMediaSourceFactory(
    DefaultMediaSourceFactory(dataSourceFactory, DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true))
        .setDrmSessionManagerProvider(drmProvider),
    resolver, bindings, storage, completedDownload, selected, selectAudio,
)
