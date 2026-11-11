package moe.rukamori.archivetune.shared.domain.source

import moe.rukamori.archivetune.shared.domain.provider.CredentialLane

/** A null profile and DEFAULT lane preserve the existing provider's internal account routing. */
data class SourceAttempt(
    val sourceId: AudioSourceType,
    val profileId: String? = null,
    val credentialLane: CredentialLane = CredentialLane.DEFAULT,
) {
    init { require(profileId == null || profileId.isNotBlank()) }
}

data class SourceAttemptPlan(
    val attempts: List<SourceAttempt>,
    val youTubeTerminalAllowed: Boolean,
) {
    init { require(attempts.none { it.sourceId == AudioSourceType.YOUTUBE }) }
}

data class SourceAttemptPlanRequest(
    val sources: PlaybackSourceSnapshot,
    val pinned: AudioSourceType? = null,
    val rejected: Set<AudioSourceType> = emptySet(),
)

interface SourceAttemptPlanPort {
    fun plan(request: SourceAttemptPlanRequest): SourceAttemptPlan
}

/**
 * Adapts the shipped chain without changing its order, source gates, pins or terminal owner.
 * MODULES remains one legacy attempt at its original position until profile migration/cutover.
 * It is deliberately absent from CompiledProviderId and cannot configure a new provider profile.
 */
object DefaultSourceAttemptPlan : SourceAttemptPlanPort {
    override fun plan(request: SourceAttemptPlanRequest): SourceAttemptPlan {
        val chain = PlaybackSourceChain.configuredChain(
            request.sources, request.pinned?.takeUnless { it in request.rejected }, request.rejected,
        ).distinct().filter { it in request.sources.enabled && it !in request.rejected }
        return SourceAttemptPlan(
            attempts = chain.map { SourceAttempt(it) },
            youTubeTerminalAllowed = PlaybackSourceChain.youTubeTerminalAllowed(request.sources, request.rejected),
        )
    }
}
