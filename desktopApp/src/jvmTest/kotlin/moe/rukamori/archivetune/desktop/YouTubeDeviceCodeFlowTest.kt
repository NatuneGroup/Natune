package moe.rukamori.archivetune.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import moe.rukamori.archivetune.shared.data.provider.YouTubeDeviceCodeFlow

class YouTubeDeviceCodeFlowTest {
    @Test fun deviceCodeNeedsBothCodesAndFillsDefaults() {
        val code = YouTubeDeviceCodeFlow.parseDeviceCode("""{"device_code":"d","user_code":"ABCD-EFGH","interval":0}""")
        assertEquals("ABCD-EFGH", code?.userCode)
        assertEquals(YouTubeDeviceCodeFlow.DEFAULT_VERIFICATION_URL, code?.verificationUrl)
        assertEquals(1, code?.intervalSeconds)
        assertEquals(1800, code?.expiresInSeconds)
        assertNull(YouTubeDeviceCodeFlow.parseDeviceCode("""{"device_code":"d"}"""))
        assertNull(YouTubeDeviceCodeFlow.parseDeviceCode("not json"))
    }

    @Test fun pollMapsPendingSlowDownGrantAndErrors() {
        assertEquals(YouTubeDeviceCodeFlow.Poll.Pending, YouTubeDeviceCodeFlow.parsePoll("""{"error":"authorization_pending"}"""))
        assertEquals(YouTubeDeviceCodeFlow.Poll.SlowDown, YouTubeDeviceCodeFlow.parsePoll("""{"error":"slow_down"}"""))
        assertEquals(
            YouTubeDeviceCodeFlow.Poll.Granted("a", "r", 60),
            YouTubeDeviceCodeFlow.parsePoll("""{"access_token":"a","refresh_token":"r","expires_in":60}"""),
        )
        assertEquals(
            YouTubeDeviceCodeFlow.Poll.Granted("a", null, 3600),
            YouTubeDeviceCodeFlow.parsePoll("""{"access_token":"a"}"""),
        )
        assertEquals(YouTubeDeviceCodeFlow.Poll.Failed("access_denied"), YouTubeDeviceCodeFlow.parsePoll("""{"error":"access_denied"}"""))
        assertEquals(YouTubeDeviceCodeFlow.Poll.Failed("no_access_token"), YouTubeDeviceCodeFlow.parsePoll("{}"))
        assertEquals(YouTubeDeviceCodeFlow.Poll.Failed("network"), YouTubeDeviceCodeFlow.parsePoll(""))
    }
}
