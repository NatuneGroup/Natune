/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.data.provider

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import moe.rukamori.archivetune.shared.domain.repository.*
import moe.rukamori.archivetune.shared.domain.source.*

data class ProviderAttempt(val repository: AudioProviderRepository, val pooled: Boolean)

/**
 * One registered source can walk real personal/Pool/instance repos in the HOST's existing order.
 * No account feed, token refresh, cache, cooldown owner or alternate credential store is introduced.
 * The snapshot supplier must reuse the existing account owner's ordering/cooldown/premium policy.
 */
class SourceAttemptSequence(
    override val source: AudioSourceType,
    private val candidates: () -> List<ProviderAttempt>,
    private val poolEnabled: () -> Boolean,
) : AudioProviderRepository {
    override suspend fun resolve(request: AudioResolveRequest): PreparedAudio? = withTimeoutOrNull(30_000) {
        val snapshot = PoolAccountPolicy.credentialsInUse(poolEnabled(), candidates().toList()) { it.pooled }
        require(snapshot.all { it.repository.source == source })
        for (candidate in snapshot) {
            if (candidate.pooled && !poolEnabled()) continue
            val audio = try { candidate.repository.resolve(request) }
                catch (auth: TerminalPlaybackAuthentication) { throw auth }
                catch (cancel: CancellationException) { throw cancel }
                catch (failure: Exception) { null }
            if (candidate.pooled && !poolEnabled()) continue
            if (audio != null && audio.source == source && audio.matchesRequest(request)) return@withTimeoutOrNull audio
        }
        null
    }
}
