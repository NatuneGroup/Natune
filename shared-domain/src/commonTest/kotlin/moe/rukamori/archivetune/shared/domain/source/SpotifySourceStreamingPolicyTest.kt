package moe.rukamori.archivetune.shared.domain.source

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Characterizes the Spotify catalog and streaming separation. The catalog and streaming accounts are two
 * accounts, so streaming stands down when both are the same person, and sign-in refuses the same account.
 */
class SpotifySourceStreamingPolicyTest {
    @Test
    fun anIdenticalStreamingCookieStandsDownWithoutReadingTheCatalogAccount() {
        assertTrue(
            SpotifyStreamingPolicy.streamingStandsDown(
                catalogSpDc = "cookie-a",
                streamingSpDc = "cookie-a",
                streamingUserId = "user-1",
                catalogUserId = { fail("the catalog account must not be read for an identical cookie") },
            ),
        )
    }

    @Test
    fun aBlankCatalogNeverStandsStreamingDown() {
        assertFalse(
            SpotifyStreamingPolicy.streamingStandsDown(
                catalogSpDc = "",
                streamingSpDc = "cookie-b",
                streamingUserId = "user-1",
                catalogUserId = { fail("no catalog sign-in means there is nothing to compare") },
            ),
        )
    }

    @Test
    fun matchingUserIdsStandStreamingDownEvenWithDifferentCookies() {
        assertTrue(
            SpotifyStreamingPolicy.streamingStandsDown(
                catalogSpDc = "cookie-a",
                streamingSpDc = "cookie-b",
                streamingUserId = "user-1",
                catalogUserId = { "user-1" },
            ),
        )
    }

    @Test
    fun differentAccountsKeepStreamingRunning() {
        assertFalse(
            SpotifyStreamingPolicy.streamingStandsDown(
                catalogSpDc = "cookie-a",
                streamingSpDc = "cookie-b",
                streamingUserId = "user-1",
                catalogUserId = { "user-2" },
            ),
        )
    }

    @Test
    fun anUnknownCatalogAccountFailsOpenSoStreamingKeepsWorking() {
        assertFalse(
            SpotifyStreamingPolicy.streamingStandsDown(
                catalogSpDc = "cookie-a",
                streamingSpDc = "cookie-b",
                streamingUserId = "user-1",
                catalogUserId = { null },
            ),
        )
    }

    @Test
    fun aBlankStreamingUserIdIsNeverComparedSoStreamingKeepsRunning() {
        assertFalse(
            SpotifyStreamingPolicy.streamingStandsDown(
                catalogSpDc = "cookie-a",
                streamingSpDc = "cookie-b",
                streamingUserId = "",
                catalogUserId = { fail("a blank streaming id is never compared") },
            ),
        )
    }

    @Test
    fun theStreamingTokenIsHandedOutOnlyForASignedInAccountThatIsNotStandingDown() {
        assertTrue(SpotifyStreamingPolicy.accessTokenAllowed("cookie-b", standsDown = false))
        assertFalse(SpotifyStreamingPolicy.accessTokenAllowed("", standsDown = false))
        assertFalse(SpotifyStreamingPolicy.accessTokenAllowed("cookie-b", standsDown = true))
    }

    @Test
    fun anIdenticalCookieIsRefusedBeforeAnyOtherCheck() {
        assertEquals(
            SpotifySignInDecision.Refuse(SpotifySignInRefusal.SAME_AS_CATALOG),
            SpotifyStreamingPolicy.decideSignIn(
                catalogSpDc = "cookie-a",
                candidateSpDc = "cookie-a",
                tokenMinted = false,
                streamingUserId = null,
                catalogUserId = { fail("the catalog must not be read for an identical cookie") },
            ),
        )
    }

    @Test
    fun aCookieThatMintsNoSignedInSessionIsRefused() {
        assertEquals(
            SpotifySignInDecision.Refuse(SpotifySignInRefusal.INVALID_SESSION),
            SpotifyStreamingPolicy.decideSignIn(
                catalogSpDc = "cookie-a",
                candidateSpDc = "cookie-b",
                tokenMinted = false,
                streamingUserId = "user-2",
                catalogUserId = { fail("an invalid session is refused before the catalog is read") },
            ),
        )
    }

    @Test
    fun anUnreadableStreamingProfileIsRefusedSoSeparationCannotBeChecked() {
        assertEquals(
            SpotifySignInDecision.Refuse(SpotifySignInRefusal.PROFILE_UNAVAILABLE),
            SpotifyStreamingPolicy.decideSignIn(
                catalogSpDc = "cookie-a",
                candidateSpDc = "cookie-b",
                tokenMinted = true,
                streamingUserId = null,
                catalogUserId = { fail("an unreadable profile is refused before the catalog is read") },
            ),
        )
    }

    @Test
    fun aSignedInCatalogWhoseAccountCannotBeReadRefusesTheSignIn() {
        assertEquals(
            SpotifySignInDecision.Refuse(SpotifySignInRefusal.CATALOG_UNVERIFIABLE),
            SpotifyStreamingPolicy.decideSignIn(
                catalogSpDc = "cookie-a",
                candidateSpDc = "cookie-b",
                tokenMinted = true,
                streamingUserId = "user-2",
                catalogUserId = { null },
            ),
        )
    }

    @Test
    fun aStreamingAccountThatIsTheCatalogAccountIsRefused() {
        assertEquals(
            SpotifySignInDecision.Refuse(SpotifySignInRefusal.SAME_AS_CATALOG),
            SpotifyStreamingPolicy.decideSignIn(
                catalogSpDc = "cookie-a",
                candidateSpDc = "cookie-b",
                tokenMinted = true,
                streamingUserId = "user-1",
                catalogUserId = { "user-1" },
            ),
        )
    }

    @Test
    fun aSeparateStreamingAccountIsAccepted() {
        assertEquals(
            SpotifySignInDecision.Accept,
            SpotifyStreamingPolicy.decideSignIn(
                catalogSpDc = "cookie-a",
                candidateSpDc = "cookie-b",
                tokenMinted = true,
                streamingUserId = "user-2",
                catalogUserId = { "user-1" },
            ),
        )
    }

    @Test
    fun withNoCatalogSignInTheCatalogAccountIsNeverReadAndStreamingIsAccepted() {
        assertEquals(
            SpotifySignInDecision.Accept,
            SpotifyStreamingPolicy.decideSignIn(
                catalogSpDc = "",
                candidateSpDc = "cookie-b",
                tokenMinted = true,
                streamingUserId = "user-2",
                catalogUserId = { fail("no catalog sign-in means nothing to read") },
            ),
        )
    }
}
