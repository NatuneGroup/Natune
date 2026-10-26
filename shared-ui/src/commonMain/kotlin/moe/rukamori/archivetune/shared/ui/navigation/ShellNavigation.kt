package moe.rukamori.archivetune.shared.ui.navigation

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import moe.rukamori.archivetune.shared.domain.settings.*
import androidx.compose.runtime.setValue

enum class ShellTab(val route: String) { Home("home"), Search("search"), Library("library") }
data class ShellEntry(val id: Long, val route: String)
data class ShellNavigationSnapshot(
    val selectedTab: ShellTab,
    val stacks: Map<ShellTab, List<ShellEntry>>,
    val nextEntryId: Long,
)

/** UI back stacks only. Repository, queue and playback state remain with their existing owners. */
@Stable
class ShellNavigation(initialTab: ShellTab = ShellTab.Home) {
    var selectedTab by mutableStateOf(initialTab)
        private set
    private var nextId = ShellTab.entries.size.toLong()
    private val stacks = mutableStateMapOf<ShellTab, List<ShellEntry>>().apply {
        ShellTab.entries.forEachIndexed { index, tab -> put(tab, listOf(ShellEntry(index.toLong(), tab.route))) }
    }
    val entry: ShellEntry get() = stacks.getValue(selectedTab).last()
    val route: String get() = entry.route
    val entries: List<ShellEntry> get() = ShellTab.entries.flatMap { stacks.getValue(it) }
    val canGoBack: Boolean get() = stacks.getValue(selectedTab).size > 1
    var reselection by mutableLongStateOf(0)
        private set

    /** Tab items restore their stack; compact round buttons explicitly request [toRoot]. */
    fun select(tab: ShellTab) = select(tab, toRoot = selectedTab == tab)

    fun select(tab: ShellTab, toRoot: Boolean) {
        if (toRoot) stacks[tab] = stacks.getValue(tab).take(1)
        if (selectedTab == tab) reselection++
        selectedTab = tab
    }

    fun open(route: String) {
        require(route.isNotBlank())
        if (this.route != route) {
            check(nextId < Long.MAX_VALUE)
            stacks[selectedTab] = stacks.getValue(selectedTab) + ShellEntry(nextId++, route)
        }
    }

    fun back(): Boolean {
        if (!canGoBack) return false
        stacks[selectedTab] = stacks.getValue(selectedTab).dropLast(1)
        return true
    }

    /** Request tokens come from the entry counter, so they stay unique across snapshot and restore. */
    fun nextRequestToken(): Long {
        check(nextId < Long.MAX_VALUE)
        return nextId++
    }

    /**
     * Reuses the newest entry that [matches] rather than stacking a duplicate: entries above it are dropped and its route
     * is rewritten in place, keeping its id so that screen's saved state survives. Opens [route] when none matches.
     */
    fun reopen(route: String, matches: (String) -> Boolean) {
        require(route.isNotBlank())
        val stack = stacks.getValue(selectedTab)
        val index = stack.indexOfLast { matches(it.route) }
        if (index < 1) return open(route)
        stacks[selectedTab] = stack.subList(0, index) + stack[index].copy(route = route)
    }

    /** Long-press back-to-main: keeps only the selected tab's root, as the baseline backToMain pops to the main screen. */
    fun backToMain() {
        stacks[selectedTab] = stacks.getValue(selectedTab).take(1)
    }

    fun snapshot(): ShellNavigationSnapshot = ShellNavigationSnapshot(selectedTab, stacks.toMap(), nextId)

    companion object {
        fun restore(snapshot: ShellNavigationSnapshot): ShellNavigation {
            require(snapshot.stacks.keys == ShellTab.entries.toSet())
            require(snapshot.stacks.all { (tab, stack) -> stack.isNotEmpty() && stack.first().route == tab.route })
            val entries = snapshot.stacks.values.flatten()
            require(entries.all { it.id >= 0 && it.route.isNotBlank() })
            require(entries.map { it.id }.toSet().size == entries.size)
            require(snapshot.nextEntryId > entries.maxOf { it.id })
            return ShellNavigation(snapshot.selectedTab).apply {
                stacks.clear()
                stacks.putAll(snapshot.stacks.mapValues { it.value.toList() })
                nextId = snapshot.nextEntryId
            }
        }

        /** Compose saved-state primitives only. This does not replace any persisted app preference/file. */
        val Saver = listSaver<ShellNavigation, Any>(
            save = { nav -> buildList {
                val state = nav.snapshot()
                add(state.selectedTab.name); add(state.nextEntryId)
                ShellTab.entries.forEach { tab ->
                    val stack = state.stacks.getValue(tab)
                    add(tab.name); add(stack.size)
                    stack.forEach { add(it.id); add(it.route) }
                }
            } },
            restore = { values ->
                var index = 0
                val selected = ShellTab.valueOf(values[index++] as String)
                val nextId = values[index++] as Long
                val stacks = buildMap {
                    repeat(ShellTab.entries.size) {
                        val tab = ShellTab.valueOf(values[index++] as String)
                        val count = values[index++] as Int
                        require(count > 0 && count <= (values.size - index) / 2)
                        put(tab, List(count) { ShellEntry(values[index++] as Long, values[index++] as String) })
                    }
                }
                require(index == values.size)
                restore(ShellNavigationSnapshot(selected, stacks, nextId))
            },
        )
    }
}

/** Supplied by the navigation content; null where no shell owns the stack, so headers keep only the plain back action. */
val LocalBackToMain = compositionLocalOf<(() -> Unit)?> { null }

@Composable
fun rememberShellNavigation(initialTab: ShellTab = ShellTab.Home): ShellNavigation =
    rememberSaveable(saver = ShellNavigation.Saver) { ShellNavigation(initialTab) }

fun initialShellTab(preferences: PreferenceSnapshot): ShellTab = when (preferences[DefaultOpenTabKey]) {
    "SEARCH" -> ShellTab.Search
    "LIBRARY" -> ShellTab.Library
    else -> ShellTab.Home
}

@Composable
fun rememberShellNavigation(preferences: PreferenceSnapshot): ShellNavigation =
    rememberShellNavigation(initialShellTab(preferences))
