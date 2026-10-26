/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.desktop.integration.auth

import com.dokar.quickjs.QuickJsException
import kotlin.test.*
import kotlin.time.TimeSource
import kotlinx.coroutines.*

/** These execute the approved QuickJS native library, not a replacement evaluator. */
class DesktopChallengeEvaluatorTest {
    private val evaluator = DesktopChallengeEvaluator()
    private val memory = 8_388_608L
    @Test fun actuallyComputesWithQuotedUnicodeInput() = runBlocking<Unit> {
        val input = "日本語 🎵 \" \\ \n \u0000 \u2028"
        assertEquals(input, evaluator.evaluate("input => input", input, 2000, memory))
        assertEquals("\ud800\u0000\udfff", evaluator.evaluate("input => input", "\ud800\u0000\udfff", 2000, memory))
        assertEquals("42", evaluator.evaluate("input => String(Number(input) * 6)", "7", 2000, memory))
    }
    @Test fun scriptCannotReplaceTheTrustedUnicodeEncoder() = runBlocking<Unit> {
        assertEquals("🎵\u0000", evaluator.evaluate(
            "() => { String.prototype.charCodeAt = () => 65; return '🎵\\u0000'; }", "", 2000, memory,
        ))
    }
    @Test fun eachInvocationHasFreshGlobalsAndNoHostCapabilities() = runBlocking<Unit> {
        evaluator.evaluate("() => { globalThis.secret = 'private'; return 'ok'; }", "", 2000, memory)
        assertEquals("undefined,undefined,undefined,undefined,undefined,undefined,undefined", evaluator.evaluate(
            "() => [typeof secret, typeof fetch, typeof require, typeof std, typeof os, typeof Atomics, typeof SharedArrayBuffer].join(',')",
            "", 2000, memory,
        ))
    }
    @Test fun rejectsOversizeOutputBeforeItCrossesTheNativeBoundary() = runBlocking<Unit> {
        assertFailsWith<QuickJsException> {
            evaluator.evaluate("() => 'a'.repeat(${DesktopChallengeEvaluator.MAX_OUTPUT + 1})", "", 2000, memory)
        }
        assertEquals(DesktopChallengeEvaluator.MAX_OUTPUT, evaluator.evaluate(
            "() => 'a'.repeat(${DesktopChallengeEvaluator.MAX_OUTPUT})", "", 2000, memory,
        ).length)
    }
    @Test fun sourceCannotCloseTheTrustedOutputGuard() = runBlocking<Unit> {
        assertFailsWith<QuickJsException> {
            evaluator.evaluate("() => ''), 'a'.repeat(900000); //", "", 2000, memory)
        }
        assertFailsWith<QuickJsException> { evaluator.evaluate("() => ({length: 1})", "", 2000, memory) }
        assertFailsWith<QuickJsException> { evaluator.evaluate("() => Promise.resolve('x')", "", 2000, memory) }
    }
    @Test fun infiniteLoopIsInterruptedWithinNativeDeadline() = runBlocking<Unit> {
        val start = TimeSource.Monotonic.markNow()
        assertFails { evaluator.evaluate("() => { for (;;) {} }", "", 100, memory) }
        assertTrue(start.elapsedNow().inWholeMilliseconds < 2000)
        assertEquals("alive", evaluator.evaluate("() => 'alive'", "", 2000, memory))
    }
    @Test fun callerCancellationInterruptsBusyNativeEvaluation() = runBlocking<Unit> {
        val evaluation = async(Dispatchers.Default) { evaluator.evaluate("() => { while (true) {} }", "", 10_000, memory) }
        delay(100)
        val start = TimeSource.Monotonic.markNow()
        evaluation.cancelAndJoin()
        assertTrue(start.elapsedNow().inWholeMilliseconds < 2000)
    }
    @Test fun nativeMemoryAndStackLimitsRejectRunawayAllocationAndRecursion() = runBlocking<Unit> {
        assertFailsWith<QuickJsException> { evaluator.evaluate(
            "() => { const list=[]; while (true) { list.push(new Uint8Array(262144)); } }", "", 2000, 2_097_152,
        ) }
        assertFailsWith<QuickJsException> { evaluator.evaluate(
            "() => { function f() { return f() + 'x'; } return f(); }", "", 2000, memory,
        ) }
    }
    @Test fun invalidResourceRequestsAndOversizeSourcesFailBeforeExecution() = runBlocking<Unit> {
        for ((time, bytes) in listOf(0L to memory, 30_001L to memory, 100L to 0L, 100L to 67_108_865L)) {
            assertFailsWith<IllegalArgumentException> { evaluator.evaluate("() => ''", "", time, bytes) }
        }
        assertFailsWith<IllegalArgumentException> {
            evaluator.evaluate("x".repeat(DesktopChallengeEvaluator.MAX_SOURCE + 1), "", 100, memory)
        }
        assertFailsWith<IllegalArgumentException> {
            evaluator.evaluate("x => x", "x".repeat(DesktopChallengeEvaluator.MAX_INPUT + 1), 100, memory)
        }
    }
}
