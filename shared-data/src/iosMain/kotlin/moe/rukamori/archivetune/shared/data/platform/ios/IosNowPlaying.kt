/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package moe.rukamori.archivetune.shared.data.platform.ios

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import moe.rukamori.archivetune.shared.domain.playback.MusicPlayerController
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import platform.MediaPlayer.*

/**
 * Main-queue system controls dispatch to the existing controller/queue owner, not a new playlist.
 * [metadata] is keyed by occurrence, so repeated media do not inherit another occurrence's labels.
 */
class IosNowPlaying(
    private val player: MusicPlayerController,
    private val metadata: (occurrenceId: String) -> ContentItem?,
    private val next: () -> Unit,
    private val previous: () -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val center = MPNowPlayingInfoCenter.defaultCenter()
    private val remote = MPRemoteCommandCenter.sharedCommandCenter()
    private val registrations = mutableListOf<Pair<MPRemoteCommand, Any>>()
    private val commandErrors = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val failures = commandErrors.asSharedFlow()
    private var closed = false

    init {
        scope.launch {
            bind(remote.playCommand) { player.play() }
            bind(remote.pauseCommand) { player.pause() }
            bind(remote.togglePlayPauseCommand) {
                if (player.snapshot.value?.playWhenReady == true) player.pause() else player.play()
            }
            bind(remote.stopCommand) { player.stop() }
            bind(remote.nextTrackCommand) { next() }
            bind(remote.previousTrackCommand) { previous() }
            bind(remote.changePlaybackPositionCommand, accepts = { event ->
                val position = (event as? MPChangePlaybackPositionCommandEvent)?.positionTime
                position != null && position.isFinite() && position >= 0 &&
                    player.snapshot.value?.isSeekable == true
            }) { event ->
                player.seekTo(((event as MPChangePlaybackPositionCommandEvent).positionTime * 1_000).toLong())
            }
            player.snapshot.collect { snapshot ->
                if (snapshot == null) {
                    center.nowPlayingInfo = null
                } else {
                    val item = metadata(snapshot.occurrenceId)
                    val info = mutableMapOf<Any?, Any>(
                        MPNowPlayingInfoPropertyElapsedPlaybackTime to snapshot.positionMs / 1_000.0,
                        MPNowPlayingInfoPropertyPlaybackRate to
                            if (snapshot.isAudioAdvancing && !snapshot.isBuffering) snapshot.playbackSpeed.toDouble() else 0.0,
                        MPNowPlayingInfoPropertyDefaultPlaybackRate to snapshot.playbackSpeed.toDouble(),
                        MPNowPlayingInfoPropertyExternalContentIdentifier to snapshot.mediaId,
                    )
                    snapshot.durationMs?.let { info[MPMediaItemPropertyPlaybackDuration] = it / 1_000.0 }
                    item?.let {
                        info[MPMediaItemPropertyTitle] = it.title
                        info[MPMediaItemPropertyArtist] = it.subtitle
                    }
                    center.nowPlayingInfo = info
                    remote.changePlaybackPositionCommand.enabled = snapshot.isSeekable
                }
            }
        }
    }

    private fun bind(
        command: MPRemoteCommand,
        accepts: (MPRemoteCommandEvent) -> Boolean = { true },
        action: (MPRemoteCommandEvent) -> Unit,
    ) {
        command.enabled = true
        val token = command.addTargetWithHandler { event ->
            val expected = player.snapshot.value
            if (closed || event == null || expected == null) MPRemoteCommandHandlerStatusNoSuchContent
            else if (!accepts(event)) MPRemoteCommandHandlerStatusCommandFailed
            else {
                // Native callbacks are guarded; application failures never cross Objective-C.
                try {
                    // MPRemoteCommand callbacks can arrive off-main; actual command dispatch is confined.
                    scope.launch {
                        val current = player.snapshot.value
                        if (!closed && current != null && current.occurrenceId == expected.occurrenceId &&
                            current.playbackGeneration == expected.playbackGeneration) {
                            try { action(event) }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { commandErrors.tryEmit("remote-command-failed") }
                        }
                    }
                    MPRemoteCommandHandlerStatusSuccess
                } catch (_: Exception) { MPRemoteCommandHandlerStatusCommandFailed }
            }
        }
        registrations += command to token
    }

    fun close() {
        scope.launch {
            if (closed) return@launch
            closed = true
            registrations.forEach { (command, token) -> command.removeTarget(token) }
            registrations.clear()
            center.nowPlayingInfo = null
            scope.cancel()
        }
    }
}
