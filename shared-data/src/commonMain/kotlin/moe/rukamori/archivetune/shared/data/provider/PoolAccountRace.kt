package moe.rukamori.archivetune.shared.data.provider

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.domain.repository.TerminalPlaybackAuthentication

/**
 * How many pooled accounts one resolve may ask at once. The pool lends shared subscriber accounts,
 * so the cap stays small: a wider fan-out would multiply the load each resolve puts on the feed
 * without a correctness gain, and one answer is all a resolve needs.
 */
const val MAX_CONCURRENT_POOL_ATTEMPTS = 3

/**
 * What one [racePoolAttempts] run settled on. [winner] is the first successful answer, or null when
 * every attempt missed. [failure] is the last ordinary failure an attempt produced, matching the
 * sequential walk that rethrows its last failure once every attempt has missed; terminal
 * authentication refusals are reported to the pool but, exactly like that walk, never become the
 * failure the caller rethrows.
 */
class PoolRaceOutcome<out R>(val winner: R?, val failure: Exception?)

private class PoolRaceResult<R>(val resolved: R?, val error: Exception?, val cancelled: Boolean)

/**
 * Races the pooled-account tier of a resolve: at most [maxConcurrent] attempts are in flight, the
 * first non-null answer wins and the still-running losers are cancelled. Every attempt that
 * actually ran reports to the pool exactly the way the sequential walk reports today — the winner
 * calls [noteSuccess] once; a terminal authentication refusal or an HTTP 401 calls [reportDead]
 * and then [noteFailure]; any other exception calls [noteFailure] alone. An attempt cancelled
 * because another attempt won reports nothing, and an attempt still queued when the race settles
 * reports nothing either, so no account is counted twice or blamed for work it never did. A
 * [CancellationException] out of an attempt aborts the whole race and propagates, as the sequential
 * walk does. Each account is tried once — no retries inside the race.
 */
suspend fun <A, R : Any> racePoolAttempts(
    attempts: List<A>,
    resolve: suspend (A) -> R?,
    reportDead: suspend (A) -> Unit,
    noteSuccess: suspend (A) -> Unit,
    noteFailure: suspend (A) -> Unit,
    maxConcurrent: Int = MAX_CONCURRENT_POOL_ATTEMPTS,
): PoolRaceOutcome<R> {
    require(maxConcurrent >= 1) { "Pool attempt race needs at least one lane" }
    if (attempts.isEmpty()) return PoolRaceOutcome(null, null)
    return coroutineScope {
        val pending = Channel<A>(attempts.size)
        attempts.forEach { pending.trySend(it) }
        pending.close()
        val outcomes = Channel<Pair<A, PoolRaceResult<R>>>(Channel.UNLIMITED)
        val workers = List(minOf(maxConcurrent, attempts.size)) {
            launch {
                for (attempt in pending) {
                    val result: PoolRaceResult<R> = try {
                        PoolRaceResult<R>(resolved = resolve(attempt), error = null, cancelled = false)
                    } catch (cancelled: CancellationException) {
                        // A lane dying on cancellation still drops its outcome so the collector
                        // propagates the cancellation instead of waiting on attempts nobody runs.
                        outcomes.trySend(attempt to PoolRaceResult<R>(null, null, cancelled = true))
                        throw cancelled
                    } catch (error: Exception) {
                        PoolRaceResult<R>(resolved = null, error = error, cancelled = false)
                    }
                    outcomes.trySend(attempt to result)
                }
            }
        }
        var winner: R? = null
        var failure: Exception? = null
        var settled = 0
        try {
            while (settled < attempts.size) {
                val (attempt, result) = outcomes.receive()
                settled += 1
                when {
                    result.cancelled -> throw CancellationException("a pooled attempt was cancelled")
                    result.resolved != null -> {
                        noteSuccess(attempt)
                        winner = result.resolved
                        break
                    }
                    result.error != null -> {
                        val error = result.error
                        if (error is TerminalPlaybackAuthentication ||
                            (error as? ProviderHttpFailure)?.status == 401
                        ) {
                            reportDead(attempt)
                        }
                        noteFailure(attempt)
                        if (error !is TerminalPlaybackAuthentication) failure = error
                    }
                }
            }
        } finally {
            workers.forEach { it.cancel() }
        }
        PoolRaceOutcome(winner, failure)
    }
}
