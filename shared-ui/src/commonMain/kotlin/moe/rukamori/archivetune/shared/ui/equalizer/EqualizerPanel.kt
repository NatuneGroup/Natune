package moe.rukamori.archivetune.shared.ui.equalizer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.domain.dsp.eq.EqualizerBand
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * The equalizer panel a host with a band source mounts: the on/off switch, the band graph and a reset to flat.
 * The host owns the bands and the saving. [onGainChange] is the live path, applied while a handle moves, and
 * [onGainChangeFinished] is the single save at the end of a drag.
 */
@Composable
fun EqualizerPanel(
    enabled: Boolean,
    bands: List<EqualizerBand>,
    onEnabledChange: (Boolean) -> Unit,
    onGainChange: (index: Int, gainDb: Double) -> Unit,
    onGainChangeFinished: (index: Int, gainDb: Double) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.large,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(Res.string.equalizer_panel_title),
                    style = MaterialTheme.typography.titleLarge,
                )
                Switch(checked = enabled, onCheckedChange = onEnabledChange)
            }
            EqualizerGraph(
                bands = bands,
                onGainChange = onGainChange,
                onGainChangeFinished = onGainChangeFinished,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
            TextButton(onClick = onReset) {
                Text(text = stringResource(Res.string.equalizer_panel_reset))
            }
        }
    }
}
