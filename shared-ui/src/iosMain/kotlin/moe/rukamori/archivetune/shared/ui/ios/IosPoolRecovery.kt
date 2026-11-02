package moe.rukamori.archivetune.shared.ui.ios

import kotlinx.coroutines.CancellationException
import moe.rukamori.archivetune.shared.data.platform.ios.IosPlaybackPool
import moe.rukamori.archivetune.shared.data.provider.PoolFailureKind
import moe.rukamori.archivetune.shared.data.provider.PoolRetryOutcome
import moe.rukamori.archivetune.shared.data.provider.classifyPoolFailure
import moe.rukamori.archivetune.shared.domain.repository.PreparedAudio

/**
 * The iOS resolvers' shared pooled-account recovery, run through the pool's
 * [IosPlaybackPool.deadReports] policy: one re-lease (single-flight per account, throttled per
 * service), then one retry with the credential the pool now serves; the account is reported dead
 * only when that fresh credential is refused again for real. [resolveFresh] re-reads the pool's
 * current account and resolves with it — a null answer is a transient failure. Answers the retry's
 * stream, or null.
 */
internal suspend fun IosPlaybackPool.recoverPooledAccount(
    service: String,
    poolId: Long?,
    stale: Any,
    kind: PoolFailureKind,
    current: suspend () -> Any?,
    resolveFresh: suspend () -> PreparedAudio?,
): PreparedAudio? {
    val id = poolId ?: return null
    return deadReports.recoverAndReport<Any, PreparedAudio>(
        service, id, kind,
        stale = stale,
        current = current,
        recover = { refresh(force = true) },
        retry = {
            try {
                resolveFresh()?.let { PoolRetryOutcome.Success(it) }
                    ?: PoolRetryOutcome.Failure(PoolFailureKind.TRANSIENT)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                PoolRetryOutcome.Failure(classifyPoolFailure(e))
            }
        },
        report = { type -> report(service, id, type) },
    )
}
