/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.desktop.playback

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Sends one TDLib request and suspends until its single reply arrives. TDLib answers an error as a [TdApi.Error] object,
 * which fails here with [TdlightRequestException], as does a send that is refused and a reply that never comes within
 * [timeoutMs]. The file adapter and the client session both use this, so they settle a request the same way.
 *
 * TDLib answers on its own receiver thread. The first outcome resumes the coroutine exactly once, and an answer that
 * arrives after a timeout or cancellation is dropped. The timeout is what stops a request sent to a dead client from
 * holding its caller forever.
 */
internal suspend fun Client.requestOk(operation: String, query: TdApi.Function<*>, timeoutMs: Long): TdApi.Object {
    val reply = withTimeoutOrNull(timeoutMs) { awaitReply(operation, query) }
        ?: throw TdlightRequestException("$operation got no reply within $timeoutMs ms")
    if (reply is TdApi.Error) {
        throw TdlightRequestException("$operation failed with TDLib error ${reply.code}: ${reply.message}")
    }
    return reply
}

private suspend fun Client.awaitReply(operation: String, query: TdApi.Function<*>): TdApi.Object =
    suspendCancellableCoroutine { continuation ->
        // The reply and the failure can come from different threads, so the first outcome wins by compare-and-set and
        // every later one is ignored rather than resuming the continuation twice.
        val settled = AtomicBoolean(false)
        try {
            send(
                query,
                Client.ResultHandler { reply ->
                    if (settled.compareAndSet(false, true)) continuation.resume(reply)
                },
                Client.ExceptionHandler { failure ->
                    if (settled.compareAndSet(false, true)) {
                        continuation.resumeWithException(TdlightRequestException("$operation failed", failure))
                    }
                },
            )
        } catch (failure: Exception) {
            if (settled.compareAndSet(false, true)) {
                continuation.resumeWithException(TdlightRequestException("$operation could not be sent", failure))
            }
        }
    }
