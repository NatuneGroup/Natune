package moe.rukamori.archivetune.ui.menu

import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
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

/** The caret sits at the end of prefilled text, so typing continues the query instead of inserting before it. */
internal fun sourceSearchFieldValue(query: String): TextFieldValue = TextFieldValue(query, TextRange(query.length))

/** Keeps the field's text and caret across recomposition and saved-state restore. */
internal val sourceSearchFieldSaver: Saver<TextFieldValue, Any> = TextFieldValue.Saver
