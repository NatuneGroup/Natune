package moe.rukamori.archivetune.shared.ui.player

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CoroutineScope
import moe.rukamori.archivetune.shared.domain.repository.QueueController
import moe.rukamori.archivetune.shared.domain.settings.SettingsStore

/**
 * Production composition adapter: settings and accepted playback come from their sole owners,
 * native capabilities/actions from [controls], and real media/M3 content from [slots].
 * Neither dismissal nor a look/orientation change owns or closes the session action scope.
 */
@Composable
fun BoundBottomSheetPlayer(
    queue: QueueController,
    settings: SettingsStore,
    actionScope: CoroutineScope,
    controls: PlayerNativeControls,
    slots: PlayerSlotFactory,
    blurSupported: Boolean,
    visible: Boolean,
    resumed: Boolean,
    reducedMotion: Boolean,
    onLyricsVisibility: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    interaction: PlayerInteractionState = remember { PlayerInteractionState() },
) {
    key(queue, queue.player, settings, controls) {
        val native by controls.state.collectAsState()
        val onAction: (PlayerAction) -> Unit = remember(queue.player, controls) {
            { action: PlayerAction -> dispatchNativePlayerAction(queue.player, controls, action) }
        }
        // Volume is written only while the observed native owner reports a volume; the UI never invents one.
        val setVolume: (Float) -> Unit = remember(controls) {
            { volume: Float ->
                if (volume.isFinite() && controls.state.value.volume?.isFinite() == true) {
                    controls.setVolume(volume.coerceIn(0f, 1f))
                }
            }
        }
        ObservedBottomSheetPlayer(
            queue = queue,
            settings = settings,
            actionScope = actionScope,
            slots = slots,
            blurSupported = blurSupported,
            active = visible && resumed,
            decorateState = { it.withNativeState(native, reducedMotion) },
            onAction = onAction,
            setVolume = setVolume,
            onLyricsVisibility = onLyricsVisibility,
            modifier = modifier,
            interaction = interaction,
        )
    }
}
