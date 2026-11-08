package moe.rukamori.archivetune.shared.ui.desktop

import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import moe.rukamori.archivetune.shared.ui.navigation.ShellNavigation
import moe.rukamori.archivetune.shared.ui.navigation.ShellTab
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * The rail's five destinations, the same five the reference desktop app keeps in its sidebar: Home, Library, History,
 * Statistics and Together. Search is not one of them: it lives in the title bar, on every page.
 *
 * Natune's navigation has three tab stacks, so a destination either selects a tab (Home, Library) or opens its route
 * in the Library stack (History, Statistics, Together). That keeps one back history per stack and lets Back return to
 * the library the user left, exactly as the tab bar does on the phone.
 */
enum class DesktopDestination(
    val label: StringResource,
    val icon: DrawableResource,
    val selectedIcon: DrawableResource,
    /**
     * The rail's own label: a 104 dp item fits a word rather than a sentence, so a destination whose name is longer
     * than its column carries its short form here. [label] stays the accessibility name.
     */
    val railLabel: StringResource = label,
) {
    Home(Res.string.m4_home, Res.drawable.m4_icon_home_outlined, Res.drawable.m4_icon_home_filled),
    Library(Res.string.m4_library, Res.drawable.m4_icon_library_outlined, Res.drawable.m4_icon_library_filled),
    History(Res.string.m4_history, Res.drawable.m4_icon_history, Res.drawable.m4_icon_history),
    Statistics(Res.string.m4_statistics, Res.drawable.m6_icon_stats, Res.drawable.m6_icon_stats),
    Together(
        Res.string.m4_listen_together,
        Res.drawable.m6_icon_listen_together,
        Res.drawable.m6_icon_listen_together,
        railLabel = Res.string.desktop_shell_rail_together,
    );

    /**
     * A rail click is a top-level navigation, so it pops the destination's back stack to its root instead of restoring
     * a detail page the user left. A `toRoot = false` call only reselects and bumps `reselection`, which leaves a
     * playlist or search-result detail on screen (and made Library unresponsive after History/Statistics/Together had
     * stacked routes). Re-clicking the current item therefore pops to its root too, like the desktop NavigationRail
     * semantics.
     */
    fun open(navigation: ShellNavigation) {
        when (this) {
            Home -> navigation.select(ShellTab.Home, toRoot = true)
            Library -> navigation.select(ShellTab.Library, toRoot = true)
            History -> openInLibraryStack(navigation, HISTORY_ROUTE)
            Statistics -> openInLibraryStack(navigation, STATISTICS_ROUTE)
            Together -> openInLibraryStack(navigation, TOGETHER_ROUTE)
        }
    }

    private fun openInLibraryStack(navigation: ShellNavigation, route: String) {
        // Pop to the library root before opening the destination, so one Back returns to it and stacking successive
        // rail destinations cannot bury the library behind several detail entries.
        navigation.select(ShellTab.Library, toRoot = true)
        navigation.open(route)
    }
}

/** Routes the rail opens. They are page routes, so the page host already renders them. */
const val HISTORY_ROUTE = "history"
const val STATISTICS_ROUTE = "stats"
const val TOGETHER_ROUTE = "listen_together"

/**
 * The destination the rail highlights for the current route. A detail page keeps its section highlighted: an album
 * opened from the library keeps Library lit rather than clearing every item.
 */
fun desktopSelectedDestination(tab: ShellTab, route: String): DesktopDestination {
    val path = route.substringBefore('?').substringBefore('#')
    return when {
        path == "home" -> DesktopDestination.Home
        path == HISTORY_ROUTE || path == "history_remote" -> DesktopDestination.History
        path == STATISTICS_ROUTE || path.startsWith("stats/") || path == "year_in_music" -> DesktopDestination.Statistics
        path.startsWith("listen_together") -> DesktopDestination.Together
        path == "search" || path.startsWith("search/") -> DesktopDestination.Library
        tab == ShellTab.Home -> DesktopDestination.Home
        else -> DesktopDestination.Library
    }
}

/** One rail entry: the icon, and the label only while the rail is expanded. */
@Composable
internal fun DesktopDestination.Icon(selected: Boolean, modifier: Modifier = Modifier) {
    Icon(
        painter = painterResource(if (selected) selectedIcon else icon),
        contentDescription = stringResource(label),
        modifier = modifier,
    )
}
