package moe.rukamori.archivetune.ui.screens.settings

import android.os.Build
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.MiniPlayerBackgroundStyle
import moe.rukamori.archivetune.shared.domain.settings.MiniPlayerMaterialBlock
import moe.rukamori.archivetune.shared.domain.settings.miniPlayerMaterialBlock
import moe.rukamori.archivetune.shared.domain.settings.MiniPlayerBackgroundStyle as SharedMaterial
import moe.rukamori.archivetune.ui.component.EnumListPreference

internal fun miniPlayerMaterialDisabledReason(
    style: MiniPlayerBackgroundStyle,
    blurDisabled: Boolean,
    supported: Boolean,
    liquidGlassEnabled: Boolean,
): Int? = when (miniPlayerMaterialBlock(SharedMaterial.valueOf(style.name), blurDisabled, supported, liquidGlassEnabled)) {
    MiniPlayerMaterialBlock.BLUR_DISABLED -> R.string.blur_setting_overridden
    MiniPlayerMaterialBlock.UNSUPPORTED -> R.string.settings_material_unsupported
    MiniPlayerMaterialBlock.GLASS_DISABLED -> R.string.settings_liquid_glass_required
    null -> null
}

@Composable
internal fun MiniPlayerMaterialPreference(
    selected: MiniPlayerBackgroundStyle,
    onSelected: (MiniPlayerBackgroundStyle) -> Unit,
    blurDisabled: Boolean,
    liquidGlassEnabled: Boolean,
) {
    fun reason(style: MiniPlayerBackgroundStyle): Int? =
        miniPlayerMaterialDisabledReason(style, blurDisabled, Build.VERSION.SDK_INT >= Build.VERSION_CODES.S, liquidGlassEnabled)
    EnumListPreference(
        title = { Text(stringResource(R.string.mini_player_background_style)) },
        icon = { Icon(painterResource(R.drawable.gradient), null) },
        selectedValue = selected,
        onValueSelected = { if (reason(it) == null) onSelected(it) },
        valueText = {
            stringResource(when (it) {
                MiniPlayerBackgroundStyle.THEME -> R.string.follow_theme
                MiniPlayerBackgroundStyle.GRADIENT -> R.string.gradient
                MiniPlayerBackgroundStyle.GLOW -> R.string.glow
                MiniPlayerBackgroundStyle.FROSTED -> R.string.frosted_blur
                MiniPlayerBackgroundStyle.LIQUID_GLASS -> R.string.liquid_glass
            })
        },
        isValueEnabled = { reason(it) == null },
        valueDescription = { reason(it)?.let { resource -> stringResource(resource) }.orEmpty() },
    )
    reason(selected)?.let { resource ->
        Text(stringResource(resource), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 56.dp, top = 4.dp, end = 16.dp))
    }
}
