/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.ui.session

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import moe.rukamori.archivetune.shared.data.lyrics.*
import moe.rukamori.archivetune.shared.domain.settings.*
import kotlin.test.*

class SettingsLyricsRuntimeTest {
    @Test fun providerToggleOrderAndAccountRevisionReconfigureRealOrchestration(): Unit = runBlocking {
        val owner = SupervisorJob()
        val store = Store()
        val revision = MutableStateFlow(0L)
        val calls = MutableStateFlow(emptyList<String>())
        var builds = 0
        val providers = listOf(Provider("LrcLib", calls), Provider("Kugou", calls))
        val runtime = SettingsLyricsRuntime(
            CoroutineScope(owner + Dispatchers.Unconfined), store,
            MutableStateFlow(providers.map { it.name }.toSet()), revision,
            { builds++; providers }, { true }, { _, _ -> error("Provider failed") },
        )
        try {
            assertEquals("LrcLib", runtime.lyrics(request("current")).providerName)
            store.edit { this[EnableLrcLibKey] = false }
            withTimeout(5_000) {
                while (runtime.lyrics(request("current"), forceRefresh = true).providerName != "Kugou") yield()
            }
            val disabledCount = calls.value.count { it.startsWith("LrcLib:") }
            assertEquals("Kugou", runtime.lyrics(request("new")).providerName)
            assertEquals(disabledCount, calls.value.count { it.startsWith("LrcLib:") })
            val previousBuilds = builds
            revision.value++
            withTimeout(5_000) { while (builds == previousBuilds) yield() }
            assertTrue(builds > previousBuilds)
            val extra = Provider("Genius", calls)
            assertEquals(listOf("Kugou", "Genius", "LrcLib"),
                orderLyricsProviders(listOf(providers[0], extra, providers[1]), listOf("Kugou", "LrcLib")).map { it.name })
        } finally { owner.cancelAndJoin() }
    }

    @Test fun preloadCountAloneControlsWindowAndChangesApplyWithoutRecreatingQueue(): Unit = runBlocking {
        val owner = SupervisorJob()
        val store = Store(PreferenceSnapshot.of(QueueLyricsPreloadCountKey to 0))
        val calls = MutableStateFlow(emptyList<String>())
        val runtime = SettingsLyricsRuntime(
            CoroutineScope(owner + Dispatchers.Unconfined), store,
            MutableStateFlow(setOf("LrcLib")), MutableStateFlow(0L),
            { listOf(Provider("LrcLib", calls)) }, { true }, { _, _ -> error("Provider failed") },
        )
        try {
            runtime.updateWindow(null, listOf(request("a"), request("b")))
            runtime.lyrics(request("current"))
            assertFalse(calls.value.any { it.endsWith(":a") || it.endsWith(":b") })
            store.edit { this[QueueLyricsPreloadCountKey] = 1 }
            withTimeout(5_000) { calls.first { "LrcLib:a" in it } }
            assertFalse("LrcLib:b" in calls.value)
            store.edit { this[QueueLyricsPreloadCountKey] = 2 }
            withTimeout(5_000) { calls.first { "LrcLib:b" in it } }
        } finally { owner.cancelAndJoin() }
    }

    private fun request(id: String) = LyricsRequest(id, id, "Artist", durationSec = 10)
    private class Provider(override val name: String, val calls: MutableStateFlow<List<String>>) : LyricsProvider {
        override suspend fun getLyrics(id: String, title: String, artist: String, album: String?, durationSec: Int, isrc: String?): Result<String> {
            calls.value += "$name:$id"
            return Result.success("[00:01.00]Hello")
        }
    }
    private class Store(initial: PreferenceSnapshot = PreferenceSnapshot.EMPTY) : SettingsStore {
        override val snapshots = MutableStateFlow(initial)
        override suspend fun edit(change: PreferenceEditor.() -> Unit): SettingsChange {
            val before = snapshots.value
            val after = before.toEditor().apply(change).snapshot()
            snapshots.value = after
            return SettingsChange(before, after)
        }
    }
}
