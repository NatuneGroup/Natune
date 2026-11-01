@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package moe.rukamori.archivetune.shared.ui.ios

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.min
import kotlinx.cinterop.useContents
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.shared.domain.customartwork.CustomArtworkLimits
import moe.rukamori.archivetune.shared.domain.settings.PlayerCustomBlurKey
import moe.rukamori.archivetune.shared.domain.settings.PlayerCustomBrightnessKey
import moe.rukamori.archivetune.shared.domain.settings.PlayerCustomContrastKey
import moe.rukamori.archivetune.shared.domain.settings.PlayerCustomImageUriKey
import moe.rukamori.archivetune.shared.ui.IosHost
import moe.rukamori.archivetune.shared.ui.resources.Res
import moe.rukamori.archivetune.shared.ui.resources.ios_player_background_blur
import moe.rukamori.archivetune.shared.ui.resources.ios_player_background_brightness
import moe.rukamori.archivetune.shared.ui.resources.ios_player_background_choose
import moe.rukamori.archivetune.shared.ui.resources.ios_player_background_contrast
import moe.rukamori.archivetune.shared.ui.resources.ios_player_background_missing
import moe.rukamori.archivetune.shared.ui.resources.ios_player_background_none
import moe.rukamori.archivetune.shared.ui.resources.ios_player_background_remove
import moe.rukamori.archivetune.shared.ui.resources.ios_player_background_reset
import moe.rukamori.archivetune.shared.ui.resources.ios_player_background_title
import moe.rukamori.archivetune.shared.ui.settings.SettingsController
import org.jetbrains.compose.resources.stringResource
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask
import platform.Foundation.writeToFile
import platform.UIKit.UIGraphicsImageRenderer
import platform.UIKit.UIGraphicsImageRendererFormat
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.UIKit.UIImagePickerController
import platform.UIKit.UIImagePickerControllerDelegateProtocol
import platform.UIKit.UIImagePickerControllerOriginalImage
import platform.UIKit.UIImagePickerControllerSourceType
import platform.UIKit.UINavigationControllerDelegateProtocol
import platform.UIKit.UIViewController
import platform.darwin.NSObject

/**
 * The player's custom background on this host: one photo from the photo library, downscaled and stored as a JPEG in
 * the app container, kept by its file URI. The document picker is not used because its bookmark is not durable, and
 * the picker runs out of process so no photo-library permission is requested.
 */
internal object IosPlayerBackground {
    private const val FILE_NAME = "player-background.jpg"
    private const val MAX_DIMENSION = 2048.0
    private val JPEG_QUALITIES = listOf(0.9, 0.75, 0.6)

    private fun path(): String {
        val manager = NSFileManager.defaultManager
        val support = (manager.URLsForDirectory(NSApplicationSupportDirectory, NSUserDomainMask).first() as NSURL).path
            ?: error("Application Support is unavailable")
        manager.createDirectoryAtPath(support, true, null, null)
        return "$support/$FILE_NAME"
    }

    /** Whether the stored-image URI still names a readable file. */
    fun readable(uri: String): Boolean {
        val path = NSURL.URLWithString(uri)?.takeIf { it.scheme == "file" }?.path ?: return false
        return NSFileManager.defaultManager.fileExistsAtPath(path)
    }

    /** Lets the user pick one photo. Null when they cancel or the picker cannot be shown. */
    suspend fun pick(presenter: UIViewController): UIImage? = withContext(Dispatchers.Main) {
        if (!UIImagePickerController.isSourceTypeAvailable(
                UIImagePickerControllerSourceType.UIImagePickerControllerSourceTypePhotoLibrary,
            )
        ) {
            return@withContext null
        }
        suspendCancellableCoroutine { continuation ->
            val picker = UIImagePickerController()
            picker.sourceType = UIImagePickerControllerSourceType.UIImagePickerControllerSourceTypePhotoLibrary
            // The original keeps its aspect: the background is not a square like the room avatar.
            picker.allowsEditing = false
            // The picker holds its delegate weakly; the cancellation handler below refers to it, so it lives as long as the pick.
            val delegate = object : NSObject(), UIImagePickerControllerDelegateProtocol, UINavigationControllerDelegateProtocol {
                override fun imagePickerController(
                    picker: UIImagePickerController,
                    didFinishPickingMediaWithInfo: Map<Any?, *>,
                ) {
                    val image = didFinishPickingMediaWithInfo[UIImagePickerControllerOriginalImage] as? UIImage
                    picker.dismissViewControllerAnimated(true, null)
                    if (continuation.isActive) continuation.resume(image)
                }

                override fun imagePickerControllerDidCancel(picker: UIImagePickerController) {
                    picker.dismissViewControllerAnimated(true, null)
                    if (continuation.isActive) continuation.resume(null)
                }
            }
            picker.delegate = delegate
            continuation.invokeOnCancellation {
                picker.dismissViewControllerAnimated(false, null)
                if (picker.delegate === delegate) picker.delegate = null
            }
            presenter.presentViewController(picker, animated = true, completion = null)
        }
    }

    /**
     * Downscales [image] to the background bound, stores it as the one custom background and returns its file URI.
     * Null when it cannot be drawn, encoded small enough, or written; the stored image is then unchanged.
     */
    suspend fun store(image: UIImage): String? = withContext(Dispatchers.Default) {
        val bytes = encode(image) ?: return@withContext null
        if (!bytes.writeToFile(path(), atomically = true)) return@withContext null
        NSURL.fileURLWithPath(path()).absoluteString
    }

    /** Removes the stored copy; the preference row clears its key separately. */
    fun removeStored() {
        NSFileManager.defaultManager.removeItemAtPath(path(), null)
    }

    private fun encode(image: UIImage): NSData? {
        val (width, height) = image.size.useContents { width to height }
        if (width <= 0.0 || height <= 0.0) return null
        val scale = min(1.0, MAX_DIMENSION / max(width, height))
        val targetWidth = max(1.0, width * scale)
        val targetHeight = max(1.0, height * scale)
        // The renderer draws at the screen's scale by default, which would triple the stored pixels.
        val format = UIGraphicsImageRendererFormat.defaultFormat().apply { this.scale = 1.0 }
        val renderer = UIGraphicsImageRenderer(size = CGSizeMake(targetWidth, targetHeight), format = format)
        val scaled = renderer.imageWithActions { _ ->
            image.drawInRect(CGRectMake(0.0, 0.0, targetWidth, targetHeight))
        }
        for (quality in JPEG_QUALITIES) {
            val data = UIImageJPEGRepresentation(scaled, quality) ?: continue
            if (data.length.toLong() in 1..CustomArtworkLimits.MAX_COVER_BYTES) return data
        }
        return null
    }
}

/**
 * The player's custom background settings: the same blur, contrast and brightness ranges as the desktop page, which
 * the player reads from the same keys.
 */
@Composable
internal fun IosCustomBackgroundPage(
    host: IosHost,
    graph: IosSessionGraph,
    controller: SettingsController,
    onFailure: () -> Unit,
) {
    val snapshot by controller.store.snapshots.collectAsState()
    val preferences = snapshot
    if (preferences == null) {
        CircularProgressIndicator()
        return
    }
    val scope = rememberCoroutineScope()
    fun write(change: suspend () -> Unit) {
        scope.launch {
            try {
                change()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                onFailure()
            }
        }
    }
    val uri = preferences[PlayerCustomImageUriKey, ""]
    val readable = remember(uri) { uri.isBlank() || IosPlayerBackground.readable(uri) }
    var blur by remember(preferences[PlayerCustomBlurKey, 0f]) { mutableStateOf(preferences[PlayerCustomBlurKey, 0f]) }
    var contrast by remember(preferences[PlayerCustomContrastKey, 1f]) {
        mutableStateOf(preferences[PlayerCustomContrastKey, 1f])
    }
    var brightness by remember(preferences[PlayerCustomBrightnessKey, 1f]) {
        mutableStateOf(preferences[PlayerCustomBrightnessKey, 1f])
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(Res.string.ios_player_background_title), style = MaterialTheme.typography.headlineSmall)
        if (uri.isBlank()) {
            Text(stringResource(Res.string.ios_player_background_none), style = MaterialTheme.typography.bodyMedium)
        } else {
            Box(Modifier.widthIn(max = 360.dp).fillMaxWidth().aspectRatio(16f / 9f)) {
                IosRemoteArtwork(uri, graph.artworkLoader, Modifier.fillMaxSize())
            }
            if (!readable) {
                Text(stringResource(Res.string.ios_player_background_missing), color = MaterialTheme.colorScheme.error)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                write {
                    val image = IosPlayerBackground.pick(host.viewController)
                    val stored = image?.let { IosPlayerBackground.store(it) }
                    if (stored != null) controller.edit { this[PlayerCustomImageUriKey] = stored }
                }
            }) { Text(stringResource(Res.string.ios_player_background_choose)) }
            if (uri.isNotBlank()) {
                OutlinedButton(onClick = {
                    write {
                        controller.edit { this[PlayerCustomImageUriKey] = null }
                        IosPlayerBackground.removeStored()
                    }
                }) { Text(stringResource(Res.string.ios_player_background_remove)) }
            }
        }
        IosBackgroundSlider(Res.string.ios_player_background_blur, blur, 0f..50f, { blur = it }) {
            write { controller.edit { this[PlayerCustomBlurKey] = blur } }
        }
        IosBackgroundSlider(Res.string.ios_player_background_contrast, contrast, 0.5f..2f, { contrast = it }) {
            write { controller.edit { this[PlayerCustomContrastKey] = contrast } }
        }
        IosBackgroundSlider(Res.string.ios_player_background_brightness, brightness, 0.5f..2f, { brightness = it }) {
            write { controller.edit { this[PlayerCustomBrightnessKey] = brightness } }
        }
        TextButton(onClick = {
            write {
                controller.edit {
                    this[PlayerCustomBlurKey] = null
                    this[PlayerCustomContrastKey] = null
                    this[PlayerCustomBrightnessKey] = null
                }
            }
        }) { Text(stringResource(Res.string.ios_player_background_reset)) }
    }
}

@Composable
private fun IosBackgroundSlider(
    label: org.jetbrains.compose.resources.StringResource,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    onDone: () -> Unit,
) {
    Column {
        Text("${stringResource(label)}: ${iosSliderValue(value)}", style = MaterialTheme.typography.bodyLarge)
        Slider(value = value.coerceIn(range), onValueChange = onChange, valueRange = range, onValueChangeFinished = onDone)
    }
}

/** Two decimals, like the desktop slider label, without java.text. */
private fun iosSliderValue(value: Float): String {
    val rounded = kotlin.math.round(value * 100) / 100f
    val text = rounded.toString()
    val dot = text.indexOf('.')
    return if (dot < 0) "$text.00" else text.padEnd(dot + 3, '0')
}
