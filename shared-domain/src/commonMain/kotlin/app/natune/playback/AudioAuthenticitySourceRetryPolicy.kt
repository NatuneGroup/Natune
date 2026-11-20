package app.natune.playback

import app.natune.constants.AudioSourceType

object AudioAuthenticitySourceRetryPolicy {
    fun nextChain(
        configuredChain: List<AudioSourceType>,
        fallbackChain: List<AudioSourceType>,
        rejectedSources: Set<AudioSourceType>,
    ): List<AudioSourceType> {
        val configuredAvailable = configuredChain.filterNot(rejectedSources::contains)
        if (configuredAvailable.isNotEmpty() || configuredChain.isEmpty() || rejectedSources.isEmpty()) {
            return configuredAvailable
        }
        return fallbackChain.filterNot(rejectedSources::contains)
    }
}
