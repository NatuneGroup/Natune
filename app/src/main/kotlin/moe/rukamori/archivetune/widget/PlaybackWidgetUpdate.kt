/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.widget

import android.content.ComponentName
import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import moe.rukamori.archivetune.playback.MusicService

private const val WIDGET_BIND_TIMEOUT_MS = 5_000L

/**
 * Asks a running [MusicService] to push its current state to the widgets.
 *
 * - Binds through the application context: the context a manifest receiver gets in onReceive is a
 *   ReceiverRestrictedContext whose bindService always throws, and the old runCatching swallowed
 *   that, so this request never reached the service.
 * - Binds without BIND_AUTO_CREATE (never starts playback just to draw a widget). Such a binding
 *   stays pending until the service is created, so it is always unbound after
 *   [WIDGET_BIND_TIMEOUT_MS]; before, every widget update while the service was down left one more
 *   pending ServiceConnection registered for the life of the process.
 * - A binder whose service is already destroyed throws from [MusicService.MusicBinder.service];
 *   that used to escape onServiceConnected on the main thread.
 */
internal suspend fun requestPlaybackWidgetUpdate(context: Context) {
    val appContext = context.applicationContext
    val connected = CompletableDeferred<Unit>()
    val connection =
        object : android.content.ServiceConnection {
            override fun onServiceConnected(
                name: ComponentName?,
                binder: android.os.IBinder?,
            ) {
                runCatching { (binder as? MusicService.MusicBinder)?.service?.updateWidget() }
                connected.complete(Unit)
            }

            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }

    val bound =
        runCatching {
            appContext.bindService(android.content.Intent(appContext, MusicService::class.java), connection, 0)
        }.getOrDefault(false)
    try {
        if (bound) withTimeoutOrNull(WIDGET_BIND_TIMEOUT_MS) { connected.await() }
    } finally {
        // Required even when bindService returned false.
        runCatching { appContext.unbindService(connection) }
    }
}
