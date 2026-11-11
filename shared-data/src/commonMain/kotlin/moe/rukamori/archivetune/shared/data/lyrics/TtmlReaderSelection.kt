package moe.rukamori.archivetune.shared.data.lyrics

import moe.rukamori.archivetune.shared.core.rustTtmlReaderOrNull
import moe.rukamori.archivetune.shared.domain.lyrics.KotlinTtmlDocumentReader
import moe.rukamori.archivetune.shared.domain.lyrics.TtmlDocumentReader
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.domain.settings.RustTtmlParserKey

/** Composition owns the flag. The reader and FFI crates contain no selection policy. */
fun ttmlDocumentReader(preferences: PreferenceSnapshot?): TtmlDocumentReader =
    if (preferences?.get(RustTtmlParserKey) == true) rustTtmlReaderOrNull() ?: KotlinTtmlDocumentReader
    else KotlinTtmlDocumentReader
