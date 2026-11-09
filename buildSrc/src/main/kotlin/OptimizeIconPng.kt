package moe.rukamori.archivetune.build

import org.apache.batik.ext.awt.image.rendered.IndexImage
import java.awt.image.BufferedImage
import java.awt.image.DataBuffer
import java.awt.image.IndexColorModel
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO

/** Use Batik's existing median-cut palette encoder; no host WebP executable/dependency is needed. */
internal fun optimizeOpaqueIconPng(file: File) {
    val original = ImageIO.read(file) ?: error("Unreadable generated icon: ${file.name}")
    val width = original.width
    val height = original.height
    val pixels = original.getRGB(0, 0, width, height, null, 0, width)
    // Batik's palette generator ignores alpha. Leave every translucent raster byte-for-byte intact.
    if (pixels.any { it ushr 24 != 255 }) return

    // Reserve four palette entries so adaptive-background corner inspection remains exact.
    val indexed = IndexImage.getIndexedImage(original, 252)
    val oldPalette = indexed.colorModel as IndexColorModel
    val paletteColors = IntArray(oldPalette.mapSize + 4)
    oldPalette.getRGBs(paletteColors)
    val corners = intArrayOf(0, width - 1, (height - 1) * width, pixels.lastIndex)
    corners.forEachIndexed { index, offset -> paletteColors[oldPalette.mapSize + index] = pixels[offset] }
    val palette = IndexColorModel(8, paletteColors.size, paletteColors, 0, false, -1, DataBuffer.TYPE_BYTE)
    val result = BufferedImage(width, height, BufferedImage.TYPE_BYTE_INDEXED, palette)
    result.raster.setRect(indexed.raster)
    corners.forEachIndexed { index, offset ->
        result.raster.setSample(offset % width, offset / width, 0, oldPalette.mapSize + index)
    }

    // Keep the original if the palette loses too much color detail (RGB PSNR below 36 dB).
    val quantized = result.getRGB(0, 0, width, height, null, 0, width)
    var squaredError = 0L
    val channelShifts = intArrayOf(16, 8, 0)
    pixels.indices.forEach { index ->
        for (shift in channelShifts) {
            val delta = ((pixels[index] ushr shift) and 255) - ((quantized[index] ushr shift) and 255)
            squaredError += delta.toLong() * delta
        }
    }
    if (squaredError > pixels.size.toLong() * 3 * 16) return
    val output = ByteArrayOutputStream()
    check(ImageIO.write(result, "png", output)) { "No PNG writer for ${file.name}" }
    val bytes = output.toByteArray()
    if (bytes.size < file.length()) file.writeBytes(bytes)
}
