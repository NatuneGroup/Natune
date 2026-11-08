package moe.rukamori.archivetune.modules

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI
import java.net.URLDecoder
import javax.imageio.ImageIO
import io.ktor.http.URLBuilder

/** JVM dependencies of the retained Android module reader/runtime. The JS host APIs are unchanged. */
class ModuleHostEnvironment(val versionName: String = ARCHIVETUNE_MODULE_COMPATIBILITY_VERSION) {
    fun openAsset(name: String): InputStream = requireNotNull(javaClass.getResourceAsStream("/$name"))
}

internal object ModuleBase64 {
    const val DEFAULT = 0
    const val NO_PADDING = 1
    const val NO_WRAP = 2
    const val URL_SAFE = 8
    fun decode(value: String, flags: Int): ByteArray =
        (if (flags and URL_SAFE != 0) java.util.Base64.getUrlDecoder() else java.util.Base64.getMimeDecoder()).decode(value)
    fun encodeToString(value: ByteArray, flags: Int): String {
        var encoder = if (flags and URL_SAFE != 0) java.util.Base64.getUrlEncoder() else java.util.Base64.getEncoder()
        if (flags and NO_PADDING != 0) encoder = encoder.withoutPadding()
        return encoder.encodeToString(value)
    }
}
internal class ModuleBitmap(private val image: BufferedImage) {
    enum class CompressFormat { PNG }
    fun compress(format: CompressFormat, quality: Int, output: ByteArrayOutputStream): Boolean = ImageIO.write(image, "png", output)
    fun recycle() { image.flush() }
}
internal object ModuleBitmapFactory {
    class Options {
        var inJustDecodeBounds = false
        var outWidth = 0
        var outHeight = 0
        var outMimeType = ""
    }
    fun decodeByteArray(bytes: ByteArray, offset: Int, length: Int, options: Options? = null): ModuleBitmap? {
        ImageIO.createImageInputStream(ByteArrayInputStream(bytes, offset, length)).use { input ->
            if (input == null) return null
            val readers = ImageIO.getImageReaders(input)
            if (!readers.hasNext()) return null
            val reader = readers.next()
            try {
                reader.input = input
                val width = reader.getWidth(0)
                val height = reader.getHeight(0)
                options?.apply { outWidth = width; outHeight = height; outMimeType = "image/${reader.formatName.lowercase().let { if (it == "jpg") "jpeg" else it }}" }
                if (options?.inJustDecodeBounds == true || width !in 1..1024 || height !in 1..1024) return null
                return ModuleBitmap(reader.read(0))
            } finally { reader.dispose() }
        }
    }
}
internal class ModuleUri private constructor(private val value: String) {
    private val uri = URI(value)
    val scheme get() = uri.scheme
    val host get() = uri.host
    val port get() = uri.port
    val encodedUserInfo get() = uri.rawUserInfo
    val path get() = uri.path
    val fragment get() = uri.fragment
    val queryParameterNames get() = uri.rawQuery.orEmpty().split('&').filter(String::isNotEmpty)
        .map { URLDecoder.decode(it.substringBefore('='), Charsets.UTF_8) }.toSet()
    fun getQueryParameters(name: String): List<String> = uri.rawQuery.orEmpty().split('&').mapNotNull {
        if (URLDecoder.decode(it.substringBefore('='), Charsets.UTF_8) == name)
            URLDecoder.decode(it.substringAfter('=', ""), Charsets.UTF_8) else null
    }
    fun getQueryParameter(name: String) = getQueryParameters(name).firstOrNull()
    fun buildUpon() = Builder(value)
    override fun toString() = value
    class Builder(value: String) {
        private val url = URLBuilder(value)
        fun clearQuery() = apply { url.parameters.clear() }
        fun appendQueryParameter(name: String, value: String) = apply { url.parameters.append(name, value) }
        fun build() = ModuleUri(url.buildString())
    }
    companion object { fun parse(value: String) = ModuleUri(value) }
}
