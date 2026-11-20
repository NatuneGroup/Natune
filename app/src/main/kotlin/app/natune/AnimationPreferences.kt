/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package app.natune

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/** Reads the scale once. Returns 1 when the setting is missing, which is how a fresh device reads. */
fun Context.systemAnimationScale(): Float =
    runCatching {
        Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }.getOrDefault(1f)

/**
 * The live system animator duration scale. Compose's own animations follow this value through their
 * coroutine context already; timers that must wait for a Compose animation, and specs that reshape
 * themselves around the scale ([scaleAwareSpring]), read it from here. The developer option does
 * not recreate a running Activity, so a one-shot read goes stale — this observes the setting and
 * keeps the returned [State] current for as long as the caller is composed.
 */
@Composable
fun rememberAnimatorDurationScale(): State<Float> {
    val context = LocalContext.current.applicationContext
    val scale = remember { mutableFloatStateOf(context.systemAnimationScale()) }
    DisposableEffect(context) {
        val observer =
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    scale.floatValue = context.systemAnimationScale()
                }
            }
        context.contentResolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            false,
            observer,
        )
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
    return scale
}

