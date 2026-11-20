package app.natune.playback

import app.natune.constants.DownloadSource
import app.natune.audiosource.DirectStream
import app.natune.modules.ModuleAudioSelection

/** The stream a download settled on, with the source whose cache-key prefix holds it. */
internal data class ResolvedDownload(
    val source: DownloadSource,
    val stream: DirectStream,
    val moduleSelection: ModuleAudioSelection? = null,
)
