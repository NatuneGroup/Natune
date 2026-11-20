package app.natune.constants

import androidx.media3.ui.AspectRatioFrameLayout

/** The ExoPlayer resize mode for [this] ratio, as documented on [VideoAspectRatio]. */
fun VideoAspectRatio.toExoResizeMode(): Int =
    when (this) {
        VideoAspectRatio.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        VideoAspectRatio.CROP -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        VideoAspectRatio.STRETCH -> AspectRatioFrameLayout.RESIZE_MODE_FILL
        VideoAspectRatio.FILL -> AspectRatioFrameLayout.RESIZE_MODE_FILL
    }
