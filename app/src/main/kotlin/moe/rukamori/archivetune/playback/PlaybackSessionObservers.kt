package moe.rukamori.archivetune.playback

import androidx.media3.common.Player
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import moe.rukamori.archivetune.db.MusicDatabase
import moe.rukamori.archivetune.sponsorblock.SponsorBlockPlaybackController

/** Moves session-facing listeners together when MusicService promotes or replaces its player. */
class PlaybackSessionObservers @Inject constructor(
    private val database: MusicDatabase,
    private val sponsorBlock: SponsorBlockPlaybackController,
) {
    private var progress: AndroidPodcastProgressController? = null

    fun attach(player: Player, ioScope: CoroutineScope) {
        sponsorBlock.attach(player, ioScope)
        val progress = progress ?: AndroidPodcastProgressController(database).also { this.progress = it }
        progress.attach(player)
    }

    fun detach() {
        try {
            sponsorBlock.detach()
        } finally {
            progress?.detach()
            progress = null
        }
    }
}
