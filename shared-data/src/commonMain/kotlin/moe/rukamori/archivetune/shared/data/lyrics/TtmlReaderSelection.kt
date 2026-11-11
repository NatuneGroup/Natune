package moe.rukamori.archivetune.shared.data.lyrics

import kotlinx.coroutines.CancellationException
import moe.rukamori.archivetune.shared.core.rustTtmlReaderOrNull
import moe.rukamori.archivetune.shared.domain.lyrics.KotlinTtmlDocumentReader
import moe.rukamori.archivetune.shared.domain.lyrics.TtmlDocumentReader
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.domain.settings.RustTtmlParserKey

/** Composition owns the flag. The reader and FFI crates contain no selection policy. */
fun ttmlDocumentReader(preferences: PreferenceSnapshot?): TtmlDocumentReader =
    if (preferences?.get(RustTtmlParserKey) == true) rustTtmlReaderOrNull()?.let(::withKotlinFallback) ?: KotlinTtmlDocumentReader
    else KotlinTtmlDocumentReader

/**
 * A native failure after a successful load (a caught panic, a binding error) must not cost the user
 * their lyrics. Refused XML is not a failure: it returns an empty document and is not retried.
 */
internal fun withKotlinFallback(native: TtmlDocumentReader) = TtmlDocumentReader { raw ->
    try {
        native.readTtml(raw)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        KotlinTtmlDocumentReader.readTtml(raw)
    }
}
