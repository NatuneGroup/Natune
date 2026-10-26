/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.desktop.integration.auth

import com.dokar.quickjs.QuickJs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import moe.rukamori.archivetune.shared.domain.platform.ChallengeEvaluator

/**
 * Fresh native QuickJS per evaluation. Source is a function expression `(input) => string`.
 * No host functions, module loader, file or network bindings are installed.
 */
class DesktopChallengeEvaluator : ChallengeEvaluator {
    override suspend fun evaluate(script: String, input: String, timeoutMs: Long, maxMemoryBytes: Long): String {
        require(timeoutMs in 1..30_000 && maxMemoryBytes in 1_048_576..67_108_864)
        require(script.length <= MAX_SOURCE && input.length <= MAX_INPUT)
        return withTimeout(timeoutMs) { runtimes.withPermit { withContext(dispatcher) {
            val runtime = QuickJs.create(dispatcher)
            try {
                runtime.memoryLimit = maxMemoryBytes
                runtime.maxStackSize = 256 * 1024
                runtime.evaluationTimeoutMillis = timeoutMs
                // Check output INSIDE QuickJS before converting/copying a native result into the JVM.
                // Indirect eval keeps untrusted source out of this lexical scope and prevents
                // closing-parenthesis/comment injection from bypassing the output check.
                val program = """
                    (() => {
                      "use strict";
                      try {
                        // Atomics.wait can block inside C without executing interruptible bytecode.
                        Object.defineProperty(globalThis, "Atomics", {value: undefined});
                        Object.defineProperty(globalThis, "SharedArrayBuffer", {value: undefined});
                        const codeUnit = Function.prototype.call.bind(String.prototype.charCodeAt);
                        const hex = "0123456789abcdef";
                        const run = (0, eval)(${jsString("($script\n)")});
                        const result = run(${jsString(input)});
                        if (typeof result !== "string" || result.length > $MAX_OUTPUT) {
                          throw "Challenge result must be a bounded string";
                        }
                        // quickjs-kt 1.0.10's raw JNI strings mishandle NUL/non-BMP characters.
                        // Bounded UTF-16 hex is ASCII across that boundary and lossless on the JVM.
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
                val transport = runtime.evaluate<String>(program, "archivetune-challenge.js")
                check(transport.length <= MAX_OUTPUT * 4 && transport.length % 4 == 0)
                buildString(transport.length / 4) {
                    for (offset in transport.indices step 4) append(transport.substring(offset, offset + 4).toInt(16).toChar())
                }
            } finally { runtime.close() }
        } } }
    }
    companion object {
        const val MAX_SOURCE = 1_048_576
        const val MAX_INPUT = 1_048_576
        const val MAX_OUTPUT = 262_144
        private val dispatcher = Dispatchers.Default.limitedParallelism(2)
        private val runtimes = Semaphore(2)
    }
}

/** UTF-16 code units are escaped, including line separators and unmatched surrogates. */
internal fun jsString(value: String): String = buildString(value.length + 2) {
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
