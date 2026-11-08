package moe.rukamori.archivetune.shared.data.provider

import moe.rukamori.archivetune.shared.domain.repository.ContentCredit

/** Artist links may follow a type label or an album, and may span several subtitle groups. */
internal fun <T> youTubeArtistCredits(
    groups: List<List<T>>,
    text: (T) -> String?,
    browseId: (T) -> String?,
): List<ContentCredit> = groups.flatten().mapNotNull { run ->
    val id = browseId(run)?.takeIf { it.startsWith("UC") || it.startsWith("FEMC") }
    val name = text(run)?.trim()?.takeIf(String::isNotBlank)
    if (id != null && name != null) ContentCredit(name, id) else null
}.distinctBy { it.id }

internal fun youTubeArtistSubtitle(artists: List<ContentCredit>, fallback: String): String =
    artists.joinToString(", ") { it.name }.ifBlank {
        fallback.takeUnless { it.trim() in setOf("Song", "Video", "Episode") }.orEmpty()
    }
