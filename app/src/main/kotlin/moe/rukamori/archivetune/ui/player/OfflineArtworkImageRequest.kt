/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest

@Composable
internal fun rememberOfflineArtworkImageRequest(imageUrl: String?): ImageRequest? {
    val context = LocalContext.current
    return remember(context, imageUrl) {
        imageUrl
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?.let { url ->
                ImageRequest
                    .Builder(context)
                    .data(url)
                    .memoryCacheKey(url)
                    .diskCacheKey(url)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .networkCachePolicy(CachePolicy.ENABLED)
                    .build()
            }
    }
}

/**
 * A player cover. It shows [imageUrl] and, while that loads, keeps on screen the cover that last loaded in this slot
 * instead of a blank placeholder, so the cover does not flash between tracks. A failed or missing cover shows nothing
 * rather than the held one, so one track's art never outlasts its own load. Lists keep
 * [rememberOfflineArtworkImageRequest], which has no held cover.
 */
@Composable
internal fun PlayerCoverImage(
    imageUrl: String?,
    contentScale: ContentScale,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val url = imageUrl?.trim()?.takeIf(String::isNotBlank)
    var heldUrl by remember { mutableStateOf<String?>(null) }
    val request = rememberPlayerCoverRequest(url, heldUrl)
    AsyncImage(
        model = request,
        contentDescription = contentDescription,
        contentScale = contentScale,
        modifier = modifier,
        onSuccess = { heldUrl = url },
    )
}

@Composable
private fun rememberPlayerCoverRequest(url: String?, heldUrl: String?): ImageRequest? {
    val context = LocalContext.current
    // Keyed on the URL alone. A cover that just loaded must not rebuild the request, because the
    // rebuilt request would start without a placeholder and blank the image for a frame. The held
    // cover is therefore read when the URL changes, which is when the placeholder is needed.
    return remember(context, url) {
        url?.let { current ->
            ImageRequest
                .Builder(context)
                .data(current)
                .memoryCacheKey(current)
                .placeholderMemoryCacheKey(heldUrl?.takeIf { it != current })
                .diskCacheKey(current)
                .diskCachePolicy(CachePolicy.ENABLED)
                .networkCachePolicy(CachePolicy.ENABLED)
                .build()
        }
    }
}
