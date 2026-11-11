package moe.rukamori.archivetune.shared.domain.source

import moe.rukamori.archivetune.shared.domain.provider.CredentialLane
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SourceAttemptPlanTest {
    private fun plan(
        order: List<AudioSourceType> = AudioSourceConfig.DEFAULT_ORDER,
        enabled: Set<AudioSourceType> = AudioSourceType.entries.toSet(),
        pin: AudioSourceType? = null,
        rejected: Set<AudioSourceType> = emptySet(),
    ) = DefaultSourceAttemptPlan.plan(SourceAttemptPlanRequest(PlaybackSourceSnapshot(order, enabled), pin, rejected))

    @Test fun shippedNativeAndModulesOrderUsesExistingProviderRouting() {
        val actual = plan()
        assertEquals(listOf("TIDAL", "QOBUZ", "QOBUZ_BACKUP", "DEEZER", "APPLE", "JIOSAAVN",
            "AMAZON", "QQ", "MODULES", "SPOTIFY"), actual.attempts.map { it.sourceId.name })
        assertTrue(actual.attempts.all { it.profileId == null && it.credentialLane == CredentialLane.DEFAULT })
        assertTrue(actual.youTubeTerminalAllowed)
    }

    @Test fun freshDefaultFlagsDoNotEnableModulesOrSpotify() {
        val enabled = PlaybackSourceChain.enabledSet(AudioSourceConfig.DEFAULT_ENABLED, spotifySignedIn = false)
        assertEquals(listOf(AudioSourceType.TIDAL), plan(enabled = enabled).attempts.map { it.sourceId })
    }

    @Test fun customModulesBlockKeepsItsPositionAndDisabledSourcesDropOut() {
        val order = AudioSourceConfig.parseOrder("SPOTIFY,MODULES,TIDAL,YOUTUBE")
        val actual = plan(order, setOf(AudioSourceType.SPOTIFY, AudioSourceType.MODULES, AudioSourceType.TIDAL, AudioSourceType.YOUTUBE))
        assertEquals(listOf(AudioSourceType.SPOTIFY, AudioSourceType.MODULES, AudioSourceType.TIDAL), actual.attempts.map { it.sourceId })
        assertEquals(listOf(AudioSourceType.SPOTIFY, AudioSourceType.TIDAL), plan(order,
            setOf(AudioSourceType.SPOTIFY, AudioSourceType.TIDAL, AudioSourceType.YOUTUBE)).attempts.map { it.sourceId })
    }

    @Test fun earlyYouTubeCutsEvenDisabledTerminalAndNeverRunsLaterModules() {
        val order = listOf(AudioSourceType.TIDAL, AudioSourceType.YOUTUBE, AudioSourceType.MODULES, AudioSourceType.SPOTIFY)
        assertEquals(listOf(AudioSourceType.TIDAL), plan(order).attempts.map { it.sourceId })
        val disabled = plan(order, setOf(AudioSourceType.TIDAL, AudioSourceType.MODULES))
        assertEquals(listOf(AudioSourceType.TIDAL), disabled.attempts.map { it.sourceId })
        assertFalse(disabled.youTubeTerminalAllowed)
        assertFalse(plan(order, rejected = setOf(AudioSourceType.YOUTUBE)).youTubeTerminalAllowed)
    }

    @Test fun nativeAndModulesPinsRetainTheirSingleSourceMeaning() {
        for (pin in listOf(AudioSourceType.TIDAL, AudioSourceType.MODULES, AudioSourceType.SPOTIFY)) {
            assertEquals(listOf(pin), plan(pin = pin).attempts.map { it.sourceId })
        }
        val disabledPin = plan(enabled = setOf(AudioSourceType.TIDAL, AudioSourceType.YOUTUBE), pin = AudioSourceType.MODULES)
        assertEquals(listOf(AudioSourceType.TIDAL), disabledPin.attempts.map { it.sourceId })
        // An enabled pin can deliberately address a source below the terminal position.
        assertEquals(listOf(AudioSourceType.MODULES), plan(order = listOf(AudioSourceType.YOUTUBE, AudioSourceType.MODULES),
            pin = AudioSourceType.MODULES).attempts.map { it.sourceId })
    }

    @Test fun rejectedPinsYieldToPermittedOrderAndYouTubePinUsesOnlyTerminal() {
        val enabled = setOf(AudioSourceType.TIDAL, AudioSourceType.MODULES, AudioSourceType.YOUTUBE)
        assertEquals(emptyList(), plan(enabled = enabled, pin = AudioSourceType.YOUTUBE).attempts)
        val refused = plan(enabled = enabled, pin = AudioSourceType.YOUTUBE, rejected = setOf(AudioSourceType.YOUTUBE))
        assertEquals(listOf(AudioSourceType.TIDAL, AudioSourceType.MODULES), refused.attempts.map { it.sourceId })
        assertFalse(refused.youTubeTerminalAllowed)
        assertEquals(listOf(AudioSourceType.TIDAL), plan(enabled = enabled, pin = AudioSourceType.MODULES,
            rejected = setOf(AudioSourceType.MODULES)).attempts.map { it.sourceId })
    }
}
