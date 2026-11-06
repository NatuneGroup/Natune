package moe.rukamori.archivetune.shared.ui.navigation

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Below this window width the desktop sidebar keeps its icon-only rail instead of the labelled column. */
val DesktopLabelledSidebarMinWidth = 1000.dp

/** Below this content width the desktop settings page falls back to the single-column list -> page flow. */
val DesktopSettingsTwoPaneMinWidth = 780.dp

/** The home carousel's sleeve size for a content width: the desktop feed fills more of the window than a phone does. */
fun desktopHomeCardWidth(contentWidth: Dp): Dp = when {
    contentWidth >= 1200.dp -> 160.dp
    contentWidth >= 900.dp -> 156.dp
    else -> 150.dp
}

/** The settings category pane's width for the content area it shares with the detail page. */
fun desktopSettingsNavWidth(contentWidth: Dp): Dp = when {
    contentWidth >= 1200.dp -> 320.dp
    contentWidth >= 1000.dp -> 272.dp
    else -> 240.dp
}
