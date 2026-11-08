package moe.rukamori.archivetune.playback

import moe.rukamori.archivetune.constants.DownloadSource
import moe.rukamori.archivetune.audiosource.DirectStream
import moe.rukamori.archivetune.modules.ModuleAudioSelection

/** The stream a download settled on, with the source whose cache-key prefix holds it. */
internal data class ResolvedDownload(
    val source: DownloadSource,
    val stream: DirectStream,
    val moduleSelection: ModuleAudioSelection? = null,
)
