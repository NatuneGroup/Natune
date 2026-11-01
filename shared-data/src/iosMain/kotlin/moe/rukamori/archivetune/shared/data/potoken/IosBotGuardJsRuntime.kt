package moe.rukamori.archivetune.shared.data.potoken

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.AsyncFunctionBinding
import com.dokar.quickjs.binding.FunctionBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import moe.rukamori.archivetune.shared.data.platform.ios.iosSecureRandomBytes

/**
 * The iOS BotGuard runtime over the same pinned quickjs-kt build `IosChallengeEvaluator` uses: one
 * QuickJS instance per engine session carrying the `__hostSleep`/`__hostRandom` bindings the
 * environment preamble needs, closed with the session exactly like the Android WebView's engine.
 */
internal class IosBotGuardJsRuntime private constructor(
    private val runtime: QuickJs,
) : PoTokenJsRuntime {
    override suspend fun evaluate(code: String): String = runtime.evaluate(code, "botguard.js")

    override suspend fun evaluateUnit(code: String) {
        runtime.evaluate<Unit>(code, "botguard.js")
    }

    override fun close() {
        try {
            runtime.close()
        } catch (_: Throwable) {
        }
    }

    companion object {
        private val dispatcher = Dispatchers.Default.limitedParallelism(1)

        fun create(): IosBotGuardJsRuntime {
            val runtime = QuickJs.create(dispatcher)
            // BotGuard's interpreter plus program is far heavier than a provider challenge; the cap
            // only protects the host, it never shapes a valid run.
            runtime.memoryLimit = 512L * 1024 * 1024
            runtime.maxStackSize = 1024L * 1024
            // Kotlin-side cancellation interrupts a runaway evaluation; this is only a last-resort bound.
            runtime.evaluationTimeoutMillis = 60_000L
            runtime.defineBinding("__hostSleep", AsyncFunctionBinding<Any?> { args ->
                val ms = (args.getOrNull(0) as? Number)?.toLong()?.coerceIn(0L, 3_600_000L) ?: 0L
                delay(ms)
                null
            })
            runtime.defineBinding("__hostRandom", FunctionBinding<ByteArray> { args ->
                val count = (args.getOrNull(0) as? Number)?.toInt()?.coerceIn(0, 1_048_576) ?: 0
                iosSecureRandomBytes(count)
            })
            return IosBotGuardJsRuntime(runtime)
        }
    }
}
