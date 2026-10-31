package moe.rukamori.archivetune.shared.ui.host

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlin.random.Random

/** A stored `#RRGGBB` tag colour. An unreadable value shows the default tag colour rather than failing the list. */
fun playlistTagColor(hex: String): Color {
    val digits = hex.removePrefix("#")
    val rgb = digits.takeIf { it.length == 6 }?.toLongOrNull(16) ?: DEFAULT_TAG_RGB
    return Color(0xFF000000L or rgb)
}

/** The `#RRGGBB` form the tag store keeps. Alpha is dropped, because the store has no alpha channel. */
fun playlistTagHex(color: Color): String =
    "#" + (color.toArgb() and 0xFFFFFF).toString(16).uppercase().padStart(6, '0')

/** The baseline tag palette, in the order the Android tag editor offers it. */
val PlaylistTagPalette: List<Color> = listOf(
    "#FF6B6B", "#4ECDC4", "#45B7D1", "#FFA07A", "#98D8C8", "#F7DC6F", "#BB8FCE", "#85C1E2",
    "#F8B739", "#52B788", "#E63946", "#457B9D", "#F4A261", "#2A9D8F", "#E76F51", "#264653",
    "#E9C46A", "#F4A6C1", "#8E44AD", "#16A085", "#C0392B", "#D35400", "#7D3C98", "#1ABC9C", "#3498DB",
).map(::playlistTagColor)

/**
 * A new local playlist id in the baseline shape: the "LP" prefix and eight random ASCII letters. The id is a handle,
 * not a secret, and the insert refuses an existing id, so an ordinary random source is enough.
 */
fun randomLocalPlaylistId(random: Random = Random.Default): String =
    "LP" + CharArray(PLAYLIST_ID_LETTERS) { PLAYLIST_ID_ALPHABET[random.nextInt(PLAYLIST_ID_ALPHABET.length)] }.concatToString()

private const val DEFAULT_TAG_RGB = 0xFF6B6BL
private const val PLAYLIST_ID_LETTERS = 8
private const val PLAYLIST_ID_ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
