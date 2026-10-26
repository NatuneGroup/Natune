import org.gradle.api.Project
import java.io.File
import java.security.MessageDigest
import java.util.Properties

/**
 * Build-time provider credentials. Values are never committed: each one is read from the environment
 * (CI secrets), then from the untracked local.properties file, then from a Gradle property. A missing
 * value resolves to an empty string, which leaves the dependent feature reporting "not configured"
 * instead of failing the build. The variable list is in docs/build-credentials.md.
 */
object ProviderCredentials {
    /**
     * Returns the first non-blank value for [name], then for each of [legacyNames], searched in the
     * environment, local.properties and Gradle properties, in that order.
     */
    fun resolve(project: Project, name: String, vararg legacyNames: String): String {
        val localProperties = Properties()
        val localFile: File = project.rootProject.file("local.properties")
        if (localFile.isFile) {
            localFile.inputStream().use { localProperties.load(it) }
        }
        for (key in listOf(name) + legacyNames) {
            val value =
                System.getenv(key)?.trim()?.takeIf { it.isNotEmpty() }
                    ?: localProperties.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() }
                    ?: project.findProperty(key)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            if (value != null) return value
        }
        return ""
    }

    /** A quoted Java string literal for `buildConfigField`, with quotes, backslashes and line breaks escaped. */
    fun javaStringLiteral(value: String): String = "\"" + escape(value, escapeDollar = false) + "\""

    /**
     * A SHA-256 fingerprint of [value]. Generation tasks take fingerprints as inputs, so a changed
     * credential regenerates its source without the credential itself entering Gradle's task history.
     */
    fun fingerprint(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** Writes `<packageName>.<objectName>` as a Kotlin object whose [constants] are `const val` strings. */
    fun writeKotlinObject(
        directory: File,
        packageName: String,
        objectName: String,
        constants: Map<String, String>,
    ) {
        val packageDirectory =
            packageName.split('.').fold(directory) { parent, segment -> parent.resolve(segment) }
        packageDirectory.mkdirs()
        packageDirectory.resolve("$objectName.kt")
            .writeText(kotlinObjectSource(packageName, objectName, constants))
    }

    private fun kotlinObjectSource(
        packageName: String,
        objectName: String,
        constants: Map<String, String>,
    ): String =
        buildString {
            appendLine("// Generated at build time from provider credentials; see docs/build-credentials.md. Do not edit.")
            appendLine("package $packageName")
            appendLine()
            appendLine("/** Build-time provider credentials. A blank value means the dependent feature is not configured. */")
            appendLine("object $objectName {")
            constants.forEach { (constantName, value) ->
                appendLine("    const val $constantName: String = \"${escape(value, escapeDollar = true)}\"")
            }
            appendLine("}")
        }

    private fun escape(value: String, escapeDollar: Boolean): String =
        buildString {
            for (character in value) {
                when (character) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '$' -> if (escapeDollar) append("\\\$") else append('$')
                    else -> append(character)
                }
            }
        }
}
