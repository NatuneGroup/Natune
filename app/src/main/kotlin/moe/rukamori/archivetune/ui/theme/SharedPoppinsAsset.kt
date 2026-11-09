package moe.rukamori.archivetune.ui.theme

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.AndroidFont
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontLoadingStrategy
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import moe.rukamori.archivetune.shared.ui.resources.Res
import org.jetbrains.compose.resources.ExperimentalResourceApi

/** Android's legacy Compose theme and Markwon use the exact same Regular bytes as shared UI. */
@OptIn(ExperimentalResourceApi::class)
internal fun sharedPoppinsAssetPath(): String {
    // Use the generated accessor so Compose's package/path layout is never duplicated here.
    val uri = Uri.parse(Res.getUri("font/m4_poppins.ttf"))
    val path = checkNotNull(uri.path)
    check(uri.scheme == "file" && path.startsWith("/android_asset/"))
    return path.removePrefix("/android_asset/")
}

// The resolver supplies the current Context; font declarations never capture an Application singleton.
private object SharedPoppinsLoader : AndroidFont.TypefaceLoader {
    override fun loadBlocking(context: Context, font: AndroidFont): Typeface =
        Typeface.createFromAsset(context.assets, sharedPoppinsAssetPath())

    override suspend fun awaitLoad(context: Context, font: AndroidFont): Typeface =
        loadBlocking(context, font)
}

@OptIn(ExperimentalTextApi::class)
internal val SharedPoppinsFont: Font = object : AndroidFont(
    FontLoadingStrategy.Blocking,
    SharedPoppinsLoader,
    FontVariation.Settings(),
) {
    override val weight: FontWeight = FontWeight.Normal
    override val style: FontStyle = FontStyle.Normal
}
