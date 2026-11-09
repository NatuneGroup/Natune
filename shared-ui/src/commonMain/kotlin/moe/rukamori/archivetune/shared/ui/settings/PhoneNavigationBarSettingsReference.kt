package moe.rukamori.archivetune.shared.ui.settings

import moe.rukamori.archivetune.shared.ui.resources.*

/** Android presentation order; regenerate with scripts/sync_phone_settings_reference.py. */
internal val PhoneNavigationBarSettingsReference = PhoneSettingsReference(Res.string.m6_settings_home_navigation_bar, listOf(
    PhoneSettingReference("group:general", Res.string.m6_account_general_group, null, PhoneSettingKind.HEADER, setOf("general"), null, setOf(Res.string.m6_account_general_group, Res.string.m6_integration_general, Res.string.m6_liquid_glass_general), false),
    PhoneSettingReference("hide_navigation_bar_labels", Res.string.ios_lane_nav_hide_labels, Res.drawable.phone_reference_nav_bar, PhoneSettingKind.TOGGLE, setOf("hide_navigation_bar_labels"), "hideNavigationBarLabels", setOf(Res.string.ios_lane_nav_hide_labels), false),
    PhoneSettingReference("navigation_bar_hide_on_scroll", Res.string.ios_lane_nav_hide_on_scroll, Res.drawable.phone_reference_nav_bar, PhoneSettingKind.TOGGLE, setOf("navigation_bar_hide_on_scroll"), "navigationBarHideOnScroll", setOf(Res.string.ios_lane_nav_hide_on_scroll), false),
))
