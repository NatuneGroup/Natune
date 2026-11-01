package moe.rukamori.archivetune.shared.data.potoken

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The byte-level contract of the BotGuard recipe: `Create` answers in both plain and scrambled
 * form, `GenerateIT` integrity tokens, the CSV->base64url mint form, the `pot=` URL append and the
 * session-id derivation — each exactly as the Android parser produces them.
 */
class PoTokenChallengeParserTest {

    // ---------------------------------------------------------------- Create parsing

    @Test
    fun createChallengePlainFormReadsEveryField() {
        val challenge =
            parseCreateChallenge(
                """[["unused",["interpreter_js","other"],["interpreter_url"],null,"vm_program","globalName"],null]""",
            )
        assertEquals("vm_program", challenge.program)
        assertEquals("globalName", challenge.globalName)
        assertEquals("interpreter_js", challenge.interpreterJavascript)
        assertEquals("interpreter_url", challenge.interpreterUrl)
    }

    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun createChallengeScrambledFormUndoesTheByteShift() {
        // The scrambled payload is the challenge JSON with 97 subtracted per byte, base64'd.
        val innerJson = """["unused",["interpreter_js"],["interpreter_url"],null,"vm_program","globalName"]"""
        val scrambled =
            Base64.encode(innerJson.encodeToByteArray().map { (it - 97).toByte() }.toByteArray())
        val challenge = parseCreateChallenge("""[0,"$scrambled"]""")
        assertEquals("vm_program", challenge.program)
        assertEquals("globalName", challenge.globalName)
        assertEquals("interpreter_js", challenge.interpreterJavascript)
        assertEquals("interpreter_url", challenge.interpreterUrl)
    }

    @Test
    fun createChallengeMissingFieldsFail() {
        assertFailsWith<PoTokenMintException> { parseCreateChallenge("not json") }
        assertFailsWith<PoTokenMintException> { parseCreateChallenge("[[]]") }
        assertFailsWith<PoTokenMintException> { parseCreateChallenge("""[["x"],null]""") }
    }

    @Test
    fun driverJsonCarriesExactlyTheFieldsTheWebViewForwards() {
        val json =
            BotGuardChallenge(
                program = "prog",
                globalName = "gname",
                interpreterJavascript = "js",
                interpreterUrl = "url",
            ).toDriverJson()
        assertTrue(json.contains("\"program\":\"prog\""))
        assertTrue(json.contains("\"globalName\":\"gname\""))
        assertTrue(json.contains("\"privateDoNotAccessOrElseSafeScriptWrappedValue\":\"js\""))
        assertTrue(json.contains("\"privateDoNotAccessOrElseTrustedResourceUrlWrappedValue\":\"url\""))
    }

    @Test
    fun driverJsonNullsTheAbsentInterpreterValues() {
        val json =
            BotGuardChallenge(program = "p", globalName = "g", interpreterJavascript = null, interpreterUrl = null)
                .toDriverJson()
        assertTrue(json.contains("\"privateDoNotAccessOrElseSafeScriptWrappedValue\":null"))
        assertTrue(json.contains("\"privateDoNotAccessOrElseTrustedResourceUrlWrappedValue\":null"))
    }

    // ---------------------------------------------------------------- GenerateIT parsing

    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun integrityTokenReadsBytesAndLifetime() {
        val bytes = byteArrayOf(1, 2, 3, -1)
        val token = parseIntegrityToken("""["${Base64.encode(bytes)}",123]""")
        assertTrue(token.tokenBytes.contentEquals(bytes))
        assertEquals(123L, token.lifetimeSeconds)
    }

    @Test
    fun integrityTokenRejectsMalformedBodies() {
        assertFailsWith<PoTokenMintException> { parseIntegrityToken("{}") }
        assertFailsWith<PoTokenMintException> { parseIntegrityToken("[\"not-base64!!\"]") }
    }

    // ---------------------------------------------------------------- Mint + URL + session helpers

    @Test
    fun commaSeparatedBytesBecomeBase64UrlWithPadding() {
        assertEquals("AQID_w==", commaSeparatedBytesToPoToken("1,2,3,255"))
        assertEquals("AA==", commaSeparatedBytesToPoToken("0"))
    }

    @Test
    fun potAppendsOnceWithTheRawToken() {
        val url = "https://r1---sn.googlevideo.com/videoplayback?id=abc&expire=1"
        val token = "dG9rZW4td2l0aA=="
        val appended = appendPoTokenToStreamUrl(url, token)
        assertEquals("$url&pot=$token", appended)
        // A second append is a no-op, like appendGvsPoToken's pot= presence check.
        assertEquals(appended, appendPoTokenToStreamUrl(appended, "other"))
        assertEquals(url, appendPoTokenToStreamUrl(url, null))
        assertEquals(url, appendPoTokenToStreamUrl(url, ""))
    }

    @Test
    fun potUsesQuestionMarkOnABareUrl() {
        assertEquals("https://x.example/v?pot=tok", appendPoTokenToStreamUrl("https://x.example/v", "tok"))
    }

    @Test
    fun sessionIdPrefersTheDataSyncUserSessionWhenSignedIn() {
        assertEquals("user-session", poTokenSessionId("delegated||user-session", "visitor", hasLoginContext = true))
        // Percent-escaped separators decode before the split, like core's normalizeDataSyncId.
        assertEquals("user-session", poTokenSessionId("delegated%7C%7Cuser-session", "visitor", hasLoginContext = true))
        // No separator: the whole value is the user session.
        assertEquals("plain", poTokenSessionId("plain", "visitor", hasLoginContext = true))
    }

    @Test
    fun sessionIdFallsBackToVisitorData() {
        // No login context binds to the visitor; a login context binds only to the dataSync id,
        // exactly like PlaybackAuthState.sessionId — no visitor fallback inside the login branch.
        assertNull(poTokenSessionId(null, "visitor", hasLoginContext = true))
        // A dangling separator normalizes to the delegated id, which then resolves whole.
        assertEquals("delegated", poTokenSessionId("delegated||", "visitor", hasLoginContext = true))
        assertEquals("visitor", poTokenSessionId("anything||else", "visitor", hasLoginContext = false))
        assertNull(poTokenSessionId(null, null, hasLoginContext = false))
        assertNull(poTokenSessionId(null, " ", hasLoginContext = false))
    }

    @Test
    fun webClientNamesCarryTheToken() {
        val minted = MintedPoTokens(playerToken = "player", sessionToken = "session")
        assertEquals("player", poTokenForWebClient("WEB_REMIX", minted))
        assertEquals("player", poTokenForWebClient("MWEB", minted))
        assertNull(poTokenForWebClient("ANDROID_MUSIC", minted))
        assertNull(poTokenForWebClient("IOS", minted))
        assertNull(poTokenForWebClient("WEB_REMIX", null))
        assertEquals(
            "session",
            poTokenForWebClient("WEB", MintedPoTokens(playerToken = "", sessionToken = "session")),
        )
    }

    @Test
    fun jsUint8ArrayLiteralsStayAscii() {
        assertEquals("new Uint8Array([65])", identifierToJsUint8Array("A"))
        assertEquals("new Uint8Array([0,255])", bytesToJsUint8Array(byteArrayOf(0, -1)))
        assertTrue(poTokenJsString("a\"b\u2028c").startsWith("\"a\\\"b\\u2028c\""))
    }
}
