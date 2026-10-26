package moe.rukamori.archivetune.shared.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.runtime.setValue

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
        CompositionLocalProvider(LocalBackToMain provides backToMain, LocalShellNavigation provides navigation) {
            holder.SaveableStateProvider(navigation.entry.id) {
                key(navigation.entry.id) { content() }
            }
        }
    }
}
