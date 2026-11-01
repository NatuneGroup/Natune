package moe.rukamori.archivetune.shared.domain.source

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the Librespot PKCE OAuth protocol and the sign-in decision: the authorize URL and token
 * bodies are byte-exact, the S256 challenge matches the RFC 7636 worked example, and the accept
 * rule refuses anything but the catalog's own Premium account.
 */
class SpotifyLibrespotOAuthTest {
    @Test
    fun thePkceChallengeMatchesTheRfc7636WorkedExample() {
        // RFC 7636 appendix B: this verifier must produce this challenge.
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            spotifyPkceChallenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
    }

    @Test
    fun theAuthorizeUrlCarriesPkceAndTheMinimalScopes() {
        val url = spotifyOAuthAuthorizeUrl(
            clientId = "client-1",
            redirectUri = "natune://spotify-callback",
            state = "s1",
            challenge = "c1",
        )
        assertTrue(url.startsWith("$SPOTIFY_OAUTH_AUTHORIZE_ENDPOINT?"))
        assertTrue("client_id=client-1" in url)
        assertTrue("response_type=code" in url)
        assertTrue("redirect_uri=natune%3A%2F%2Fspotify-callback" in url)
        assertTrue("scope=streaming%20user-read-private" in url)
        assertTrue("state=s1" in url)
        assertTrue("code_challenge=c1&code_challenge_method=S256" in url)
        // No secret is ever sent: the public client id is the only credential the URL carries.
        assertTrue("client_secret" !in url)
    }

    @Test
    fun theTokenBodiesAreFormEncodedPublicClientRequests() {
        assertEquals(
            "client_id=client-1&grant_type=authorization_code" +
                "&code=abc&redirect_uri=natune%3A%2F%2Fspotify-callback&code_verifier=ver",
            spotifyTokenExchangeBody("client-1", "natune://spotify-callback", "abc", "ver"),
        )
        assertEquals(
            "client_id=client-1&grant_type=refresh_token&refresh_token=rt1",
            spotifyTokenRefreshBody("client-1", "rt1"),
        )
    }

    @Test
    fun tokenGrantParsingReadsTheRotatingRefreshTokenAndRejectsMissingAccess() {
        val grant = parseSpotifyOAuthTokenGrant(
            """{"access_token":"a1","refresh_token":"r1","expires_in":3600}""",
        )
        assertEquals("a1", grant?.accessToken)
        assertEquals("r1", grant?.refreshToken)
        assertEquals(3600L, grant?.expiresInSeconds)

        // A refresh response that rotates nothing still carries the new access token.
        val rotated = parseSpotifyOAuthTokenGrant("""{"access_token":"a2","expires_in":3600}""")
        assertEquals("a2", rotated?.accessToken)
        assertNull(rotated?.refreshToken)

        assertNull(parseSpotifyOAuthTokenGrant("""{"refresh_token":"r1"}"""))
        assertNull(parseSpotifyOAuthTokenGrant("not json"))
    }

    @Test
    fun meParsingReadsTheAccountFacts() {
        val facts = parseSpotifyOAuthMe(
            """{"id":"uid1","display_name":"Main","product":"premium","email":"e@x"}""",
        )
        assertEquals("uid1", facts?.userId)
        assertEquals("Main", facts?.displayName)
        assertEquals("premium", facts?.product)

        assertNull(parseSpotifyOAuthMe("""{"display_name":"No id"}"""))
        assertNull(parseSpotifyOAuthMe("not json"))
    }

    @Test
    fun librespotSignInRequiresTheCatalogAccountOnPremium() {
        val accept = SpotifyStreamingPolicy.decideLibrespotSignIn(
            catalogSignedIn = true,
            librespotUserId = "uid1",
            product = "premium",
            catalogUserId = { "uid1" },
        )
        assertIs<SpotifySignInDecision.Accept>(accept)

        val different = SpotifyStreamingPolicy.decideLibrespotSignIn(
            catalogSignedIn = true,
            librespotUserId = "uid2",
            product = "premium",
            catalogUserId = { "uid1" },
        )
        assertEquals(
            SpotifySignInDecision.Refuse(SpotifySignInRefusal.DIFFERENT_FROM_CATALOG),
            different,
        )

        val free = SpotifyStreamingPolicy.decideLibrespotSignIn(
            catalogSignedIn = true,
            librespotUserId = "uid1",
            product = "free",
            catalogUserId = { "uid1" },
        )
        assertEquals(SpotifySignInDecision.Refuse(SpotifySignInRefusal.NOT_PREMIUM), free)
    }

    @Test
    fun librespotSignInRefusesUnreadableAndMissingAccounts() {
        assertEquals(
            SpotifySignInDecision.Refuse(SpotifySignInRefusal.PROFILE_UNAVAILABLE),
            SpotifyStreamingPolicy.decideLibrespotSignIn(
                catalogSignedIn = true,
                librespotUserId = null,
                product = "premium",
                catalogUserId = { "uid1" },
            ),
        )
        assertEquals(
            SpotifySignInDecision.Refuse(SpotifySignInRefusal.CATALOG_UNVERIFIABLE),
            SpotifyStreamingPolicy.decideLibrespotSignIn(
                catalogSignedIn = true,
                librespotUserId = "uid2",
                product = "premium",
                catalogUserId = { null },
            ),
        )
        // A signed-out catalog cannot collide, so the grant alone defines the library account.
        assertIs<SpotifySignInDecision.Accept>(
            SpotifyStreamingPolicy.decideLibrespotSignIn(
                catalogSignedIn = false,
                librespotUserId = "uid1",
                product = "premium",
                catalogUserId = { error("not called") },
            ),
        )
    }
}
