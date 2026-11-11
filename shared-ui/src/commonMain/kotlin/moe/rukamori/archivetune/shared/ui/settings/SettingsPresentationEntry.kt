package moe.rukamori.archivetune.shared.ui.settings

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.painterResource

internal val LocalPhoneSettingsRoot = staticCompositionLocalOf { false }

@Composable
internal fun SettingsPresentationEntry(
    reference: PhoneSettingReference?,
    phone: Boolean,
    index: Int,
    count: Int,
    enabled: Boolean,
    headlineContent: @Composable () -> Unit,
    supportingContent: @Composable () -> Unit,
    trailingContent: @Composable () -> Unit,
    modifier: Modifier,
    interactionSource: MutableInteractionSource? = null,
) {
    if (!phone) {
        ListItem(headlineContent = headlineContent, supportingContent = supportingContent,
            trailingContent = trailingContent, modifier = modifier)
    } else {
        PhonePreferenceEntry(headlineContent, supportingContent, {
            Row {
                trailingContent()
                if (!LocalPhoneSettingsRoot.current && (reference?.kind == PhoneSettingKind.VALUE || reference?.kind == PhoneSettingKind.ACTION)) {
                    Icon(painterResource(Res.drawable.m6_icon_arrow_forward), null, Modifier.size(22.dp))
                }
            }
        }, reference?.icon, index, count, enabled, modifier, interactionSource)
    }
}
