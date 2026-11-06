package moe.rukamori.archivetune.shared.ui.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import moe.rukamori.archivetune.shared.domain.settings.AudioQualityKey
import moe.rukamori.archivetune.shared.domain.settings.AudioSourceOrderKey
import moe.rukamori.archivetune.shared.domain.settings.AutoLoadMoreKey
import moe.rukamori.archivetune.shared.domain.settings.DeezerEnabledKey
import moe.rukamori.archivetune.shared.domain.settings.PreferenceKey
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.domain.settings.QobuzEnabledKey
import moe.rukamori.archivetune.shared.domain.settings.SpotifyStreamEnabledKey
import moe.rukamori.archivetune.shared.domain.settings.TidalEnabledKey
import moe.rukamori.archivetune.shared.domain.source.AudioSourceType

/**
 * The source-check dialog must restart only when one of its own inputs changes. Keying it on the whole preference
 * snapshot re-ran every probe after any settings write; these tests pin that unrelated keys stay out of the plan.
 */
class SourceProbePlanTest {
    private val order = "TIDAL,QOBUZ,DEEZER,YOUTUBE"

    private fun snapshot(vararg extra: Pair<PreferenceKey<*>, Any>) = PreferenceSnapshot.of(
        *(listOf<Pair<PreferenceKey<*>, Any>>(
            AudioSourceOrderKey to order,
            TidalEnabledKey to true,
            QobuzEnabledKey to true,
            DeezerEnabledKey to true,
        ) + extra).toTypedArray(),
    )

    @Test fun unrelatedSettingsWritesKeepTheProbePlanStable() {
        assertEquals(
            sourceProbePlan(snapshot(), spotifyReady = false),
            sourceProbePlan(snapshot(AutoLoadMoreKey to true, AudioQualityKey to "HIGH"), spotifyReady = false),
        )
    }

    @Test fun theProbePlanChangesWithTheOrderItWalks() {
        assertNotEquals(
            sourceProbePlan(snapshot(), spotifyReady = false),
            sourceProbePlan(snapshot(AudioSourceOrderKey to "QOBUZ,TIDAL,DEEZER,YOUTUBE"), spotifyReady = false),
        )
    }

    @Test fun theProbePlanChangesWithEveryEnabledSwitchItReads() {
        val base = sourceProbePlan(snapshot(), spotifyReady = false)
        assertNotEquals(base, sourceProbePlan(snapshot(TidalEnabledKey to false), spotifyReady = false))
        assertNotEquals(base, sourceProbePlan(snapshot(QobuzEnabledKey to false), spotifyReady = false))
        assertNotEquals(base, sourceProbePlan(snapshot(DeezerEnabledKey to false), spotifyReady = false))
    }

    @Test fun spotifyCountsAsProbeableOnlyWhenItsSwitchAndTheStreamingAccountAgree() {
        val switchedOn = snapshot(SpotifyStreamEnabledKey to true)
        assertEquals(false, sourceProbePlan(switchedOn, spotifyReady = false).enabled[AudioSourceType.SPOTIFY])
        assertEquals(true, sourceProbePlan(switchedOn, spotifyReady = true).enabled[AudioSourceType.SPOTIFY])
        assertEquals(
            false,
            sourceProbePlan(snapshot(), spotifyReady = true).enabled[AudioSourceType.SPOTIFY],
            "A signed-in account alone must not probe a source the user left off",
        )
    }

    @Test fun thePlanCarriesEveryEnabledFlagTheDialogWouldProbe() {
        val plan = sourceProbePlan(snapshot(), spotifyReady = false)
        assertEquals(AudioSourceType.entries.toSet(), plan.enabled.keys)
        assertEquals(
            listOf(AudioSourceType.TIDAL, AudioSourceType.QOBUZ, AudioSourceType.DEEZER, AudioSourceType.YOUTUBE),
            plan.order.filter { plan.enabled[it] == true },
        )
    }
}
