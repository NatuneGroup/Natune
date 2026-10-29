package moe.rukamori.archivetune.ui.screens.settings

import android.content.Context
import androidx.compose.material3.ColorScheme
import androidx.navigation.NavController
import moe.rukamori.archivetune.R

internal const val LIQUID_GLASS_SETTINGS_SEARCH_KEY = "liquid_glass_settings"

/** Search index for Appearance → Liquid Glass customisation; hidden from the main list like the other sub-pages. */
internal fun liquidGlassSettingsItem(
    context: Context,
    navController: NavController,
    colorScheme: ColorScheme,
): SettingsItem =
    SettingsItem(
        key = LIQUID_GLASS_SETTINGS_SEARCH_KEY,
        icon = R.drawable.blur_on,
        title = context.getString(R.string.liquid_glass_settings_title),
        subtitle = context.getString(R.string.liquid_glass_customisation_subtitle),
        accentColor = colorScheme.secondary,
        keywords = listOf("liquid glass", "glass tuning", "glass customisation", "glass customization", "refraction", "glass blur"),
        onClick = { navController.navigate(LIQUID_GLASS_SETTINGS_ROUTE) },
        hidden = true,
        children = listOf(
            SettingsChild(context.getString(R.string.liquid_glass_intensity), "glass_intensity",
                listOf("intensity", "subtle", "standard", "vivid", "glass strength")),
            SettingsChild(context.getString(R.string.liquid_glass_refraction_height), "glass_refraction_height",
                listOf("refraction height", "lens height", "glass edge")),
            SettingsChild(context.getString(R.string.liquid_glass_refraction_amount), "glass_refraction_amount",
                listOf("refraction amount", "lens strength", "displacement")),
            SettingsChild(context.getString(R.string.liquid_glass_blur_radius), "glass_blur_radius",
                listOf("glass blur", "blur radius", "backdrop softness")),
            SettingsChild(context.getString(R.string.liquid_glass_tint_opacity), "glass_tint_opacity",
                listOf("glass tint", "glass opacity", "dimming")),
            SettingsChild(context.getString(R.string.liquid_glass_shadow_depth), "glass_shadow_depth",
                listOf("glass shadow", "drop shadow", "shadow depth")),
            SettingsChild(context.getString(R.string.liquid_glass_depth_3d), "glass_depth_3d",
                listOf("3d depth", "depth effect", "spatial depth")),
            SettingsChild(context.getString(R.string.liquid_glass_chromatic_aberration), "glass_chromatic_aberration",
                listOf("chromatic aberration", "colour fringing", "color fringing", "prism")),
            SettingsChild(context.getString(R.string.liquid_glass_backdrop_vibrancy), "glass_backdrop_vibrancy",
                listOf("vibrancy", "saturation", "glass colour")),
            SettingsChild(context.getString(R.string.liquid_glass_adaptive_luminance), "glass_adaptive_luminance",
                listOf("adaptive luminance", "glass brightness", "glass contrast")),
            SettingsChild(context.getString(R.string.liquid_glass_reset), "glass_reset",
                listOf("reset glass", "default glass", "restore glass")),
        ),
    )
