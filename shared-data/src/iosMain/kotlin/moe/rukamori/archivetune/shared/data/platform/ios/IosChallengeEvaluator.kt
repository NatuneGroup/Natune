@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package moe.rukamori.archivetune.shared.data.platform.ios

import com.dokar.quickjs.QuickJs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import moe.rukamori.archivetune.shared.domain.platform.ChallengeEvaluator

/**
 * Fresh bounded QuickJS runtime per evaluation, the same contract as the desktop challenge evaluator.
 * The source is a function expression `(input) => string`. No host function, module loader, file,
 * network, Atomics or SharedArrayBuffer is installed. Time, memory, stack and output bounds are enforced
 * by the engine. Failures are sanitized because engine messages can echo challenge source.
 */
class IosChallengeEvaluator : ChallengeEvaluator {
    override suspend fun evaluate(script: String, input: String, timeoutMs: Long, maxMemoryBytes: Long): String {
        require(timeoutMs in 1..30_000 && maxMemoryBytes in 1_048_576..67_108_864)
        require(script.length <= MAX_SOURCE && input.length <= MAX_INPUT)
        return try {
            withTimeout(timeoutMs) {
                runtimes.withPermit {
                    withContext(dispatcher) { evaluateBounded(script, input, timeoutMs, maxMemoryBytes) }
                }
            }
        } catch (_: Exception) {
            // Real cancellation of the caller propagates. A timeout or engine error is reported as sanitized failure.
            currentCoroutineContext().ensureActive()
            throw IosChallengeFailed()
        }
    }

    private suspend fun evaluateBounded(script: String, input: String, timeoutMs: Long, maxMemoryBytes: Long): String {
        val runtime = QuickJs.create(dispatcher)
        try {
            runtime.memoryLimit = maxMemoryBytes
            runtime.maxStackSize = 256 * 1024
            runtime.evaluationTimeoutMillis = timeoutMs
            // Output is checked INSIDE QuickJS before a native result is copied into Kotlin. Indirect eval
            // keeps untrusted source out of this lexical scope, so a closing parenthesis or comment cannot
            // bypass the output check.
            val transport = runtime.evaluate<String>(program(script, input), "archivetune-challenge.js")
            check(transport.length <= MAX_OUTPUT * 4 && transport.length % 4 == 0)
            return buildString(transport.length / 4) {
                for (offset in transport.indices step 4) {
                    append(transport.substring(offset, offset + 4).toInt(16).toChar())
                }
            }
        } finally {
            runtime.close()
        }
    }

    private fun program(script: String, input: String): String = """
        (() => {
          "use strict";
          try {
            // Atomics.wait can block inside C without executing interruptible bytecode.
            Object.defineProperty(globalThis, "Atomics", {value: undefined});
            Object.defineProperty(globalThis, "SharedArrayBuffer", {value: undefined});
            const codeUnit = Function.prototype.call.bind(String.prototype.charCodeAt);
            const hex = "0123456789abcdef";
            const run = (0, eval)(${challengeJsString("($script\n)")});
            const result = run(${challengeJsString(input)});
            if (typeof result !== "string" || result.length > $MAX_OUTPUT) {
              throw "Challenge result must be a bounded string";
            }
            // Bounded UTF-16 hex stays ASCII across the native boundary and is lossless for NUL and non-BMP units.
            let transport = "";
            for (let i = 0; i < result.length; i++) {
              const unit = codeUnit(result, i);
              transport += hex[(unit >>> 12) & 15] + hex[(unit >>> 8) & 15] +
                hex[(unit >>> 4) & 15] + hex[unit & 15];
            }
            return transport;
          } catch (_) {
            throw "Challenge evaluation failed";
          }
        })()
    """.trimIndent()

    private companion object {
        const val MAX_SOURCE = 1_048_576
        const val MAX_INPUT = 1_048_576
        const val MAX_OUTPUT = 262_144
        private val dispatcher = Dispatchers.Default.limitedParallelism(2)
        private val runtimes = Semaphore(2)
    }
}

/** UTF-16 code units are escaped, including line separators and unmatched surrogates. */
private fun challengeJsString(value: String): String = buildString(value.length + 2) {
    append('"')
    for (character in value) {
        when {
            character == '"' -> append("\\\"")
            character == '\\' -> append("\\\\")
            character.code in 0x20..0x7e -> append(character)
            else -> append("\\u").append(character.code.toString(16).padStart(4, '0'))
        }
    }
    append('"')
}

/** Sanitized: engine diagnostics can echo challenge source or account-bearing script input. */
class IosChallengeFailed : IllegalStateException("iOS challenge evaluation failed")
