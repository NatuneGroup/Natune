/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.allowHardware

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
 * rather than the held one, so one track's art never outlasts its own load. [onError] lets a caller move to a fallback
 * URL. Lists keep [rememberOfflineArtworkImageRequest], which has no held cover.
 */
@Composable
internal fun PlayerCoverImage(
    imageUrl: String?,
    contentScale: ContentScale,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    onError: ((coil3.compose.AsyncImagePainter.State.Error) -> Unit)? = null,
) {
    val held = remember { mutableStateOf<String?>(null) }
    HeldPlayerCover(
        imageUrl = imageUrl,
        held = held,
        contentScale = contentScale,
        modifier = modifier,
        contentDescription = contentDescription,
        onError = onError,
    )
}

/**
 * A cover slot whose last loaded URL is owned by the caller in [held]. Use it where the content is recreated for each
 * track, such as an AnimatedContent child: the caller keeps [held] outside that content, so the new child can show the
 * previous cover while its own load runs. [downsampleTo] caps the decoded size of a background that is blurred anyway.
 */
@Composable
internal fun HeldPlayerCover(
    imageUrl: String?,
    held: MutableState<String?>,
    contentScale: ContentScale,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    downsampleTo: Int? = null,
    onError: ((coil3.compose.AsyncImagePainter.State.Error) -> Unit)? = null,
) {
    val url = imageUrl?.trim()?.takeIf(String::isNotBlank)
    val request = rememberPlayerCoverRequest(url, held.value, downsampleTo)
    AsyncImage(
        model = request,
        contentDescription = contentDescription,
        contentScale = contentScale,
        modifier = modifier,
        onSuccess = { held.value = url },
        onError = onError,
    )
}

@Composable
private fun rememberPlayerCoverRequest(url: String?, heldUrl: String?, downsampleTo: Int?): ImageRequest? {
    val context = LocalContext.current
    // Keyed on the URL and size, never on the held cover. A cover that just loaded must not rebuild the request,
    // because the rebuilt request would start without a placeholder and blank the image for a frame. The held
    // cover is therefore read when the URL changes, which is when the placeholder is needed.
    return remember(context, url, downsampleTo) {
        url?.let { current ->
            ImageRequest
                .Builder(context)
                .data(current)
                .memoryCacheKey(current)
                .placeholderMemoryCacheKey(heldUrl?.takeIf { it != current })
                .diskCacheKey(current)
                .diskCachePolicy(CachePolicy.ENABLED)
                .networkCachePolicy(CachePolicy.ENABLED)
                .let { builder ->
                    if (downsampleTo != null) builder.size(downsampleTo, downsampleTo).allowHardware(false) else builder
                }.build()
        }
    }
}
