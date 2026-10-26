package moe.rukamori.archivetune.shared.domain.lyrics.romanization

import com.ibm.icu.text.Transliterator

/**
 * The `Any-Latin; Latin-ASCII` pass from ICU4J, the same transform the Android lyric code asks
 * `android.icu` for. ICU transliterators are not thread-safe, so each thread builds its own, as the
 * Android code does.
 */
class IcuLyricsTransliterator : LyricsTransliterator {
    private val transliterators: ThreadLocal<Transliterator> =
        ThreadLocal.withInitial { Transliterator.getInstance(GENERIC_ROMANIZATION_TRANSFORM) }

    override fun toLatin(text: String): String? =
        try {
            transliterators.get().transliterate(text)
        } catch (_: RuntimeException) {
            null
        }

    private companion object {
        const val GENERIC_ROMANIZATION_TRANSFORM = "Any-Latin; Latin-ASCII"
    }
}
