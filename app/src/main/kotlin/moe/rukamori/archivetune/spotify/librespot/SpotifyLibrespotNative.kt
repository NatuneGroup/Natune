package moe.rukamori.archivetune.spotify.librespot

import java.io.IOException
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import moe.rukamori.archivetune.shared.domain.source.SpotifyLibrespotFailure

internal class SpotifyLibrespotAudioException(val failure: SpotifyLibrespotFailure) :
    IOException("Spotify Librespot: ${failure.name}")

/** The native worker never holds a coroutine thread hostage; cancellation revokes its session. */
internal object SpotifyLibrespotNative {
    val available: Boolean by lazy {
        try {
            System.loadLibrary("natune_librespot")
            true
        } catch (_: LinkageError) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "SpotifyLibrespot").apply { isDaemon = true }
    }

    suspend fun request(onCancelled: () -> Unit, operation: () -> String): String {
        if (!available) throw SpotifyLibrespotAudioException(SpotifyLibrespotFailure.NATIVE_UNAVAILABLE)
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { onCancelled() }
            worker.execute {
                if (!continuation.isActive) return@execute
                try {
                    val response = operation()
                    continuation.resume(response)
                } catch (_: Throwable) {
                    continuation.resumeWithException(SpotifyLibrespotAudioException(SpotifyLibrespotFailure.INVALID_RESPONSE))
                }
            }
        }
    }

    fun close() {
        if (available) nativeClose()
    }

    external fun nativeConnect(
        token: String,
        userId: String,
        deviceId: String,
        cacheDir: String,
        proxy: String,
        androidApiLevel: Int,
        timeoutMs: Long,
    ): String

    external fun nativeResolve(trackUri: String, formats: IntArray, timeoutMs: Long): String

    external fun nativeSessionValid(): Boolean

    private external fun nativeClose()
}
