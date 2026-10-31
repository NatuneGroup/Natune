package moe.rukamori.archivetune.ui.menu

import moe.rukamori.archivetune.models.MediaMetadata

/**
 * Entering search opens on the song's title and artists, so the providers answer without the user typing first.
 * Blank parts are dropped so a missing artist cannot leave a stray space in the query.
 */
internal fun MediaMetadata.sourceSearchSeed(): String =
    (listOf(title) + artists.map { it.name })
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString(" ")
