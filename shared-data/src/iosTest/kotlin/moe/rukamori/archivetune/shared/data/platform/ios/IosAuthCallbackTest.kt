package moe.rukamori.archivetune.shared.data.platform.ios

import kotlin.test.*
import moe.rukamori.archivetune.shared.domain.platform.*

class IosAuthCallbackTest {
    private val request = BrowserAuthRequest("provider", CredentialScope.SPOTIFY_STREAMING,
        "https://example.invalid/authorize?state=attempt-1", "archivetune:/oauth", "attempt-1")

    @Test fun exactRedirectAndSingleStateRequired() {
        IosAuthCallback.validateRequest(request)
        assertEquals("attempt-1", IosAuthCallback.result("archivetune:/oauth?state=attempt-1&code=x", request).state)
        listOf(
            "archivetune:/oauth/extra?state=attempt-1",
            "archivetune://evil/oauth?state=attempt-1",
            "archivetune:/oauth?state=attempt-2",
            "archivetune:/oauth?state=attempt-1&state=attempt-1",
        ).forEach { assertFailsWith<IllegalArgumentException> { IosAuthCallback.result(it, request) } }
    }

    @Test fun cleartextAuthorizationIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            IosAuthCallback.validateRequest(request.copy(authorizationUrl = "http://example.invalid/?state=attempt-1"))
        }
    }
}
