package moe.rukamori.archivetune.shared.data.download

import moe.rukamori.archivetune.shared.domain.source.AudioSourceType

/** The longest single path component a download writes; a longer one is cut rather than refused by the filesystem. */
const val MAX_DOWNLOAD_COMPONENT_LENGTH = 96

/**
 * One download's path component. A provider id, media id or quality is opaque and can carry separators, so every
 * character that is not a plain file character becomes `_`. A component that is empty or only dots becomes a name,
 * because `..` would name a parent directory and let a download write outside its own folder.
 */
fun sanitizeDownloadComponent(value: String): String {
    val mapped = buildString {
        for (character in value) {
            if (length >= MAX_DOWNLOAD_COMPONENT_LENGTH) break
            append(if (character.isLetterOrDigit() || character == '.' || character == '_' || character == '-') character else '_')
        }
    }
    return when {
        mapped.isEmpty() -> "_"
        mapped.all { character -> character == '.' } -> "_$mapped"
        else -> mapped
    }
}

/** The file name of one download: the media id and the quality, under [extension], all inside the length bound. */
fun downloadFileName(mediaId: String, quality: String, extension: String = "media"): String {
    val suffix = "." + sanitizeDownloadComponent(extension)
    val name = sanitizeDownloadComponent(mediaId) + "_" + sanitizeDownloadComponent(quality) + suffix
    if (name.length <= MAX_DOWNLOAD_COMPONENT_LENGTH) return name
    // A cut name keeps the extension, so the file is still named by its own container.
    return name.take(MAX_DOWNLOAD_COMPONENT_LENGTH - suffix.length) + suffix
}

/**
 * The directories of one download under the store root, then its file name: `provider/source/<mediaId>_<quality>.<ext>`.
 * Two providers naming one media id, and two qualities of one song, therefore never share a file.
 */
fun downloadRelativePath(
    provider: String,
    mediaId: String,
    source: AudioSourceType,
    quality: String,
    extension: String = "media",
): List<String> = listOf(
    sanitizeDownloadComponent(provider),
    sanitizeDownloadComponent(source.name.lowercase()),
    downloadFileName(mediaId, quality, extension),
)

/** Joins the components of one relative path under [root] with a plain separator; no component can leave [root]. */
fun downloadAbsolutePath(root: String, relative: List<String>): String =
    root.trimEnd('/') + "/" + relative.joinToString("/")
