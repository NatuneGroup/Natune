package moe.rukamori.archivetune.shared.data.potoken

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.AsyncFunctionBinding
import com.dokar.quickjs.binding.FunctionBinding
import java.security.SecureRandom
import java.util.concurrent.Executors
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The QuickJS-backed [PoTokenJsRuntime] desktop callers install on the shared engine.
 *
 * The dispatcher handed to `QuickJs.create` only hosts async jobs — `evaluate` still runs its
 * JavaScript on the calling thread — so creation, every evaluation and the final close all run
 * inside `withContext` on the runtime's own single thread. A QuickJS instance is thread-confined,
 * so this is also the only thread it ever runs on.
 */
suspend fun jvmBotGuardJsRuntime(): PoTokenJsRuntime = JvmBotGuardJsRuntime.create()

/**
 * The desktop BotGuard runtime: one QuickJS instance with the two host bindings the environment
 * preamble needs — `__hostSleep` drives the timer queue through real coroutine delays and
 * `__hostRandom` fills `crypto.getRandomValues` from the platform secure generator. The runtime is
 * created per engine session and closed with it, exactly like the WebView the Android recipe owns.
 */
internal class JvmBotGuardJsRuntime private constructor(
    private val runtime: QuickJs,
    private val worker: ExecutorCoroutineDispatcher,
) : PoTokenJsRuntime {
    override suspend fun evaluate(code: String): String =
        withContext(worker) { runtime.evaluate(code, "botguard.js") }

    override suspend fun evaluateUnit(code: String) {
        withContext(worker) { runtime.evaluate<Unit>(code, "botguard.js") }
    }

    override suspend fun close() {
        try {
            // Closing a live runtime still has to happen on its own thread, even when the caller
            // is already cancelled, and the worker thread must go away with it.
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
        private val secureRandom = SecureRandom()

        suspend fun create(): JvmBotGuardJsRuntime {
            // The runtime's own daemon thread: creation, every evaluate, the async-job queue and
            // the final close all serialize on it; the engine mutex keeps minting single-flight.
            val worker =
                Executors.newSingleThreadExecutor { runnable ->
                    Thread(runnable, "botguard-quickjs").apply { isDaemon = true }
                }.asCoroutineDispatcher()
            // The native handle lives outside withContext: a cancelled resumption can drop the
            // block's return value, and a dropped QuickJS runtime still has to be closed on its
            // own thread before the worker goes away.
            var runtime: QuickJs? = null
            try {
                withContext(worker) {
                    QuickJs.create(worker).also { runtime = it }.apply {
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
                            ByteArray(count).also(secureRandom::nextBytes)
                        })
                    }
                }
            } catch (failure: Throwable) {
                runCatching {
                    runtime?.let { live ->
                        withContext(NonCancellable + worker) { runCatching { live.close() } }
                    }
                }
                runCatching { worker.close() }
                throw failure
            }
            return JvmBotGuardJsRuntime(checkNotNull(runtime), worker)
        }
    }
}
