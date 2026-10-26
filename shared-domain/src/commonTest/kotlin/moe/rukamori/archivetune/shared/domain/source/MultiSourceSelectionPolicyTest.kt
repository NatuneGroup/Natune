/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.domain.source

import moe.rukamori.archivetune.shared.domain.source.AudioSourceType.AMAZON
import moe.rukamori.archivetune.shared.domain.source.AudioSourceType.APPLE
import moe.rukamori.archivetune.shared.domain.source.AudioSourceType.DEEZER
import moe.rukamori.archivetune.shared.domain.source.AudioSourceType.JIOSAAVN
import moe.rukamori.archivetune.shared.domain.source.AudioSourceType.QOBUZ
import moe.rukamori.archivetune.shared.domain.source.AudioSourceType.QOBUZ_BACKUP
import moe.rukamori.archivetune.shared.domain.source.AudioSourceType.QQ
import moe.rukamori.archivetune.shared.domain.source.AudioSourceType.SPOTIFY
import moe.rukamori.archivetune.shared.domain.source.AudioSourceType.TIDAL
import moe.rukamori.archivetune.shared.domain.source.AudioSourceType.YOUTUBE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Characterizes the multi-source resolution chain. Sources are tried in the user's order, the chain is cut
 * at YouTube, disabled sources drop out, and a per-song pin narrows the chain to one source. The YouTube
 * toggle is a hard stop for the terminal fallback.
 */
class MultiSourceSelectionPolicyTest {
    private fun snapshot(
        enabled: Set<AudioSourceType> = AudioSourceType.entries.toSet(),
        order: List<AudioSourceType> = AudioSourceConfig.DEFAULT_ORDER,
    ) = PlaybackSourceSnapshot(order = order, enabled = enabled)

    @Test
    fun baseChainTriesEveryEnabledSourceInPriorityOrderWithYouTubeLast() {
        // JioSaavn sits before Amazon here, as in the shipped order. Spotify streaming sits just above YouTube.
        assertEquals(
            listOf(TIDAL, QOBUZ, QOBUZ_BACKUP, DEEZER, APPLE, JIOSAAVN, AMAZON, QQ, SPOTIFY),
            PlaybackSourceChain.baseChain(snapshot()),
        )
    }

    @Test
    fun baseChainSkipsDisabledSourcesWithoutReorderingTheRest() {
        assertEquals(
            listOf(TIDAL, DEEZER),
            PlaybackSourceChain.baseChain(snapshot(enabled = setOf(TIDAL, DEEZER, YOUTUBE))),
        )
    }

    @Test
    fun youTubeIsCutFromTheChainEvenWhenTheOrderPlacesItEarly() {
        assertEquals(
            emptyList(),
            PlaybackSourceChain.baseChain(snapshot(order = listOf(YOUTUBE, TIDAL, DEEZER))),
        )
    }

    @Test
    fun onlyYouTubeEnabledLeavesAnEmptyChainAndAllowsTheCacheShortCircuit() {
        val onlyYouTube = snapshot(enabled = setOf(YOUTUBE))

        assertEquals(emptyList(), PlaybackSourceChain.baseChain(onlyYouTube))
        assertTrue(PlaybackSourceChain.cacheShortCircuitAllowed(onlyYouTube))
    }

    @Test
    fun aSourceAboveYouTubeBlocksTheYouTubeCacheShortCircuit() {
        assertFalse(PlaybackSourceChain.cacheShortCircuitAllowed(snapshot(enabled = setOf(YOUTUBE, DEEZER))))
    }

    @Test
    fun qobuzBackupFollowsQobuzAndRunsBeforeDeezer() {
        assertEquals(
            listOf(QOBUZ, QOBUZ_BACKUP, DEEZER),
            PlaybackSourceChain.baseChain(snapshot(enabled = setOf(QOBUZ, QOBUZ_BACKUP, DEEZER, YOUTUBE))),
        )
    }

    @Test
    fun aPinnedEnabledSourceRunsAlone() {
        assertEquals(listOf(DEEZER), PlaybackSourceChain.configuredChain(snapshot(), DEEZER, rejected = emptySet()))
    }

    @Test
    fun aPinForADisabledSourceYieldsToTheBaseChain() {
        assertEquals(
            listOf(TIDAL),
            PlaybackSourceChain.configuredChain(snapshot(enabled = setOf(TIDAL, YOUTUBE)), DEEZER, rejected = emptySet()),
        )
    }

    @Test
    fun withoutAPinTheBaseChainApplies() {
        assertEquals(
            PlaybackSourceChain.baseChain(snapshot()),
            PlaybackSourceChain.configuredChain(snapshot(), override = null, rejected = emptySet()),
        )
    }

    @Test
    fun aYouTubePinEmptiesTheChainSoTheTerminalFallbackServesTheSong() {
        assertEquals(emptyList(), PlaybackSourceChain.configuredChain(snapshot(), YOUTUBE, rejected = emptySet()))
    }

    @Test
    fun aYouTubePinYieldsToTheBaseChainOnceYouTubeWasRejectedForTheSong() {
        assertEquals(
            PlaybackSourceChain.baseChain(snapshot()),
            PlaybackSourceChain.configuredChain(snapshot(), YOUTUBE, rejected = setOf(YOUTUBE)),
        )
    }

    @Test
    fun cacheKeysFollowTheChainOrderAndEndWithTheBareYouTubeKey() {
        assertEquals(
            listOf("tidal:m1", "qobuz:m1", "m1"),
            PlaybackSourceChain.cacheKeyOrder(snapshot(enabled = setOf(TIDAL, QOBUZ, YOUTUBE)), "m1"),
        )
    }

    @Test
    fun cacheKeysSkipSourcesWithoutACacheKeyAndTheBareKeyWhenYouTubeIsOff() {
        assertEquals(listOf("m1"), PlaybackSourceChain.cacheKeyOrder(snapshot(enabled = setOf(APPLE, QQ, YOUTUBE)), "m1"))
        assertEquals(listOf("tidal:m1"), PlaybackSourceChain.cacheKeyOrder(snapshot(enabled = setOf(TIDAL)), "m1"))
    }

    @Test
    fun cacheProbeKeysMatchTheKeysDownloadsWrite() {
        assertEquals("tidal:x", PlaybackSourceChain.cacheProbeKey(TIDAL, "x"))
        assertEquals("qobuz:x", PlaybackSourceChain.cacheProbeKey(QOBUZ, "x"))
        assertEquals("qobuz_backup:x", PlaybackSourceChain.cacheProbeKey(QOBUZ_BACKUP, "x"))
        assertEquals("deezer:x", PlaybackSourceChain.cacheProbeKey(DEEZER, "x"))
        assertEquals("jiosaavn:x", PlaybackSourceChain.cacheProbeKey(JIOSAAVN, "x"))
        assertEquals("x", PlaybackSourceChain.cacheProbeKey(YOUTUBE, "x"))
        for (source in listOf(APPLE, AMAZON, QQ, SPOTIFY)) {
            assertNull(PlaybackSourceChain.cacheProbeKey(source, "x"), source.name)
        }
    }

    @Test
    fun spotifyCountsAsEnabledOnlyWithItsSwitchAndASignedInStreamingAccount() {
        val switchedOn = mapOf(TIDAL to true, SPOTIFY to true)

        assertEquals(setOf(TIDAL), PlaybackSourceChain.enabledSet(switchedOn, spotifySignedIn = false))
        assertEquals(setOf(TIDAL, SPOTIFY), PlaybackSourceChain.enabledSet(switchedOn, spotifySignedIn = true))
        assertEquals(
            setOf(TIDAL),
            PlaybackSourceChain.enabledSet(mapOf(TIDAL to true, SPOTIFY to false), spotifySignedIn = true),
        )
    }

    @Test
    fun enabledSetKeepsOnlySwitchedOnSources() {
        assertEquals(
            setOf(YOUTUBE),
            PlaybackSourceChain.enabledSet(mapOf(QOBUZ to false, YOUTUBE to true), spotifySignedIn = false),
        )
    }

    @Test
    fun youTubeIsRefusedAsTheTerminalFallbackWhenItsToggleIsOff() {
        assertFalse(PlaybackSourceChain.youTubeTerminalAllowed(snapshot(enabled = setOf(TIDAL)), rejected = emptySet()))
        assertTrue(
            PlaybackSourceChain.youTubeTerminalAllowed(snapshot(enabled = setOf(TIDAL, YOUTUBE)), rejected = emptySet()),
        )
    }

    @Test
    fun youTubeIsRefusedForASongWhoseAuthenticityChecksRejectedIt() {
        assertFalse(PlaybackSourceChain.youTubeTerminalAllowed(snapshot(), rejected = setOf(YOUTUBE)))
    }

    @Test
    fun enabledPlaybackSourcesKeepTheGivenOrderWithoutRepeats() {
        assertEquals(
            listOf(TIDAL, YOUTUBE),
            PlaybackSourceSelection.enabledPlaybackSources(listOf(DEEZER, TIDAL, DEEZER, YOUTUBE), setOf(TIDAL, YOUTUBE)),
        )
    }

    @Test
    fun aCachedSourceIsUsableOnlyWhileEnabledAndNotRejectedForTheSong() {
        assertTrue(PlaybackSourceSelection.canUseCachedPlaybackSource(TIDAL, enabled = true, rejectedSources = emptySet()))
        assertFalse(PlaybackSourceSelection.canUseCachedPlaybackSource(TIDAL, enabled = false, rejectedSources = emptySet()))
        assertFalse(PlaybackSourceSelection.canUseCachedPlaybackSource(TIDAL, enabled = true, rejectedSources = setOf(TIDAL)))
    }
}
