package moe.rukamori.archivetune.shared.ui.acoustic

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Builds an [ImageBitmap] from ARGB pixels produced by the acoustic heat raster. Each host binds it to
 * its Skia surface; a null answer leaves the graph with only its grid and legend.
 */
internal expect fun acousticHeatImage(pixels: IntArray, width: Int, height: Int): ImageBitmap?
