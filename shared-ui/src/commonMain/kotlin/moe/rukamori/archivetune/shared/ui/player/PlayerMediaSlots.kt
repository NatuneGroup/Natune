package moe.rukamori.archivetune.shared.ui.player

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.StateFlow
import moe.rukamori.archivetune.shared.domain.lyrics.LyricsLine
import moe.rukamori.archivetune.shared.domain.playback.PlaybackSnapshot
import moe.rukamori.archivetune.shared.domain.settings.*
import moe.rukamori.archivetune.shared.ui.lyrics.*

/** Required real media/content owners. Artwork also supplies M4's isolated Haze source. */
class PlayerMediaSlots(
    val artwork: @Composable (occurrenceId: String?, modifier: Modifier) -> Unit,
    val canvas: @Composable (occurrenceId: String?, active: Boolean, modifier: Modifier) -> Unit,
    val customBackground: @Composable (uri: String, modifier: Modifier) -> Unit,
    val queue: @Composable (modifier: Modifier) -> Unit,
)

/** The per-song offset comes from the lyrics/session owner, never a second preference store. */
fun PreferenceSnapshot.playerLyricsSettings(syncOffsetMs: Long, reducedMotion: Boolean): LyricsSurfaceSettings =
    LyricsSurfaceSettings(
        mode = LyricsMode.fromStored(this[LyricsModeKey]),
        tapToSeek = this[LyricsClickKey, true],
        syncOffsetMs = syncOffsetMs,
        reduceMotion = reducedMotion || this[DisableAnimationsKey, false],
    )

/**
 * Connects session documents and M3 renderers to actual shell visibility and live settings.
 * [playback] must be the SAME `queue.player.snapshot` passed to [BoundBottomSheetPlayer].
 * [compactStyle] is explicit because M3 currently publishes only BitChord and SimpMusic strips;
 * this adapter must not invent additional renderers or claim unsupported native inline parity.
 */
fun playerLyricsSlotFactory(
    media: PlayerMediaSlots,
    occurrenceId: String,
    lines: List<LyricsLine>,
    sessionKey: String,
    playback: StateFlow<PlaybackSnapshot?>,
    romanization: LyricsRomanizationState,
    actions: LyricsSurfaceActions,
    syncOffsetMs: Long,
    compactStyle: (PlayerDesignStyle) -> LyricsCompactStyle,
    resumed: Boolean,
    reducedMotion: Boolean,
): PlayerSlotFactory = { preferences, surfaces ->
    key(playback, romanization, sessionKey) {
        val accepted by playback.collectAsState()
        val matching = accepted?.occurrenceId == occurrenceId
        val settings = preferences.playerLyricsSettings(syncOffsetMs, reducedMotion)
        val currentActions by rememberUpdatedState(actions)
        val guardedActions = remember(playback, occurrenceId) {
            LyricsSurfaceActions(
                onSeek = { position ->
                    val current = playback.value
                    if (current != null && current.occurrenceId == occurrenceId && current.isSeekable) {
                        currentActions.onSeek(position)
                    }
                },
                onShareCard = { card ->
                    if (playback.value?.occurrenceId == occurrenceId) currentActions.onShareCard(card)
                },
            )
        }
        val lyrics = playerLyricsSlots(
            lines = if (matching) lines else emptyList(),
            sessionKey = sessionKey,
            playback = playback,
            romanization = romanization,
            settings = settings,
            actions = guardedActions,
            compactStyle = compactStyle(preferences[PlayerDesignStyleKey].toEnum(PlayerDesignStyle.Default)),
            fullVisible = matching && surfaces.fullLyrics,
            compactVisible = matching && surfaces.compactLyrics,
            resumed = resumed,
        )
        PlayerSlots(
            artwork = media.artwork,
            canvas = { id, active, modifier ->
                media.canvas(id, active && surfaces.artwork && resumed && !settings.reduceMotion &&
                    playback.value?.occurrenceId == id, modifier)
            },
            customBackground = media.customBackground,
            lyrics = lyrics.full,
            compactLyrics = lyrics.compact,
            queue = media.queue,
        )
    }
}
