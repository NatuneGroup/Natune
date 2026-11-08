package moe.rukamori.archivetune.desktop

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import moe.rukamori.archivetune.shared.domain.lastfm.*
import moe.rukamori.archivetune.shared.domain.playback.MusicPlayerController
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.QueueState
import moe.rukamori.archivetune.shared.domain.settings.*

/** Opt-in ListenBrainz events use the same bounded listening-time rule as the existing scrobble owner. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class DesktopListenBrainz(scope: CoroutineScope, private val http: HttpClient,
    private val settings: SettingsStore, player: MusicPlayerController, queue: StateFlow<QueueState>) {
    private val ownerScope = CoroutineScope(scope.coroutineContext + Dispatchers.Default.limitedParallelism(1))
    private val tracker = LastFmScrobbleTracker(ownerScope, { System.currentTimeMillis() / 1000 },
        { track -> submit(track, null) }, { track, started -> submit(track, started) })
    private val driver = ownerScope.launch {
        combine(player.snapshot, queue, settings.snapshots) { playback, state, preferences -> Triple(playback, state, preferences) }
            .collect { (playback, state, preferences) ->
                if (preferences?.get(ListenBrainzEnabledKey, false) != true || preferences[ListenBrainzTokenKey].isNullOrBlank()) {
                    tracker.onSongStop()
                } else {
                    val row = state.entries.firstOrNull { it.occurrenceId == playback?.occurrenceId }
                    val item = row?.item
                    val play = if (item == null) null else LastFmPlay(requireNotNull(row).occurrenceId,
                        item.artists.map { it.name }.ifEmpty { listOf(item.subtitle) }, item.title, item.album,
                        ((item.durationMs ?: 0) / 1000).toInt(), item.ref.kind != ContentKind.SONG)
                    tracker.onPlayerStateChanged(playback?.isAudioAdvancing == true, play, playback?.durationMs)
                }
            }
    }
    private fun submit(track: LastFmTrackMetadata, started: Long?) {
        val preferences = settings.snapshots.value ?: return
        val token = preferences[ListenBrainzTokenKey]?.takeIf { it.isNotBlank() } ?: return
        if (preferences[ListenBrainzEnabledKey, false] != true) return
        ownerScope.launch {
            try {
                if (settings.snapshots.value?.get(ListenBrainzTokenKey) != token || settings.snapshots.value?.get(ListenBrainzEnabledKey) != true) return@launch
                val body = buildJsonObject {
                    put("listen_type", if (started == null) "playing_now" else "single")
                    put("payload", buildJsonArray { add(buildJsonObject {
                        started?.let { put("listened_at", it) }
                        put("track_metadata", buildJsonObject {
                            put("artist_name", track.artist); put("track_name", track.track)
                            track.album?.let { put("release_name", it) }
                            put("additional_info", buildJsonObject { put("submission_client", "Natune"); track.durationSeconds?.let { put("duration", it) } })
                        })
                    }) })
                }
                withTimeout(15_000L) {
                    check(http.post("https://api.listenbrainz.org/1/submit-listens") {
                        header("Authorization", "Token $token"); header("Content-Type", "application/json"); setBody(body.toString())
                    }.status.value in 200..299)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { desktopDiagnostic("ListenBrainz submission declined: ${failure.javaClass.simpleName}") }
        }
    }
    fun close() { driver.cancel(); ownerScope.launch { tracker.destroy() } }
}
