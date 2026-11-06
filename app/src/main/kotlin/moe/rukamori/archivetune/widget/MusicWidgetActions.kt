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
import android.os.Looper
import android.util.Log
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import moe.rukamori.archivetune.playback.MusicService

class PlayPauseAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        sendWidgetTransportCommand(context, WidgetTransportCommand.PLAY_PAUSE)
    }
}

class SkipNextAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        sendWidgetTransportCommand(context, WidgetTransportCommand.SKIP_NEXT)
    }
}

class SkipPrevAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        sendWidgetTransportCommand(context, WidgetTransportCommand.SKIP_PREVIOUS)
    }
}

internal enum class WidgetTransportCommand {
    PLAY_PAUSE,
    SKIP_NEXT,
    SKIP_PREVIOUS,
}

internal interface WidgetTransport {
    val isPlaying: Boolean

    fun play()

    fun pause()

    fun seekToNext()

    fun seekToPrevious()
}

internal fun WidgetTransportCommand.applyTo(transport: WidgetTransport) {
    when (this) {
        WidgetTransportCommand.PLAY_PAUSE -> if (transport.isPlaying) transport.pause() else transport.play()
        WidgetTransportCommand.SKIP_NEXT -> transport.seekToNext()
        WidgetTransportCommand.SKIP_PREVIOUS -> transport.seekToPrevious()
    }
}

private class MediaControllerTransport(
    private val controller: MediaController,
) : WidgetTransport {
    override val isPlaying: Boolean
        get() = controller.isPlaying

    override fun play() = controller.play()

    override fun pause() = controller.pause()

    override fun seekToNext() = controller.seekToNext()

    override fun seekToPrevious() = controller.seekToPrevious()
}

private const val TAG = "MusicWidgetActions"
private const val WIDGET_CONTROLLER_TIMEOUT_MILLIS = 5_000L

/**
 * Drives the existing media session instead of starting [MusicService] directly.
 *
 * A Glance action callback runs from a broadcast receiver while the app is in the background, so
 * `startService` is rejected on Android 8+ (and was only logged before). A [MediaController] built
 * from the service's [SessionToken] is the transport path media sessions already support for
 * notification, car and in-process clients; it needs no foreground-service start and works for
 * play/pause as well as skip. Failures are logged rather than silently dropped.
 */
private suspend fun sendWidgetTransportCommand(
    context: Context,
    command: WidgetTransportCommand,
) {
    val appContext = context.applicationContext
    val token = SessionToken(appContext, ComponentName(appContext, MusicService::class.java))
    val controllerFuture =
        try {
            MediaController
                .Builder(appContext, token)
                .setApplicationLooper(Looper.getMainLooper())
                .buildAsync()
        } catch (error: Exception) {
            Log.e(TAG, "Could not connect to the media session for $command", error)
            return
        }

    try {
        withContext(Dispatchers.Main.immediate) {
            val controller =
                withTimeoutOrNull(WIDGET_CONTROLLER_TIMEOUT_MILLIS) { controllerFuture.await() }
            if (controller == null) {
                Log.w(TAG, "Timed out connecting to the media session for $command")
                return@withContext
            }
            command.applyTo(MediaControllerTransport(controller))
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Log.e(TAG, "Widget transport command $command failed", error)
    } finally {
        withContext(NonCancellable + Dispatchers.Main.immediate) {
            MediaController.releaseFuture(controllerFuture)
        }
    }
}
