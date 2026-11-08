/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.modules


object ModuleSourceDecoder {
    fun decode(input: String): String {
        if (input.length > MAX_SOURCE_BYTES) throw ModuleException(ModuleFailure.INVALID_DOCUMENT)
        val text = input.removePrefix("\uFEFF").trim()
        val source = text
        rejectEncryptedEnvelope(source)
        if (source.isBlank()) throw ModuleException(ModuleFailure.INVALID_DOCUMENT)
        val readable = unwrap(withoutHeader(source))
        rejectEncryptedEnvelope(readable)
        return readable
    }

    private fun rejectEncryptedEnvelope(value: String) {
        if (value.trimStart().startsWith("8SM1.")) throw ModuleException(ModuleFailure.UNSUPPORTED,
            stage = ModuleLoadStage.DECODE, diagnosticCode = "ENCRYPTED_8SM1_REJECTED",
            cause = IllegalArgumentException("Encrypted 8SM1 modules are unsupported; install a readable .arch package"))
    }

    private fun unwrap(value: String): String {
        val match = Regex("^export\\s+const\\s+[A-Za-z_$][A-Za-z0-9_$]*\\s*=\\s*`").find(value) ?: return value
        val result = StringBuilder()
        var index = match.range.last + 1
        while (index < value.length) {
            val character = value[index++]
            if (character == '`') {
                if (value.substring(index).trim().removeSuffix(";").isNotBlank()) throw ModuleException(ModuleFailure.INVALID_DOCUMENT)
                return result.toString()
            }
            if (character == '$' && value.getOrNull(index) == '{') throw ModuleException(ModuleFailure.UNSUPPORTED)
            if (character != '\\') {
                result.append(character)
                continue
            }
            val escaped = value.getOrNull(index++) ?: throw ModuleException(ModuleFailure.INVALID_DOCUMENT)
            when (escaped) {
                'n' -> result.append('\n')
                'r' -> result.append('\r')
                't' -> result.append('\t')
                'b' -> result.append('\b')
                'f' -> result.append('\u000C')
                'v' -> result.append('\u000B')
                '0' -> result.append('\u0000')
                '\n' -> Unit
                '\r' -> if (value.getOrNull(index) == '\n') index++
                'x', 'u' -> {
                    val count = if (escaped == 'x') 2 else 4
                    val digits = value.substring(index, minOf(index + count, value.length))
                    if (digits.length != count) throw ModuleException(ModuleFailure.INVALID_DOCUMENT)
                    result.append((digits.toIntOrNull(16) ?: throw ModuleException(ModuleFailure.INVALID_DOCUMENT)).toChar())
                    index += count
                }
                else -> result.append(escaped)
            }
        }
        throw ModuleException(ModuleFailure.INVALID_DOCUMENT)
    }

    private fun withoutHeader(value: String): String {
        var index = 0
        while (index < value.length) {
            when {
                value[index].isWhitespace() -> index++
                value.startsWith("//", index) -> index = value.indexOf('\n', index).takeIf { it >= 0 }?.plus(1) ?: value.length
                value.startsWith("/*", index) -> index = value.indexOf("*/", index + 2).takeIf { it >= 0 }?.plus(2)
                    ?: throw ModuleException(ModuleFailure.INVALID_DOCUMENT)
                else -> return value.substring(index)
            }
        }
        throw ModuleException(ModuleFailure.INVALID_DOCUMENT)
    }

    const val MAX_SOURCE_BYTES = 4 * 1024 * 1024
}
