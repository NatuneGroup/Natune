/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.domain.lyrics.ai

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AiLyricsSessionTest {
    private val config = AiServiceSettings(AiProvider.CHATGPT, "fixture", "", "test")
    private fun settings(auto: Boolean = true) = AiLyricsSessionSettings(
        AiTranslationSettingsSnapshot(config, auto, "ENGLISH", emptySet()),
        AiRomanizationSettings(true, auto, emptySet(), config),
    )
    private fun request(text: String = "[00:01.00]Hello", automatic: Boolean = false) =
        AiLyricsSessionRequest("song", text, AiLyricsOperation.TRANSLATE, automatic = automatic)

    @Test fun deduplicatesRunningAndCachesOnlyCompleteResults() = runTest {
        var calls = 0
        val response = CompletableDeferred<List<String>>()
        val session = AiLyricsSessionCoordinator(this, "test", { _, _, _, _ -> calls++; response.await() }, { _, _ -> emptyList() })
        session.submit(request(), settings())
        session.submit(request(), settings())
        runCurrent()
        assertTrue(session.state.value.running)
        assertEquals(1, calls)
        response.complete(listOf("Bonjour"))
        runCurrent()
        assertEquals("[00:01.00]Hello\n[00:01.00]Bonjour", session.state.value.result?.translatedLyrics)
        session.submit(request(), settings())
        runCurrent()
        assertEquals(1, calls)
        assertFalse(session.state.value.running)
    }

    @Test fun incompleteFailureCanRetryAndDoesNotCacheOriginals() = runTest {
        var calls = 0
        val session = AiLyricsSessionCoordinator(this, "test", { _, _, _, _ ->
            calls++
            if (calls == 1) listOf("") else listOf("Bonjour")
        }, { _, _ -> emptyList() })
        session.submit(request(), settings())
        runCurrent()
        assertEquals(AiLyricsSessionFailure.INCOMPLETE, session.state.value.failure)
        assertNull(session.state.value.result)
        session.submit(request(), settings())
        runCurrent()
        assertEquals(2, calls)
        assertTrue(session.state.value.result != null)
    }

    @Test fun automaticObserverAndManualMenuShareTheSameActiveJob() = runTest {
        var calls = 0
        val response = CompletableDeferred<List<String>>()
        val session = AiLyricsSessionCoordinator(this, "test", { _, _, _, _ -> calls++; response.await() }, { _, _ -> emptyList() })
        val automatic = request(automatic = true)
        val first = session.submit(automatic, settings())
        runCurrent()
        session.select(request())
        val manual = session.submit(request(), settings())
        assertTrue(first === manual)
        assertEquals(1, calls)
        response.complete(listOf("Bonjour"))
        runCurrent()
        assertTrue(session.state.value.result != null)
    }

    @Test fun cancelledOldProviderCannotPublishOrClearNewRunningState() = runTest {
        val first = CompletableDeferred<List<String>>()
        val second = CompletableDeferred<List<String>>()
        val session = AiLyricsSessionCoordinator(this, "test", { _, _, lines, _ ->
            withContext(NonCancellable) { if (lines.single() == "Hello") first.await() else second.await() }
        }, { _, _ -> emptyList() })
        session.submit(request(), settings())
        runCurrent()
        session.submit(request("[00:01.00]New"), settings())
        runCurrent()
        first.complete(listOf("Obsolete"))
        runCurrent()
        assertTrue(session.state.value.running)
        assertNull(session.state.value.result)
        second.complete(listOf("Nouveau"))
        runCurrent()
        assertTrue(session.state.value.result!!.translatedLyrics!!.contains("Nouveau"))
    }

    @Test fun settingsChangesAndCancelRejectLateSuccess() = runTest {
        val response = CompletableDeferred<List<String>>()
        val session = AiLyricsSessionCoordinator(this, "test", { _, _, _, _ ->
            withContext(NonCancellable) { response.await() }
        }, { _, _ -> emptyList() })
        session.submit(request(), settings())
        runCurrent()
        session.updateSettings(settings().let { it.copy(translation = it.translation.copy(config = config.copy(model = "changed"))) })
        response.complete(listOf("Obsolete"))
        runCurrent()
        assertNull(session.state.value.result)
        assertFalse(session.state.value.running)
        session.cancel()
    }

    @Test fun automaticGatesDoNotBlockManualTranslation() = runTest {
        var calls = 0
        val session = AiLyricsSessionCoordinator(this, "test", { _, _, _, _ -> calls++; listOf("Bonjour") }, { _, _ -> emptyList() })
        session.submit(request(automatic = true), settings(auto = false))
        runCurrent()
        assertEquals(0, calls)
        session.submit(request(), settings(auto = false))
        runCurrent()
        assertEquals(1, calls)
    }

    @Test fun romanizationIsTextAddressedAndDeepLNeverRomanizes() = runTest {
        var calls = 0
        val session = AiLyricsSessionCoordinator(this, "test", { _, _, _, _ -> emptyList() }, { _, lines ->
            calls++; lines.map { if (it == "안녕") "annyeong" else null }
        })
        val req = request("[00:01.00]안녕").copy(operation = AiLyricsOperation.ROMANIZE)
        session.submit(req, settings())
        runCurrent()
        assertEquals(mapOf("안녕" to "annyeong"), session.state.value.result?.romanizedByLine)
        val disabled = settings().let { it.copy(romanization = it.romanization.copy(config = config.copy(provider = AiProvider.DEEPL))) }
        session.submit(req, disabled)
        runCurrent()
        assertEquals(1, calls)
        assertNull(session.state.value.result)
    }

    @Test fun targetAndCredentialChangesDoNotReuseSuccess() = runTest {
        var calls = 0
        val session = AiLyricsSessionCoordinator(this, "test", { _, _, _, _ -> calls++; listOf("Bonjour") }, { _, _ -> emptyList() })
        session.submit(request(), settings())
        runCurrent()
        val changed = settings().let { it.copy(translation = it.translation.copy(config = config.copy(apiKey = "replacement"))) }
        session.submit(request(), changed)
        runCurrent()
        assertEquals(2, calls)
        session.submit(request().copy(targetLanguage = "FRENCH"), changed)
        runCurrent()
        assertEquals(AiLyricsSessionFailure.INELIGIBLE, session.state.value.failure)
        assertEquals(2, calls)
    }

    @Test fun romanizationUsesTheExistingBoundedBatchPolicyAndRejectsOversizedText() = runTest {
        val sizes = mutableListOf<Int>()
        val session = AiLyricsSessionCoordinator(this, "test", { _, _, _, _ -> emptyList() }, { _, lines ->
            sizes += lines.size
            lines.map { "annyeong ${it.substringBefore(' ')}" }
        })
        val req = request((0..80).joinToString("\n") { "$it 안녕" }).copy(operation = AiLyricsOperation.ROMANIZE)
        session.submit(req, settings())
        runCurrent()
        assertEquals(listOf(80, 1), sizes)
        assertEquals(81, session.state.value.result?.romanizedByLine?.size)
        session.submit(req.copy(lyrics = "안".repeat(2_000_001)), settings())
        runCurrent()
        assertEquals(AiLyricsSessionFailure.INELIGIBLE, session.state.value.failure)
        assertEquals(2, sizes.size)
    }
}
