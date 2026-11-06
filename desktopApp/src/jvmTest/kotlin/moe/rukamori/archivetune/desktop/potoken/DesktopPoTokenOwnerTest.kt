package moe.rukamori.archivetune.desktop.potoken

import com.metrolist.innertubex.extraction.strategy.PoTokenProviderKind
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import moe.rukamori.archivetune.shared.data.potoken.MintedPoTokens
import moe.rukamori.archivetune.shared.ui.settings.PoTokenHelperState
import moe.rukamori.archivetune.shared.ui.settings.PoTokenHelperStatus

class DesktopPoTokenOwnerTest {
    @Test
    fun theDefaultWebSwitchDoesNotLeavePlaybackUntokened() = runTest {
        val helper = FakeLocalHelper(MintedPoTokens(playerToken = "player-token", sessionToken = "session-token"))
        // The old web-client switch defaults off, and the shipped build has no BotGuard key pair.
        val owner = owner(enabled = false, helper = helper)

        val minted = owner.mintForPlayback("video-1", signIn = null, visitorData = "visitor-1")

        assertEquals("player-token", minted?.playerToken)
        assertEquals("session-token", minted?.sessionToken)
        assertEquals(listOf("video-1" to "visitor-1"), helper.requests)
        owner.close()
    }

    @Test
    fun theHelperAnswersEvenWhenTheBotGuardKeysAreEmpty() = runTest {
        val helper = FakeLocalHelper(MintedPoTokens(playerToken = "player-token", sessionToken = "session-token"))
        val owner = owner(enabled = true, helper = helper)

        assertEquals("player-token", owner.mintForPlayback("video-1", signIn = null, visitorData = "visitor-1")?.playerToken)
        assertEquals(1, helper.requests.size)
        owner.close()
    }

    @Test
    fun aDisabledHelperFallsBackCleanly() = runTest {
        val helper = FakeLocalHelper(MintedPoTokens(playerToken = "player-token", sessionToken = "session-token"))
        val owner = owner(enabled = false, helper = helper, helperEnabled = false)

        assertNull(owner.mintForPlayback("video-1", signIn = null, visitorData = "visitor-1"))
        assertTrue(helper.requests.isEmpty())
        assertTrue(owner.tokenProvider.capabilities.providers.isEmpty())
        assertEquals(
            PoTokenHelperStatus(PoTokenHelperState.DISABLED),
            owner.localHelperStatus(),
        )
        owner.close()
    }

    @Test
    fun capabilitiesAdmitWebClientsTheHelperCanSatisfy() {
        val helper = FakeLocalHelper(MintedPoTokens(playerToken = "player-token", sessionToken = "session-token"))
        val owner = owner(enabled = false, helper = helper)

        val capabilities = owner.tokenProvider.capabilities

        assertEquals(setOf(PoTokenProviderKind.WEB_BOTGUARD), capabilities.providers)
        assertTrue(!capabilities.usesWebView)
    }

    @Test
    fun innerTubeProviderUsesTheHelperBindings() = runTest {
        val helper = FakeLocalHelper(MintedPoTokens(playerToken = "player-token", sessionToken = "session-token"))
        val owner = owner(enabled = false, helper = helper)

        val result = owner.tokenProvider.getPoToken("video-1", "visitor-1", cookie = null)

        assertEquals("session-token", result?.playerRequestToken)
        assertEquals("player-token", result?.streamingDataToken)
        assertEquals("visitor-1", result?.visitorData)
        owner.close()
    }

    @Test
    fun anUnavailableHelperReturnsNoTokenInsteadOfHalfAPair() = runTest {
        val helper = FakeLocalHelper(tokens = null)
        val owner = owner(enabled = false, helper = helper)

        assertNull(owner.mintForPlayback("video-1", signIn = null, visitorData = "visitor-1"))
        assertNull(owner.tokenProvider.getPoToken("video-1", "visitor-1", cookie = null))
        owner.close()
    }

    @Test
    fun statusReportsUnsupportedWithoutAHelper() = runTest {
        val owner = owner(enabled = false, helper = null)
        assertEquals(PoTokenHelperStatus(PoTokenHelperState.UNSUPPORTED), owner.localHelperStatus())
    }

    private fun owner(
        enabled: Boolean,
        helper: LocalPoTokenHelper?,
        helperEnabled: Boolean = true,
    ): DesktopPoTokenOwner =
        DesktopPoTokenOwner(
            http = HttpClient(MockEngine { error("the owner must not contact the network in these tests") }),
            enabled = { enabled },
            localHelper = helper,
            localHelperEnabled = { helperEnabled },
            configuredCredentials = { null },
        )

    private class FakeLocalHelper(
        private val tokens: MintedPoTokens?,
        override val availableOnHost: Boolean = true,
    ) : LocalPoTokenHelper {
        val requests = mutableListOf<Pair<String, String>>()

        override suspend fun mint(videoId: String, sessionId: String): MintedPoTokens? {
            requests += videoId to sessionId
            return tokens
        }

        override suspend fun clearTokens() {
        }

        override suspend fun disable() {
        }

        override suspend fun status(): PoTokenHelperStatus =
            PoTokenHelperStatus(PoTokenHelperState.READY, "127.0.0.1:4416")

        override suspend fun close() {
        }
    }
}
