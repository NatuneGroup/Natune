package moe.rukamori.archivetune.shared.data.lyrics

import kotlin.coroutines.cancellation.CancellationException

/**
 * Captures a suspending call's outcome as a [Result] without swallowing cancellation: a
 * cancelled coroutine must unwind to its scheduler instead of looking like a provider miss.
 */
internal suspend inline fun <T> suspendRunCatching(crossinline block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        Result.failure(error)
    }
