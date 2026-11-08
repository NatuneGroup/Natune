package moe.rukamori.archivetune.desktop.integration.auth

import java.net.URLEncoder
import kotlin.test.Test
import kotlin.test.assertFailsWith
import moe.rukamori.archivetune.shared.domain.platform.BrowserAuthRequest
import moe.rukamori.archivetune.shared.domain.platform.CredentialScope

class ModuleSessionBrowserTest {
    private val state = "0123456789abcdef0123456789abcdef"
    private val redirect = "archivetune://module-session"
    private fun request(callback: String = "$redirect?cb_version=v2grant&state=$state", outer: String = "") = BrowserAuthRequest(
        "module-test", CredentialScope.PERSONAL,
        "https://provider.example/challenge?id=challenge&cb=${URLEncoder.encode(callback, Charsets.UTF_8)}$outer", redirect, state)

    @Test fun acceptsBothChallengeContracts() {
        validateModuleSessionRequest(request())
        validateModuleSessionRequest(request(outer = "&state=$state"))
    }

    @Test fun rejectsWrongOrRepeatedCallbackState() {
        assertFailsWith<IllegalArgumentException> { validateModuleSessionRequest(request("$redirect?cb_version=v2grant&state=another-state-value")) }
        assertFailsWith<IllegalArgumentException> { validateModuleSessionRequest(request("$redirect?cb_version=v2grant&state=$state&state=$state")) }
        assertFailsWith<IllegalArgumentException> { validateModuleSessionRequest(request(outer = "&state=another-state-value")) }
    }

    @Test fun rejectsCallbacksOutsideTheModuleEndpoint() {
        for (callback in listOf("https://provider.example/?cb_version=v2grant&state=$state",
            "$redirect/path?cb_version=v2grant&state=$state", "$redirect?cb_version=v2grant&state=$state#fragment")) {
            assertFailsWith<IllegalArgumentException> { validateModuleSessionRequest(request(callback)) }
        }
    }

    @Test fun rejectsAmbiguousCallbackAndInsecureOrigin() {
        assertFailsWith<IllegalArgumentException> { validateModuleSessionRequest(request(outer = "&cb=other")) }
        val valid = request()
        assertFailsWith<IllegalArgumentException> { validateModuleSessionRequest(valid.copy(authorizationUrl = valid.authorizationUrl.replace("https:", "http:"))) }
    }
}
