package moe.rukamori.archivetune.shared.ui.session

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import moe.rukamori.archivetune.shared.domain.lyrics.ai.*
import moe.rukamori.archivetune.shared.domain.repository.AiTextService
import moe.rukamori.archivetune.shared.domain.settings.*
import moe.rukamori.archivetune.shared.ui.menu.*
import kotlin.test.*

class LyricsSessionsTest {
    @Test fun serviceSuccessCommitsThroughSharedUndoPathAndUndoSuppressesAutomaticRetry(): Unit = runBlocking {
        val owner = SupervisorJob()
        val scope = CoroutineScope(owner + Dispatchers.Unconfined)
        val store = Store()
        val service = Service()
        val original = LyricsEditSnapshot("track", "[00:01.00]Hello", "REMOTE", "LrcLib")
        val current = MutableStateFlow(original)
        val sessions = LyricsSessions(scope, store, service, "fixture")
        val edits = LyricsMenuEdits { expected, replacement, guard ->
            if (current.value != expected || !guard()) null else replacement.copy(revision = expected.revision + 1).also { current.value = it }
        }
        LyricsTranslationPersistence(scope, sessions, edits, { current.value }, { error("Write failed") })
        try {
            sessions.select("track", original.lyrics)
            sessions.manual(AiLyricsOperation.TRANSLATE)
            val saved = withTimeout(5_000) { current.first { it.source == "AI_TRANSLATION" } }
            assertTrue(saved.lyrics.endsWith("[00:01.00]Bonjour"))
            assertEquals("LrcLib", saved.providerName)
            assertTrue(edits.undo())
            sessions.translationUndone("track")
            assertEquals(original.lyrics, current.value.lyrics)
            assertEquals(original.source, current.value.source)
            store.edit { this[AutoTranslateLyricsKey] = true }
            yield()
            assertEquals(1, service.calls)
            sessions.manual(AiLyricsOperation.TRANSLATE)
            withTimeout(5_000) { current.first { it.source == "AI_TRANSLATION" } }
        } finally { owner.cancelAndJoin() }
    }

    @Test fun settingsEditedDuringDatabaseReadInvalidatePendingSuccessBeforeCommit(): Unit = runBlocking {
        val owner = SupervisorJob()
        val scope = CoroutineScope(owner + Dispatchers.Unconfined)
        val store = Store()
        val sessions = LyricsSessions(scope, store, Service(), "fixture")
        val before = LyricsEditSnapshot("track", "[00:01.00]Hello", "REMOTE", "LrcLib")
        val reading = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var writes = 0
        val edits = LyricsMenuEdits { _, replacement, guard -> if (guard()) { writes++; replacement } else null }
        LyricsTranslationPersistence(scope, sessions, edits, { reading.complete(Unit); release.await(); before }, { error("Write failed") })
        try {
            sessions.select("track", before.lyrics)
            sessions.manual(AiLyricsOperation.TRANSLATE)
            withTimeout(5_000) { reading.await() }
            store.edit { this[AiApiKeyKey] = "replacement-fixture" }
            release.complete(Unit)
            yield()
            assertEquals(0, writes)
            assertNull(edits.undo.value)
        } finally { release.complete(Unit); owner.cancelAndJoin() }
    }

    @Test fun staleReadFailureCannotCancelTheNextTracksRequest(): Unit = runBlocking {
        val owner = SupervisorJob()
        val scope = CoroutineScope(owner + Dispatchers.Unconfined)
        val next = CompletableDeferred<List<String>>()
        val service = Service { lines -> if (lines.single() == "Hello") listOf("Bonjour") else next.await() }
        val sessions = LyricsSessions(scope, Store(), service, "fixture")
        val reading = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var failures = 0
        val edits = LyricsMenuEdits { _, _, _ -> error("Obsolete edit") }
        LyricsTranslationPersistence(scope, sessions, edits, {
            reading.complete(Unit)
            release.await()
            error("Old database read failed")
        }, { failures++ })
        try {
            sessions.select("old", "[00:01.00]Hello")
            sessions.manual(AiLyricsOperation.TRANSLATE)
            withTimeout(5_000) { reading.await() }
            sessions.select("new", "[00:01.00]New")
            sessions.manual(AiLyricsOperation.TRANSLATE)
            release.complete(Unit)
            yield()
            assertTrue(sessions.translation.state.value.running)
            assertEquals("new", sessions.translation.state.value.request?.mediaId)
            assertEquals(0, failures)
        } finally { release.complete(Unit); owner.cancelAndJoin() }
    }

    private class Service(
        val respond: suspend (List<String>) -> List<String> = { lines -> lines.map { "Bonjour" } },
    ) : AiTextService {
        var calls = 0
        override suspend fun translateLines(config: AiServiceSettings, targetLanguage: String, lines: List<String>, formatName: String): List<String> {
            calls++
            return respond(lines)
        }
        override suspend fun romanizeLines(config: AiServiceSettings, lines: List<String>, formatName: String): List<String> = error("Not used")
        override suspend fun listModels(config: AiServiceSettings): List<String> = error("Not used")
        override suspend fun test(config: AiServiceSettings) = error("Not used")
    }
    private class Store : SettingsStore {
        override val snapshots = MutableStateFlow(PreferenceSnapshot.of(AiProviderKey to "CHATGPT", AiApiKeyKey to "fixture"))
        override suspend fun edit(change: PreferenceEditor.() -> Unit): SettingsChange {
            val before = snapshots.value
            val after = before.toEditor().apply(change).snapshot()
            snapshots.value = after
            return SettingsChange(before, after)
        }
    }
}
