package moe.rukamori.archivetune.shared.ui.player

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.domain.playback.PlaybackSnapshot
import moe.rukamori.archivetune.shared.domain.repository.QueueController
import moe.rukamori.archivetune.shared.domain.repository.QueueState
import moe.rukamori.archivetune.shared.domain.settings.SettingsStore
import androidx.compose.runtime.getValue

/**
 * Projection of M7's sole queue facade; no copied queue state or second persistence owner. Until the engine reports
 * a removed track's successor, the queue's current item is shown without the removed track's snapshot.
 */
fun queuePlayerState(queue: QueueState,playback: PlaybackSnapshot?,active: Boolean): PlayerState {
    val playing=queue.entries.firstOrNull { it.occurrenceId==playback?.occurrenceId }
    val entry=playing ?: queue.entries.firstOrNull { it.occurrenceId==queue.currentOccurrenceId }
    val item=entry?.item
    val index=entry?.let { queue.playOrder.indexOf(it.occurrenceId) } ?: -1
    return PlayerState(playback.takeIf { playing!=null },item?.title.orEmpty(),item?.subtitle.orEmpty(),queue.playOrder,
        canPrevious=index>0,canNext=index>=0 && index<queue.playOrder.lastIndex,active=active,
        albumTitle=item?.album,explicit=item?.explicit ?: false,
        standInOccurrenceId=entry?.occurrenceId.takeIf { playing==null })
}

/**
 * Live composition seam for M4/M6/M7/native hosts. The caller's action scope belongs to its session,
 * not this screen, so dismissing a player never cancels an already requested queue selection.
 * [decorateState] supplies actual route volume, Canvas, palette, repeat/shuffle and motion results.
 */
@Composable
fun ObservedBottomSheetPlayer(
    queue: QueueController,
    settings: SettingsStore,
    actionScope: CoroutineScope,
    slots: PlayerSlots,
    blurSupported: Boolean,
    active: Boolean,
    decorateState: (PlayerState) -> PlayerState,
    onAction: (PlayerAction) -> Unit,
    setVolume: (Float) -> Unit,
    onLyricsVisibility: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    interaction: PlayerInteractionState = remember { PlayerInteractionState() },
) {
    ObservedBottomSheetPlayer(queue,settings,actionScope,{ _, _ -> slots },blurSupported,active,
        decorateState,onAction,setVolume,onLyricsVisibility,modifier,interaction)
}

@Composable
fun ObservedBottomSheetPlayer(
    queue: QueueController,
    settings: SettingsStore,
    actionScope: CoroutineScope,
    slots: PlayerSlotFactory,
    blurSupported: Boolean,
    active: Boolean,
    decorateState: (PlayerState) -> PlayerState,
    onAction: (PlayerAction) -> Unit,
    setVolume: (Float) -> Unit,
    onLyricsVisibility: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    interaction: PlayerInteractionState = remember { PlayerInteractionState() },
) {
    // A replacement owner starts from its own value, never a remembered value from the old session.
    key(queue,settings,queue.player) {
        val preferences by settings.snapshots.collectAsState()
        val queueState by queue.state.collectAsState()
        val playback by queue.player.snapshot.collectAsState()
        // Null means not read yet, not a default V4 flash before the saved style arrives.
        val ready=preferences
        if(ready!=null) {
            val state=decorateState(queuePlayerState(queueState,playback,active))
            val displayedPlayback by rememberUpdatedState(state.playback)
            val actions=remember(queue,queue.player,actionScope,onAction,setVolume,onLyricsVisibility) {
                controllerPlayerActions(queue.player,{ id -> actionScope.launch { queue.select(id) } },
                    setVolume,onAction,onLyricsVisibility,{ displayedPlayback })
            }
            BottomSheetPlayer(state,actions,ready,slots,blurSupported,modifier,interaction)
        }
    }
}
