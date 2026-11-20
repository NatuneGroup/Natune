/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package app.natune.ui.utils

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.natune.R
import app.natune.acoustics.auditKey
import app.natune.acoustics.rememberAcousticAuditOwner
import app.natune.shared.domain.dsp.acoustic.AcousticAuditTarget
import app.natune.ui.component.DefaultDialog
import app.natune.ui.component.LocalMenuState

@Composable
fun AcousticQualityDialog(target: AcousticAuditTarget, onDismiss: () -> Unit) {
    val bound = remember(target) { target.auditKey() }
    val owner = rememberAcousticAuditOwner(bound)
    val dismiss = { owner.close(); onDismiss() }
    DefaultDialog(
        onDismiss = dismiss,
        modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(),
        title = { Text(stringResource(R.string.acoustic_quality_title)) },
        horizontalAlignment = Alignment.Start,
        contentScrollable = true,
        buttons = {
            TextButton(onClick = dismiss) { Text(stringResource(R.string.acoustic_quality_close)) }
        },
    ) {
        AcousticQualityContent(bound, owner)
    }
}

@Composable
internal fun AcousticQualityMenuAction(target: AcousticAuditTarget, onDismiss: () -> Unit) {
    val menuState = LocalMenuState.current
    ListItem(
        headlineContent = { Text(stringResource(R.string.acoustic_quality_title)) },
        leadingContent = { Icon(painterResource(R.drawable.graphic_eq), contentDescription = null) },
        modifier = Modifier.clickable {
            onDismiss()
            menuState.showDialog {
                AcousticQualityDialog(target = target, onDismiss = menuState::dismissDialog)
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}
