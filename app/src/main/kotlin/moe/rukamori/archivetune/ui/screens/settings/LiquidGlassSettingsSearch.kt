package moe.rukamori.archivetune.ui.screens.settings

import androidx.compose.material3.ColorScheme
import androidx.navigation.NavController
import moe.rukamori.archivetune.R

internal const val LIQUID_GLASS_SETTINGS_SEARCH_KEY = "liquid_glass_settings"

/** Search index for Appearance → Liquid Glass customisation; hidden from the main list like the other sub-pages. */
internal fun liquidGlassSettingsItem(navController: NavController, colorScheme: ColorScheme): SettingsItem =
    SettingsItem(
        key = LIQUID_GLASS_SETTINGS_SEARCH_KEY,
        icon = R.drawable.blur_on,
        title = "Liquid Glass customisation",
        subtitle = "Refraction, blur, tint and optical quality",
        accentColor = colorScheme.secondary,
        keywords = listOf("liquid glass", "glass tuning", "glass customisation", "glass customization", "refraction", "glass blur"),
        onClick = { navController.navigate(LIQUID_GLASS_SETTINGS_ROUTE) },
        hidden = true,
        children = listOf(
            SettingsChild("Glass intensity", "glass_intensity", listOf("intensity", "subtle", "standard", "vivid", "glass strength")),
            SettingsChild("Refraction height", "glass_refraction_height", listOf("refraction height", "lens height", "glass edge")),
            SettingsChild("Refraction amount", "glass_refraction_amount", listOf("refraction amount", "lens strength", "displacement")),
            SettingsChild("Blur radius", "glass_blur_radius", listOf("glass blur", "blur radius", "backdrop softness")),
            SettingsChild("Tint and opacity level", "glass_tint_opacity", listOf("glass tint", "glass opacity", "dimming")),
            SettingsChild("Shadow depth", "glass_shadow_depth", listOf("glass shadow", "drop shadow", "shadow depth")),
            SettingsChild("3D depth effect", "glass_depth_3d", listOf("3d depth", "depth effect", "spatial depth")),
            SettingsChild("Chromatic aberration", "glass_chromatic_aberration", listOf("chromatic aberration", "colour fringing", "color fringing", "prism")),
            SettingsChild("Backdrop vibrancy", "glass_backdrop_vibrancy", listOf("vibrancy", "saturation", "glass colour")),
            SettingsChild("Adaptive luminance", "glass_adaptive_luminance", listOf("adaptive luminance", "glass brightness", "glass contrast")),
            SettingsChild("Reset glass parameters", "glass_reset", listOf("reset glass", "default glass", "restore glass")),
        ),
    )
