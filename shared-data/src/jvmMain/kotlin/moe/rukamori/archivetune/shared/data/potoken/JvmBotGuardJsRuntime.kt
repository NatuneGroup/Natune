package moe.rukamori.archivetune.shared.data.potoken

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.AsyncFunctionBinding
import com.dokar.quickjs.binding.FunctionBinding
import java.security.SecureRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay

/** The QuickJS-backed [PoTokenJsRuntime] desktop callers install on the shared engine. */
fun jvmBotGuardJsRuntime(): PoTokenJsRuntime = JvmBotGuardJsRuntime.create()

/**
 * The desktop BotGuard runtime: one QuickJS instance with the two host bindings the environment
 * preamble needs — `__hostSleep` drives the timer queue through real coroutine delays and
 * `__hostRandom` fills `crypto.getRandomValues` from the platform secure generator. The runtime is
 * created per engine session and closed with it, exactly like the WebView the Android recipe owns.
 */
internal class JvmBotGuardJsRuntime private constructor(
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
        /** The single dispatcher the runtime's async jobs run on; minting is serialized by the engine mutex. */
        private val dispatcher = Dispatchers.Default.limitedParallelism(1)
        private val secureRandom = SecureRandom()

        fun create(): JvmBotGuardJsRuntime {
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
                ByteArray(count).also(secureRandom::nextBytes)
            })
            return JvmBotGuardJsRuntime(runtime)
        }
    }
}
