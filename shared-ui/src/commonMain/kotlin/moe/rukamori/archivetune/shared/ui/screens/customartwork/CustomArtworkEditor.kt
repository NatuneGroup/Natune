package moe.rukamori.archivetune.shared.ui.screens.customartwork

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/** The two per-song overrides: the replacement cover and the motion canvas. */
enum class CustomArtworkKind { COVER, CANVAS }

/**
 * The owner's last settled change. It is reported only after the store has accepted or refused the picked
 * file, so the screen never shows a save that has not happened.
 */
sealed interface CustomArtworkOutcome {
    data class Saved(val kind: CustomArtworkKind) : CustomArtworkOutcome
    data class Removed(val kind: CustomArtworkKind) : CustomArtworkOutcome
    data class TooLarge(val limitMb: Int) : CustomArtworkOutcome
    data object Unsupported : CustomArtworkOutcome
    data object Failed : CustomArtworkOutcome
}

/**
 * [hasCoverSource] is false when the song has no cover URL to override, which hides the cover rows exactly as
 * the baseline overflow section did. [hasCustomCover] and [hasCustomCanvas] report the stored overrides. [busy]
 * keeps every action disabled while a picked file is being stored.
 */
data class CustomArtworkEditUi(
    val hasCoverSource: Boolean,
    val hasCustomCover: Boolean,
    val hasCustomCanvas: Boolean,
    val busy: Boolean = false,
    val outcome: CustomArtworkOutcome? = null,
)

/**
 * [setCover] asks the host for an image picker and [setCanvas] for an image-or-video picker. The host stores the
 * chosen file, republishes the playing canvas when needed, and reports the result through
 * [CustomArtworkEditUi.outcome]. This screen never writes a file itself.
 */
class CustomArtworkEditActions(
    val setCover: () -> Unit,
    val setCanvas: () -> Unit,
    val removeCover: () -> Unit,
    val removeCanvas: () -> Unit,
    val dismiss: () -> Unit,
)

/**
 * The song's custom artwork editor. [preview] draws the current artwork at the modifier it is given; the host
 * supplies it so this module creates no image loader. Dismissal waits while a stored file is still in flight,
 * matching the baseline pickers that ignored taps while busy.
 */
@Composable
fun CustomArtworkEditDialog(
    state: CustomArtworkEditUi,
    actions: CustomArtworkEditActions,
    modifier: Modifier = Modifier,
    preview: (@Composable (Modifier) -> Unit)? = null,
) {
    val busy = state.busy
    AlertDialog(
        onDismissRequest = { if (!busy) actions.dismiss() },
        modifier = modifier.testTag("custom-artwork-editor"),
        title = { Text(stringResource(Res.string.m4_custom_artwork_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                preview?.invoke(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp)))
                if (state.hasCoverSource) {
                    CustomArtworkEditRow(Res.string.m4_custom_artwork_set_cover, Res.drawable.m4_icon_image,
                        enabled = !busy, tag = "custom-artwork-set-cover", onClick = actions.setCover)
                }
                CustomArtworkEditRow(Res.string.m4_custom_artwork_set_canvas, Res.drawable.m4_icon_motion_photos_on,
                    enabled = !busy, tag = "custom-artwork-set-canvas", onClick = actions.setCanvas)
                if (state.hasCustomCover) {
                    CustomArtworkEditRow(Res.string.m4_custom_artwork_remove_cover, Res.drawable.m4_icon_hide_image,
                        enabled = !busy, tag = "custom-artwork-remove-cover", onClick = actions.removeCover)
                }
                if (state.hasCustomCanvas) {
                    CustomArtworkEditRow(Res.string.m4_custom_artwork_remove_canvas, Res.drawable.m4_icon_delete,
                        enabled = !busy, tag = "custom-artwork-remove-canvas", onClick = actions.removeCanvas)
                }
                state.outcome?.let { outcome ->
                    Text(
                        text = customArtworkOutcomeText(outcome),
                        color = if (outcome.isFailure()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.testTag("custom-artwork-outcome"),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(actions.dismiss, enabled = !busy, modifier = Modifier.testTag("custom-artwork-close")) {
                Text(stringResource(Res.string.m4_close))
            }
        },
    )
}

@Composable
private fun CustomArtworkEditRow(
    label: StringResource,
    icon: DrawableResource,
    enabled: Boolean,
    tag: String,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(stringResource(label)) },
        leadingContent = { Icon(painter = painterResource(icon), contentDescription = null) },
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick).testTag(tag),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Composable
private fun customArtworkOutcomeText(outcome: CustomArtworkOutcome): String = when (outcome) {
    is CustomArtworkOutcome.Saved ->
        stringResource(
            if (outcome.kind == CustomArtworkKind.COVER) Res.string.m4_custom_artwork_cover_saved
            else Res.string.m4_custom_artwork_canvas_saved,
        )
    is CustomArtworkOutcome.Removed ->
        stringResource(
            if (outcome.kind == CustomArtworkKind.COVER) Res.string.m4_custom_artwork_cover_removed
            else Res.string.m4_custom_artwork_canvas_removed,
        )
    is CustomArtworkOutcome.TooLarge -> stringResource(Res.string.m4_custom_artwork_too_large, outcome.limitMb)
    CustomArtworkOutcome.Unsupported -> stringResource(Res.string.m4_custom_artwork_unsupported)
    CustomArtworkOutcome.Failed -> stringResource(Res.string.m4_custom_artwork_failed)
}

private fun CustomArtworkOutcome.isFailure(): Boolean =
    this !is CustomArtworkOutcome.Saved && this !is CustomArtworkOutcome.Removed
