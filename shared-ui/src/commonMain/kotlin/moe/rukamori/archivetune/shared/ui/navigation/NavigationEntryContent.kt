/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */
package moe.rukamori.archivetune.shared.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier

/** Saveable scroll/draft state is per ENTRY, not route name or tab position. Popped entries are pruned. */
@Composable
fun NavigationEntryContent(
    navigation: ShellNavigation,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val holder = rememberSaveableStateHolder()
    val entryIds = navigation.entries.map { it.id }.toSet()
    var previousIds by remember { mutableStateOf(entryIds) }
    SideEffect {
        (previousIds - entryIds).forEach(holder::removeState)
        previousIds = entryIds
    }
    val backToMain = remember(navigation) { { navigation.backToMain() } }
    Box(modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalBackToMain provides backToMain) {
            holder.SaveableStateProvider(navigation.entry.id) {
                key(navigation.entry.id) { content() }
            }
        }
    }
}
