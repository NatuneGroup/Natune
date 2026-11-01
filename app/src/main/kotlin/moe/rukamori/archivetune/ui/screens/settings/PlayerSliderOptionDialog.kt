package moe.rukamori.archivetune.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.constants.SliderStyle
import moe.rukamori.archivetune.ui.component.DefaultDialog

/**
 * The Appearance > Player seek-style picker; extracted from appearancePlayerRows unchanged.
 */
@Composable
internal fun PlayerSliderOptionDialog(
    sliderStyle: SliderStyle,
    onSliderStyleChange: (SliderStyle) -> Unit,
    onDismiss: () -> Unit,
) {
    val sliderStyles =
        remember {
            listOf(
                SliderStyle.Standard,
                SliderStyle.Wavy,
                SliderStyle.Thick,
                SliderStyle.Circular,
                SliderStyle.Simple,
            )
        }
    DefaultDialog(
        buttons = {
            TextButton(
                onClick = onDismiss,
                shapes = ButtonDefaults.shapes(),
            ) {
                Text(text = stringResource(android.R.string.cancel))
            }
        },
        onDismiss = onDismiss,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            sliderStyles.chunked(3).forEach { styleRow ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    styleRow.forEach { style ->
                        SliderStyleOptionCard(
                            sliderStyle = style,
                            selected = sliderStyle == style,
                            onClick = {
                                onSliderStyleChange(style)
                                onDismiss()
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(3 - styleRow.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}
