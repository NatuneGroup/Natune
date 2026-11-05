package moe.rukamori.archivetune.shared.domain.recommend

import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence

/**
 * Stable ordering for equal scores. `String.hashCode` and `Random.Default` are not contractual across processes or
 * platforms, so a recommendation policy that must be reproducible for a given seed uses this FNV-1a mix instead.
 */
internal object RecommendationOrdering {
    private const val FNV_OFFSET = -3750763034362895579L
    private const val FNV_PRIME = 1_099_511_628_211L

    fun mix(seed: Long, vararg parts: String): Long {
        var hash = FNV_OFFSET xor seed
        for (part in parts) {
            for (character in part) {
                hash = (hash xor character.code.toLong()) * FNV_PRIME
            }
            // The separator keeps ("ab", "c") and ("a", "bc") from colliding.
            hash = (hash xor 0x1fL) * FNV_PRIME
        }
        return hash
    }

    fun tieBreak(seed: Long, occurrence: ContentOccurrence): Long {
        val ref = occurrence.item.ref
        return mix(seed, ref.provider, ref.kind.name, ref.id, occurrence.occurrenceId)
    }
}
