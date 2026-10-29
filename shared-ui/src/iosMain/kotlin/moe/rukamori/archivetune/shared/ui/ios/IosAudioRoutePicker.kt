package moe.rukamori.archivetune.shared.ui.ios

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.viewinterop.UIKitInteropInteractionMode
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVKit.AVRoutePickerView
import platform.CoreGraphics.CGRectMake
import platform.UIKit.UIColor
import platform.UIKit.UIView

/** Exported through ArchiveTuneShared as well as callable from Kotlin; the active AVAudioSession owns routing. */
@OptIn(ExperimentalForeignApi::class)
fun createIosAudioRoutePicker(): UIView = AVRoutePickerView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0)).apply {
    prioritizesVideoDevices = false
}

@OptIn(ExperimentalForeignApi::class, ExperimentalComposeUiApi::class)
@Composable
internal fun IosAudioRoutePicker(modifier: Modifier, tint: Color, enabled: Boolean) {
    val activeTint = MaterialTheme.colorScheme.primary
    UIKitView(
        factory = ::createIosAudioRoutePicker,
        modifier = modifier,
        update = { view ->
            view.tintColor = tint.toUIColor()
            (view as AVRoutePickerView).activeTintColor = activeTint.toUIColor()
            view.userInteractionEnabled = enabled
            view.alpha = if (enabled) 1.0 else 0.38
        },
        // The OS control must receive its tap directly, and supplies its own localized accessibility actions.
        properties = UIKitInteropProperties(
            interactionMode = UIKitInteropInteractionMode.NonCooperative,
            isNativeAccessibilityEnabled = true,
        ),
    )
}

@OptIn(ExperimentalForeignApi::class)
private fun Color.toUIColor(): UIColor {
    val color = convert(ColorSpaces.Srgb)
    return UIColor(color.red.toDouble(), color.green.toDouble(), color.blue.toDouble(), color.alpha.toDouble())
}
