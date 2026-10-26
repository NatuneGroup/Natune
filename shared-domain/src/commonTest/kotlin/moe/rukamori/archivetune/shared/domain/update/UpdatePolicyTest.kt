package moe.rukamori.archivetune.shared.domain.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import moe.rukamori.archivetune.shared.domain.settings.UpdateChannel
import moe.rukamori.archivetune.shared.domain.settings.UpdateEligibility
import moe.rukamori.archivetune.shared.domain.settings.archiveMatchesOffer
import moe.rukamori.archivetune.shared.domain.settings.defaultUpdateChannel
import moe.rukamori.archivetune.shared.domain.settings.eligibleUpdateOffer
import moe.rukamori.archivetune.shared.domain.settings.updateEligibility
import moe.rukamori.archivetune.shared.domain.settings.updateRequestIsCurrent

class UpdatePolicyTest {

    @Test
    fun storedChannelAliasesLandOnNightly() {
        assertEquals(UpdateChannel.STABLE, UpdateChannel.fromStoredName("STABLE", UpdateChannel.STABLE))
        assertEquals(UpdateChannel.NIGHTLY, UpdateChannel.fromStoredName("NIGHTLY", UpdateChannel.STABLE))
        assertEquals(UpdateChannel.NIGHTLY, UpdateChannel.fromStoredName("CANARY", UpdateChannel.STABLE))
        assertEquals(UpdateChannel.NIGHTLY, UpdateChannel.fromStoredName("DAILY_NIGHTLY", UpdateChannel.STABLE))
        assertEquals(UpdateChannel.STABLE, UpdateChannel.fromStoredName("bogus", UpdateChannel.STABLE))
        assertEquals(UpdateChannel.NIGHTLY, UpdateChannel.fromStoredName(null, UpdateChannel.NIGHTLY))
    }

    @Test
    fun defaultChannelFollowsBuildIdentityOnlyWhenUnset() {
        assertEquals(UpdateChannel.NIGHTLY, defaultUpdateChannel(isNightlyBuild = true))
        assertEquals(UpdateChannel.STABLE, defaultUpdateChannel(isNightlyBuild = false))
    }

    @Test
    fun eligibilityNeverOffersADowngrade() {
        assertEquals(UpdateEligibility.NEWER, updateEligibility(candidateCode = 11, installedCode = 10))
        assertEquals(UpdateEligibility.NOT_NEWER, updateEligibility(candidateCode = 10, installedCode = 10))
        assertEquals(UpdateEligibility.NOT_NEWER, updateEligibility(candidateCode = 9, installedCode = 10))
        assertEquals(UpdateEligibility.UNKNOWN_BUILD_CODE, updateEligibility(candidateCode = null, installedCode = 10))
        assertEquals(UpdateEligibility.UNKNOWN_BUILD_CODE, updateEligibility(candidateCode = 0, installedCode = 10))
    }

    /**
     * The FLOOR+epoch-minutes edge: two builds in the same minute share a code. Eligibility is strict `>`, so the
     * second build is NOT offered to a client already on the first — the tie-break is "same code means same offer",
     * and the feed ordering (newer tag/publishedAt first) decides which candidate a fresh client sees.
     */
    @Test
    fun sameMinuteBuildsShareTheOfferDecision() {
        val offer = eligibleUpdateOffer(
            UpdateChannel.NIGHTLY, "N202610270002", 42, "N202610270002", null, "https://x/a.apk", installedCode = 42,
        )
        assertNull(offer) // equal code → never re-offered, whatever the tag says
        val newer = eligibleUpdateOffer(
            UpdateChannel.NIGHTLY, "N202610270003", 43, "N202610270003", null, "https://x/a.apk", installedCode = 42,
        )
        assertEquals("NIGHTLY|N202610270003|43", newer?.identity)
    }

    @Test
    fun requestStaysCurrentOnlyForSameChannelAndRevision() {
        assertTrue(updateRequestIsCurrent(UpdateChannel.STABLE, 3, UpdateChannel.STABLE, 3))
        assertFalse(updateRequestIsCurrent(UpdateChannel.STABLE, 3, UpdateChannel.NIGHTLY, 3))
        assertFalse(updateRequestIsCurrent(UpdateChannel.STABLE, 3, UpdateChannel.STABLE, 4))
    }

    @Test
    fun stagedArchiveMatchesOnlySameCodeNewerThanInstalledAndSameSource() {
        assertTrue(archiveMatchesOffer(candidateCode = 12, archiveCode = 12L, installedCode = 10, sourceMatches = true))
        assertFalse(archiveMatchesOffer(candidateCode = 12, archiveCode = 12L, installedCode = 10, sourceMatches = false))
        assertFalse(archiveMatchesOffer(candidateCode = 11, archiveCode = 12L, installedCode = 10, sourceMatches = true))
        assertFalse(archiveMatchesOffer(candidateCode = 12, archiveCode = 9L, installedCode = 10, sourceMatches = true))
        assertFalse(archiveMatchesOffer(candidateCode = 12, archiveCode = null, installedCode = 10, sourceMatches = true))
    }

    @Test
    fun canaryMigrationIsOneShot() {
        val migration = canaryChannelMigration("CANARY", setOf("canary_releases_json", "unrelated"))
        assertTrue(migration.needed)
        assertTrue(migration.channelToNightly)
        assertEquals(setOf("canary_releases_json"), migration.dropKeyNames)
        assertFalse(canaryChannelMigration("NIGHTLY", emptySet()).needed)
        // Legacy keys alone still drop even without a CANARY selection.
        assertTrue(canaryChannelMigration("STABLE", setOf("canaryChannelUnlocked")).needed)
    }

    @Test
    fun rateLimitBackoffFollowsResetOrDefault() {
        val now = 1_000_000L
        assertEquals(300_000L, GitHubRateLimit.backoffUntil(403, "0", "300", now))
        assertEquals(now + GitHubRateLimit.DEFAULT_BACKOFF_MS, GitHubRateLimit.backoffUntil(403, "0", null, now))
        assertEquals(0L, GitHubRateLimit.backoffUntil(403, "10", "300", now))
        assertEquals(0L, GitHubRateLimit.backoffUntil(200, "0", "300", now))
        assertTrue(GitHubRateLimit.isBlocked(untilMs = now + 1, nowMs = now))
        assertFalse(GitHubRateLimit.isBlocked(untilMs = now, nowMs = now))
    }

    @Test
    fun checkDueIsChannelScoped() {
        val now = STABLE_UPDATE_CHECK_INTERVAL_MS + 10
        assertTrue(updateCheckDue(0L, "NIGHTLY", UpdateChannel.STABLE, now))
        // exactly one interval elapsed is due; one ms less is not
        assertFalse(updateCheckDue(11L, "STABLE", UpdateChannel.STABLE, now))
        assertTrue(updateCheckDue(10L, "STABLE", UpdateChannel.STABLE, now))
        assertTrue(updateCheckDue(10L, "NIGHTLY", UpdateChannel.NIGHTLY, now + NIGHTLY_UPDATE_CHECK_INTERVAL_MS))
    }

    @Test
    fun popupShowsOncePerOfferUnlessPersistent() {
        val offer = "STABLE|v2|2"
        assertTrue(updatePopupShouldShow(offer, persistentRemind = true, lastShownIdentity = "old", neverShowIdentity = null))
        assertTrue(updatePopupShouldShow(offer, persistentRemind = false, lastShownIdentity = "old", neverShowIdentity = null))
        assertFalse(updatePopupShouldShow(offer, persistentRemind = false, lastShownIdentity = offer, neverShowIdentity = null))
        assertTrue(updatePopupShouldShow(offer, persistentRemind = true, lastShownIdentity = offer, neverShowIdentity = null))
        assertFalse(updatePopupShouldShow(offer, persistentRemind = true, lastShownIdentity = "old", neverShowIdentity = offer))
        assertFalse(updatePopupShouldShow(null, persistentRemind = true, lastShownIdentity = null, neverShowIdentity = null))
    }
}
