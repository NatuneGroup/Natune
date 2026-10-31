package moe.rukamori.archivetune.shared.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.ensureActive

/**
 * The cover a player slot shows for [key], a value that changes whenever the slot should show another image.
 *
 * A slot that already holds the image answers at once. Otherwise it keeps the image it showed last while [load]
 * fetches the next one, so a track change does not blank the cover. Metrolist's CrossfadeArtwork keeps its last
 * successful painter the same way. Only a loaded result replaces the held image. A failed load shows no image, and a
 * superseded load is dropped, so the cover of one track never outlasts that track's own load. The blend between
 * occurrences stays in rememberPlayerHandoff; this only holds the cover across loads.
 */
@Composable
fun rememberCoverImage(key: Any?, cached: () -> ImageBitmap?, load: suspend () -> ImageBitmap?): ImageBitmap? {
    if (key == null) return null
    val instant = cached()
    val held by produceState<ImageBitmap?>(initialValue = instant, key1 = key) {
        val now = cached()
        if (now != null) {
            value = now
            return@produceState
        }
        val loaded = load()
        ensureActive()
        value = loaded
    }
    return instant ?: held
}
