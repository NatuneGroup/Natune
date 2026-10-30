package moe.rukamori.archivetune.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ProvidedValue
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import moe.rukamori.archivetune.constants.PlayerHeaderGlassFadeRamp
import moe.rukamori.archivetune.constants.SheetOverlayEpsilon
import moe.rukamori.archivetune.ui.component.BottomSheetState
import moe.rukamori.archivetune.ui.component.LocalMenuGlassBackdrop
import moe.rukamori.archivetune.ui.component.MENU_GLASS_EXIT_LATCH_MS
import moe.rukamori.archivetune.ui.component.rememberThrottledLayerBackdrop
import moe.rukamori.archivetune.ui.component.throttledLayerBackdrop

/** True while the player sheet is raised above its collapsed mini-player position. */
val LocalPlayerSheetOverlayActive = compositionLocalOf { false }

/**
 * How far the rising player sheet has covered the top header zone, 0 to 1. Held as a State so
 * readers in draw or layer blocks follow every frame of the drag without recomposing.
 */
val LocalPlayerSheetOverlayFraction = compositionLocalOf<State<Float>> { mutableStateOf(0f) }

/** Height of the zone below the status bar that holds detail-screen header pills. */
private val PlayerHeaderPillZone = 60.dp

internal fun playerSheetOverlayActive(sheetValue: Dp, collapsedBound: Dp): Boolean =
    sheetValue > collapsedBound + SheetOverlayEpsilon

/**
 * 0 until the sheet's top edge comes within [PlayerHeaderGlassFadeRamp] of the header pill zone,
 * then rising linearly to 1 as the edge reaches the bottom of that zone.
 */
internal fun playerSheetOverlayFraction(
    sheetValue: Dp,
    collapsedBound: Dp,
    maxHeight: Dp,
    statusBarTop: Dp,
): Float {
    if (!playerSheetOverlayActive(sheetValue, collapsedBound)) return 0f
    val sheetEdgeFromTop = maxHeight - sheetValue
    val pillZoneBottom = statusBarTop + PlayerHeaderPillZone
    val fadeStart = pillZoneBottom + PlayerHeaderGlassFadeRamp
    return ((fadeStart - sheetEdgeFromTop) / (fadeStart - pillZoneBottom)).coerceIn(0f, 1f)
}

/** The root-level glass state the host provides and the modifier that records the menu backdrop. */
class RootGlassOverlay internal constructor(
    val providedValues: Array<ProvidedValue<*>>,
    val menuRecordingModifier: Modifier,
)

@Composable
fun rememberRootGlassOverlay(
    liquidGlassActive: Boolean,
    menuVisible: Boolean,
    playerSheetState: BottomSheetState,
    statusBarTop: Dp,
    maxHeight: Dp,
): RootGlassOverlay {
    val menuBackdrop = if (liquidGlassActive) rememberThrottledLayerBackdrop() else null
    var exitLatch by remember { mutableStateOf(false) }
    LaunchedEffect(menuVisible) {
        if (menuVisible) {
            exitLatch = true
        } else {
            delay(MENU_GLASS_EXIT_LATCH_MS)
            exitLatch = false
        }
    }
    // Read synchronously so the menu's first composition already finds a recording backdrop.
    val menuRecording = menuBackdrop != null && (menuVisible || exitLatch)
    val overlayActive by remember(playerSheetState) {
        derivedStateOf { playerSheetOverlayActive(playerSheetState.value, playerSheetState.collapsedBound) }
    }
    val overlayFraction =
        remember(playerSheetState, statusBarTop, maxHeight) {
            derivedStateOf {
                playerSheetOverlayFraction(playerSheetState.value, playerSheetState.collapsedBound, maxHeight, statusBarTop)
            }
        }
    return RootGlassOverlay(
        providedValues =
            arrayOf(
                // Null while nothing records it, so the menu never samples an empty layer.
                LocalMenuGlassBackdrop provides menuBackdrop.takeIf { menuRecording },
                LocalPlayerSheetOverlayActive provides overlayActive,
                LocalPlayerSheetOverlayFraction provides overlayFraction,
            ),
        menuRecordingModifier =
            if (menuBackdrop != null && menuRecording) Modifier.throttledLayerBackdrop(menuBackdrop) else Modifier,
    )
}
