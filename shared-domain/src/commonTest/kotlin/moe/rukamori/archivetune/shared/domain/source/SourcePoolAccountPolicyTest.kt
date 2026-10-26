package moe.rukamori.archivetune.shared.domain.source

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Characterizes Playback Pool isolation. Shared pool accounts are kept apart from the user's own sign-ins, so
 * switching the pool off never removes personal access, and a pool refresh never overwrites it.
 */
class SourcePoolAccountPolicyTest {
    private data class Credential(val id: Long, val isPool: Boolean, val premium: Boolean = false)

    @Test
    fun thePoolRunsOnlyWhenTheUserAllowsItAndAProviderIsConfigured() {
        assertTrue(PoolAccountPolicy.isPoolEnabled(true, "https://pool.example"))
        assertFalse(PoolAccountPolicy.isPoolEnabled(false, "https://pool.example"))
        assertFalse(PoolAccountPolicy.isPoolEnabled(true, null))
        assertFalse(PoolAccountPolicy.isPoolEnabled(true, "   "))
    }

    @Test
    fun aDisabledPoolHidesEveryPoolAccount() {
        val accounts = listOf("pool-1", "pool-2")

        assertEquals(emptyList(), PoolAccountPolicy.visibleAccounts(poolEnabled = false, accounts = accounts))
        assertEquals(accounts, PoolAccountPolicy.visibleAccounts(poolEnabled = true, accounts = accounts))
    }

    @Test
    fun personalCredentialsStayUsableWhenThePoolIsSwitchedOff() {
        val personal = Credential(1L, isPool = false)
        val pool = Credential(2L, isPool = true)

        assertEquals(
            listOf(personal),
            PoolAccountPolicy.credentialsInUse(poolEnabled = false, credentials = listOf(personal, pool)) { it.isPool },
        )
        assertEquals(
            listOf(personal, pool),
            PoolAccountPolicy.credentialsInUse(poolEnabled = true, credentials = listOf(personal, pool)) { it.isPool },
        )
    }

    @Test
    fun aPoolRefreshReplacesThePoolCredentialsAndNeverTouchesPersonalOnes() {
        val personal = Credential(1L, isPool = false)
        val oldPool = Credential(2L, isPool = true)
        val freshPool = Credential(3L, isPool = true)

        assertEquals(
            listOf(personal, freshPool),
            PoolAccountPolicy.refreshedCredentials(listOf(personal, oldPool), listOf(freshPool)) { it.isPool },
        )
    }

    @Test
    fun aFailedPoolAccountIsDemotedForTheCooldownThenTriedAgain() {
        var now = 1_000_000L
        val cooldowns = PoolAccountCooldowns { now }

        cooldowns.noteFailure("qobuz", 7L)
        assertTrue(cooldowns.isCoolingDown("qobuz", 7L))

        now += PoolAccountPolicy.ACCOUNT_COOLDOWN_MS - 1
        assertTrue(cooldowns.isCoolingDown("qobuz", 7L))

        now += 1
        assertFalse(cooldowns.isCoolingDown("qobuz", 7L))
    }

    @Test
    fun aSuccessfulResolveClearsACooldownAndCooldownsAreScopedPerService() {
        val cooldowns = PoolAccountCooldowns { 0L }
        cooldowns.noteFailure("qobuz", 7L)
        cooldowns.noteFailure("tidal", 7L)

        cooldowns.noteSuccess("qobuz", 7L)

        assertFalse(cooldowns.isCoolingDown("qobuz", 7L))
        assertTrue(cooldowns.isCoolingDown("tidal", 7L))
    }

    @Test
    fun accountsWithoutAUsableIdAreNeverRemembered() {
        val cooldowns = PoolAccountCooldowns { 0L }

        cooldowns.noteFailure("qobuz", null)
        cooldowns.noteFailure("qobuz", 0L)

        assertFalse(cooldowns.isCoolingDown("qobuz", null))
        assertFalse(cooldowns.isCoolingDown("qobuz", 0L))
    }

    @Test
    fun healthyPremiumAccountsGoFirstThenFreeThenCoolingAccountsLast() {
        val cooldowns = PoolAccountCooldowns { 0L }
        cooldowns.noteFailure("qobuz", 2L)
        val accounts =
            listOf(
                Credential(1L, isPool = true, premium = false),
                Credential(2L, isPool = true, premium = true),
                Credential(3L, isPool = true, premium = true),
                Credential(4L, isPool = true, premium = false),
            )

        val ordered = cooldowns.ordered("qobuz", accounts, idOf = { it.id }, premiumOf = { it.premium })

        assertEquals(listOf(3L, 1L, 4L, 2L), ordered.map { it.id })
    }

    @Test
    fun aThinPoolWhereEveryAccountIsCoolingStillOffersEveryAccount() {
        val cooldowns = PoolAccountCooldowns { 0L }
        cooldowns.noteFailure("qobuz", 1L)
        cooldowns.noteFailure("qobuz", 2L)
        val accounts = listOf(Credential(1L, isPool = true), Credential(2L, isPool = true))

        val ordered = cooldowns.ordered("qobuz", accounts, idOf = { it.id }, premiumOf = { it.premium })

        assertEquals(listOf(1L, 2L), ordered.map { it.id })
    }

    @Test
    fun appleOrderingSortsPremiumFirstAndIgnoresCooldowns() {
        val cooldowns = PoolAccountCooldowns { 0L }
        cooldowns.noteFailure("apple", 2L)
        val accounts =
            listOf(
                Credential(1L, isPool = true, premium = false),
                Credential(2L, isPool = true, premium = true),
                Credential(3L, isPool = true, premium = false),
                Credential(4L, isPool = true, premium = true),
            )

        assertEquals(listOf(2L, 4L, 1L, 3L), cooldowns.orderedByPremium(accounts) { it.premium }.map { it.id })
    }
}
