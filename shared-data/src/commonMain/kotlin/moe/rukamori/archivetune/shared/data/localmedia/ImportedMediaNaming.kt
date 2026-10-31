package moe.rukamori.archivetune.shared.data.localmedia

/** The number of collision suffixes one import tries before it gives up on the original name. */
private const val IMPORT_NAME_ATTEMPTS = 1000

/**
 * The name an imported file takes inside the app's import folder. The picked file's own leaf name is kept, its
 * extension is kept when it is short and alphanumeric, and every other character becomes `_`; a name already in use
 * gains a `-<n>` suffix. The result is always one plain file name, so a picker answer can never write outside the
 * folder. [taken] answers whether a candidate name already exists there.
 */
fun importedMediaFileName(original: String, taken: (String) -> Boolean): String {
    val leaf = original.substringAfterLast('/').substringAfterLast('\\')
    val dot = leaf.lastIndexOf('.')
    val extension = (if (dot > 0) leaf.substring(dot + 1) else "").plainExtension()
    val base = sanitizeImportStem(if (dot > 0) leaf.substring(0, dot) else leaf)
    val preferred = base + extension
    if (!taken(preferred)) return preferred
    for (index in 1 until IMPORT_NAME_ATTEMPTS) {
        val candidate = "$base-$index$extension"
        if (!taken(candidate)) return candidate
    }
    error("no free imported file name for $original")
}

/** The extension of an imported name, in the plain ASCII form a media player and a case-insensitive filesystem agree on. */
private fun String.plainExtension(): String {
    if (length !in 1..8) return ""
    if (!all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }) return ""
    return "." + lowercase()
}

/** The stem of an imported name: plain file characters only, never empty and never a dot entry. */
private fun sanitizeImportStem(value: String): String {
    val mapped = value.map { character ->
        if (character.isLetterOrDigit() || character == '-' || character == '_' || character == ' ') character else '_'
    }.joinToString("").trim(' ', '_', '-')
    return mapped.ifEmpty { "imported" }
}
