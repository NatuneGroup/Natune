@file:OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)

package moe.rukamori.archivetune.shared.data.potoken

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.AsyncFunctionBinding
import com.dokar.quickjs.binding.FunctionBinding
import kotlinx.coroutines.CloseableCoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.shared.data.platform.ios.iosSecureRandomBytes

/**
 * The iOS BotGuard runtime over the same pinned quickjs-kt build `IosChallengeEvaluator` uses: one
 * QuickJS instance per engine session carrying the `__hostSleep`/`__hostRandom` bindings the
 * environment preamble needs, closed with the session exactly like the Android WebView's engine.
 *
 * The dispatcher handed to `QuickJs.create` only hosts async jobs — `evaluate` still runs its
 * JavaScript on the calling thread — so creation, every evaluation and the final close all run
 * inside `withContext` on the runtime's own worker thread. A QuickJS instance is thread-confined,
 * so this is also the only thread it ever runs on.
 */
internal class IosBotGuardJsRuntime private constructor(
    private val runtime: QuickJs,
    private val worker: CloseableCoroutineDispatcher,
) : PoTokenJsRuntime {
    override suspend fun evaluate(code: String): String =
        withContext(worker) { runtime.evaluate(code, "botguard.js") }

    override suspend fun evaluateUnit(code: String) {
        withContext(worker) { runtime.evaluate<Unit>(code, "botguard.js") }
    }

    override suspend fun close() {
        try {
            // Closing a live runtime still has to happen on its own thread, even when the caller
            // is already cancelled, and the worker must go away with it.
            withContext(NonCancellable + worker) {
                try {
                    runtime.close()
                } catch (_: Throwable) {
                }
            }
        } catch (_: Throwable) {
        } finally {
            runCatching { worker.close() }
        }
    }

    companion object {
        suspend fun create(): IosBotGuardJsRuntime {
            // The runtime's own thread: creation, every evaluate, the async-job queue and the final
            // close all serialize on it; the engine mutex keeps minting single-flight.
            val worker = newSingleThreadContext("ArchiveTune BotGuard")
            try {
                val runtime =
                    withContext(worker) {
                        QuickJs.create(worker).apply {
                            // BotGuard's interpreter plus program is far heavier than a provider
                            // challenge; the cap only protects the host, it never shapes a valid run.
                            memoryLimit = 512L * 1024 * 1024
                            maxStackSize = 1024L * 1024
                            // Kotlin-side cancellation interrupts a runaway evaluation; this is only
                            // a last-resort bound.
                            evaluationTimeoutMillis = 60_000L
                            defineBinding("__hostSleep", AsyncFunctionBinding<Any?> { args ->
                                val ms =
                                    (args.getOrNull(0) as? Number)?.toLong()?.coerceIn(0L, 3_600_000L) ?: 0L
                                delay(ms)
                                null
                            })
                            defineBinding("__hostRandom", FunctionBinding<ByteArray> { args ->
                                val count =
                                    (args.getOrNull(0) as? Number)?.toInt()?.coerceIn(0, 1_048_576) ?: 0
                                iosSecureRandomBytes(count)
                            })
                        }
                    }
                return IosBotGuardJsRuntime(runtime, worker)
            } catch (failure: Throwable) {
                runCatching { worker.close() }
                throw failure
            }
        }
    }
}
