package moe.rukamori.archivetune.playback

import androidx.media3.common.MediaItem
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import moe.rukamori.archivetune.modules.ModuleAudioSelection
import moe.rukamori.archivetune.modules.ModuleDownloadRepository
import moe.rukamori.archivetune.modules.ModuleMediaStorage
import moe.rukamori.archivetune.modules.ResolveModuleAudioUseCase

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
