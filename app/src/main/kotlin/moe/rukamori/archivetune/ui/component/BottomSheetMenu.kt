/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.isUnspecified
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.constants.BackAnimationStyle

val LocalMenuState = compositionLocalOf { MenuState() }

@Stable
class MenuState(
    isVisible: Boolean = false,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    var isVisible by mutableStateOf(isVisible)
    var content by mutableStateOf(content)
    internal var dialogContent by mutableStateOf<(@Composable () -> Unit)?>(null)
        private set

    @OptIn(ExperimentalMaterial3Api::class)
    fun show(content: @Composable ColumnScope.() -> Unit) {
        dialogContent = null
        isVisible = true
        this.content = content
    }

    @OptIn(ExperimentalMaterial3Api::class)
    fun dismiss() {
        isVisible = false
    }

    fun showDialog(content: @Composable () -> Unit) {
        isVisible = false
        dialogContent = content
    }

    fun dismissDialog() {
        dialogContent = null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BottomSheetMenu(
    modifier: Modifier = Modifier,
    state: MenuState,
    // Unspecified lets the menu use liquid glass when the host records a menu backdrop, and the
    // theme surface otherwise; a specified colour always wins.
    background: Color = Color.Unspecified,
) {
    val focusManager = LocalFocusManager.current
    val predictiveBack = LocalBackAnimationStyle.current == BackAnimationStyle.PREDICTIVE

    state.dialogContent?.invoke()

    if (state.isVisible) {
        val sheetState = rememberModalBottomSheetState()
        val scope = rememberCoroutineScope()
        val backState = rememberPredictiveSheetBackState()
        val sheetShape = BottomSheetDefaults.ExpandedShape
        val glassSource = LocalMenuGlassBackdrop.current?.takeIf { background.isUnspecified }
        val useGlass = liquidGlassEligible(glassSource)
        val baseScheme = MaterialTheme.colorScheme
        val dark = baseScheme.surface.luminance() < 0.5f
        val glassTint = menuGlassTint(baseScheme, dark, LocalLiquidGlassTuning.current.tintFactor)
        val drawGlassTint: DrawScope.() -> Unit = remember(glassTint) { { drawRect(glassTint) } }
        val sheetScheme = if (useGlass) remember(baseScheme, dark) { menuGlassColorScheme(baseScheme, dark) } else baseScheme
        val glassModifier =
            if (useGlass) {
                Modifier.liquidGlassMaterial(
                    backdrop = glassSource,
                    shape = sheetShape,
                    role = LiquidGlassRole.ActionMenu,
                    onDrawSurface = drawGlassTint,
                )
            } else {
                Modifier
            }
        ModalBottomSheet(
            onDismissRequest = {
                focusManager.clearFocus()
                state.isVisible = false
            },
            sheetState = sheetState,
            shape = sheetShape,
            containerColor =
                when {
                    useGlass -> Color.Transparent
                    background.isSpecified -> background
                    else -> baseScheme.surface
                },
            contentColor = sheetScheme.onSurface,
            dragHandle = {
                Box(
                    modifier =
                        Modifier
                            .padding(vertical = 12.dp)
                            .size(width = 40.dp, height = 4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(sheetScheme.onSurfaceVariant.copy(alpha = 0.4f)),
                )
            },
            modifier =
                modifier
                    .fillMaxHeight()
                    .then(
                        if (predictiveBack) {
                            Modifier.graphicsLayer {
                                val progress = backState.progress.value
                                transformOrigin = TransformOrigin(0.5f, 1f)
                                scaleX = 1f - progress * 0.1f
                                scaleY = scaleX
                                alpha = 1f - progress * 0.6f
                            }
                        } else {
                            Modifier
                        },
                    ).then(glassModifier),
        ) {
            // Material3's native sheet back handling only scales the sheet. In PREDICTIVE mode
            // this handler, composed inside the sheet's dialog after Material3's own, takes the
            // gesture so the menu scales and fades with it; ORIGINAL keeps the native behaviour.
            if (predictiveBack) {
                PredictiveStyleBackHandler(backState = backState, enabled = true) {
                    focusManager.clearFocus()
                    scope.launch { sheetState.hide() }.invokeOnCompletion { state.isVisible = false }
                }
            }
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth(),
                // No horizontal padding here: 20dp gutters on both sides made the bottom sheet
                // look like a narrow centred column, so the menu opens full-screen width by
                // default. The MenuSurfaceSection cards inside still have their own rounded
                // corners (MaterialTheme.shapes.extraLarge) and look fine edge-to-edge.
            ) {
                if (useGlass) MaterialTheme(colorScheme = sheetScheme) { state.content(this) } else state.content(this)
            }
        }
    }
}
