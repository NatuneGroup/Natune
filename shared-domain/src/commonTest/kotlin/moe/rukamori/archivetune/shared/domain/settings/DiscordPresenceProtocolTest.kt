package moe.rukamori.archivetune.shared.domain.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.Json

/**
 * The wire shapes the Discord invariant fixes: PKCE authorize/exchange bodies, the minimal IDENTIFY, op 1 heartbeats
 * and op 3 presence updates. These functions are shared by the desktop and iOS hosts, so one test pins both.
 */
class DiscordPresenceProtocolTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun authorizeUrlCarriesPkcePublicClientAndFixedScopes() {
        val url = discordAuthorizeUrl(state = "state-value", challenge = "challenge-value")
        assertTrue(url.startsWith("https://discord.com/oauth2/authorize?"))
        assertTrue(url.contains("client_id=$DISCORD_APPLICATION_ID"))
        assertTrue(url.contains("response_type=code"))
        assertTrue(url.contains("redirect_uri=discord-$DISCORD_APPLICATION_ID%3A%2Fauthorize%2Fcallback"))
        assertTrue(url.contains("scope=openid%20identify%20sdk.social_layer_presence"))
        assertTrue(url.contains("state=state-value"))
        assertTrue(url.contains("code_challenge=challenge-value"))
        assertTrue(url.contains("code_challenge_method=S256"))
        assertFalse(url.contains("client_secret"))
    }

    @Test
    fun tokenBodiesUsePublicClientAndNoSecret() {
        val exchange = discordTokenExchangeBody("the-code", "the-verifier")
        assertTrue(exchange.contains("client_id=$DISCORD_APPLICATION_ID"))
        assertTrue(exchange.contains("grant_type=authorization_code"))
        assertTrue(exchange.contains("code=the-code"))
        assertTrue(exchange.contains("code_verifier=the-verifier"))
        assertTrue(exchange.contains("redirect_uri=discord-$DISCORD_APPLICATION_ID%3A%2Fauthorize%2Fcallback"))
        assertFalse(exchange.contains("client_secret"))

        val refresh = discordTokenRefreshBody("refresh+token")
        assertTrue(refresh.contains("grant_type=refresh_token"))
        assertTrue(refresh.contains("refresh_token=refresh%2Btoken"))
        assertFalse(refresh.contains("client_secret"))
    }

    @Test
    fun tokenGrantParsesAccessRefreshAndExpiry() {
        val grant = parseDiscordTokenGrant(
            """{"access_token":"acc","refresh_token":"ref","expires_in":604800,"token_type":"Bearer"}""",
        )
        assertNotNull(grant)
        assertEquals("acc", grant.accessToken)
        assertEquals("ref", grant.refreshToken)
        assertEquals(604800L, grant.expiresInSeconds)
        assertNull(parseDiscordTokenGrant("{}"))
        assertNull(parseDiscordTokenGrant("not json"))
    }

    @Test
    fun accountFactsJoinAvatarUrlFromIdAndHash() {
        val facts = parseDiscordAccountFacts(
            """{"id":"42","username":"name","global_name":"Display","avatar":"a1b2"}""",
        )
        assertNotNull(facts)
        assertEquals("name", facts.username)
        assertEquals("Display", facts.displayName)
        assertEquals("https://cdn.discordapp.com/avatars/42/a1b2.png", facts.avatarUrl)

        val noAvatar = parseDiscordAccountFacts("""{"id":"42","username":"name","global_name":null,"avatar":null}""")
        assertNotNull(noAvatar)
        assertNull(noAvatar.avatarUrl)
        assertNull(parseDiscordAccountFacts("{}"))
    }

    @Test
    fun identifyIsMinimalBearerTokenIntentsZero() {
        val root = json.parseToJsonElement(buildDiscordIdentifyJson("token-value", "ios")).jsonObject
        assertEquals(2, root.getValue("op").jsonPrimitive.intOrNull)
        val d = root.getValue("d").jsonObject
        assertEquals("Bearer token-value", d.getValue("token").jsonPrimitive.content)
        assertEquals(0, d.getValue("intents").jsonPrimitive.intOrNull)
        assertFalse("capabilities" in d)
        assertFalse("compress" in d)
        assertFalse("presence" in d)
        val properties = d.getValue("properties").jsonObject
        assertEquals("ios", properties.getValue("os").jsonPrimitive.content)
        assertEquals("ArchiveTune", properties.getValue("browser").jsonPrimitive.content)
        assertEquals("ArchiveTune", properties.getValue("device").jsonPrimitive.content)
    }

    @Test
    fun heartbeatCarriesSequenceOrNull() {
        val beat = json.parseToJsonElement(buildDiscordHeartbeatJson(17)).jsonObject
        assertEquals(1, beat.getValue("op").jsonPrimitive.intOrNull)
        assertEquals(17, beat.getValue("d").jsonPrimitive.intOrNull)
        val empty = json.parseToJsonElement(buildDiscordHeartbeatJson(null)).jsonObject
        assertIs<JsonNull>(empty.getValue("d"))
    }

    @Test
    fun gatewayEventReadsOpIntervalAndSequence() {
        val hello = parseDiscordGatewayEvent("""{"op":10,"d":{"heartbeat_interval":41250}}""")
        assertNotNull(hello)
        assertEquals(10, hello.op)
        assertEquals(41250L, hello.heartbeatIntervalMs)
        assertNull(hello.sequence)

        val dispatch = parseDiscordGatewayEvent("""{"op":0,"s":9,"t":"READY","d":{"user":{"id":"1"}}}""")
        assertNotNull(dispatch)
        assertEquals(0, dispatch.op)
        assertEquals(9, dispatch.sequence)
        assertNull(dispatch.heartbeatIntervalMs)

        // ACKs and heartbeat requests carry `d` as null or a number; only HELLO's `d` is an object, so those
        // shapes must parse instead of throwing inside the runCatching and dropping healthy frames.
        val ack = parseDiscordGatewayEvent("""{"op":11,"d":null}""")
        assertNotNull(ack)
        assertEquals(11, ack.op)
        assertNull(ack.heartbeatIntervalMs)

        val heartbeatRequest = parseDiscordGatewayEvent("""{"op":1,"d":null}""")
        assertNotNull(heartbeatRequest)
        assertEquals(1, heartbeatRequest.op)
        val heartbeatRequestSeq = parseDiscordGatewayEvent("""{"op":1,"d":7}""")
        assertNotNull(heartbeatRequestSeq)
        assertNull(heartbeatRequestSeq.heartbeatIntervalMs)

        assertNull(parseDiscordGatewayEvent("{}"))
        assertNull(parseDiscordGatewayEvent("junk"))
    }

    @Test
    fun presenceShapeMatchesShippedClient() {
        val preferences = PreferenceSnapshot.of(
            DiscordPresenceStatusKey to "dnd",
            DiscordActivityTypeKey to "LISTENING",
            DiscordActivityNameKey to DiscordActivitySource.SONG.name,
            DiscordActivityDetailsKey to DiscordActivitySource.SONG.name,
            DiscordActivityStateKey to DiscordActivitySource.ARTIST.name,
            DiscordLargeImageTypeKey to "thumbnail",
            DiscordSmallImageTypeKey to "appicon",
        )
        val track = DiscordPresenceTrack(
            title = "Song",
            artists = listOf("One", "Two"),
            album = "Album",
            artworkUri = "https://img/1.png",
        )
        val root = json.parseToJsonElement(
            buildDiscordPresenceJson(preferences, track, positionMs = 30_000L, durationMs = 180_000L, paused = false, nowMillis = 1_000_000L),
        ).jsonObject
        assertEquals(3, root.getValue("op").jsonPrimitive.intOrNull)
        val d = root.getValue("d").jsonObject
        assertEquals("dnd", d.getValue("status").jsonPrimitive.content)
        assertEquals(false, d.getValue("afk").jsonPrimitive.booleanOrNull)
        assertIs<JsonNull>(d.getValue("since"))
        val activity = d.getValue("activities").jsonArray.single().jsonObject
        assertEquals("Song", activity.getValue("name").jsonPrimitive.content)
        assertEquals(2, activity.getValue("type").jsonPrimitive.intOrNull)
        assertEquals("Song", activity.getValue("details").jsonPrimitive.content)
        assertEquals("One, Two", activity.getValue("state").jsonPrimitive.content)
        val timestamps = activity.getValue("timestamps").jsonObject
        assertEquals(970_000L, timestamps.getValue("start").jsonPrimitive.longOrNull)
        assertEquals(1_150_000L, timestamps.getValue("end").jsonPrimitive.longOrNull)
        val assets = activity.getValue("assets").jsonObject
        assertEquals("https://img/1.png", assets.getValue("large_image").jsonPrimitive.content)
        assertEquals("archivetune", assets.getValue("small_image").jsonPrimitive.content)
    }

    @Test
    fun presenceOmitsTimestampsWhenPausedOrUntimed() {
        val preferences = PreferenceSnapshot.EMPTY
        val track = DiscordPresenceTrack(title = "Song")
        val paused = json.parseToJsonElement(
            buildDiscordPresenceJson(preferences, track, positionMs = 1L, durationMs = 2L, paused = true, nowMillis = 0L),
        ).jsonObject.getValue("d").jsonObject.getValue("activities").jsonArray.single().jsonObject
        assertFalse("timestamps" in paused)
        val untimed = json.parseToJsonElement(
            buildDiscordPresenceJson(preferences, track, positionMs = null, durationMs = null, paused = false, nowMillis = 0L),
        ).jsonObject.getValue("d").jsonObject.getValue("activities").jsonArray.single().jsonObject
        assertFalse("timestamps" in untimed)
        // A presence with no track still names the app, never a blank activity.
        val empty = json.parseToJsonElement(
            buildDiscordPresenceJson(preferences, null, null, null, false, 0L),
        ).jsonObject.getValue("d").jsonObject.getValue("activities").jsonArray.single().jsonObject
        assertEquals("ArchiveTune", empty.getValue("name").jsonPrimitive.content)
        assertFalse("assets" in empty)
    }

    @Test
    fun imageChoicesFollowTheSettingsPageValues() {
        assertEquals("https://img/x.png", resolveDiscordImage("thumbnail", "", "https://img/x.png"))
        assertEquals("https://img/x.png", resolveDiscordImage("artist", "", "https://img/x.png"))
        assertEquals("archivetune", resolveDiscordImage("appicon", "", "https://img/x.png"))
        assertEquals("https://custom/img.png", resolveDiscordImage("custom", "https://custom/img.png", "https://img/x.png"))
        assertNull(resolveDiscordImage("dontshow", "", "https://img/x.png"))
        assertNull(resolveDiscordImage("custom", " ", "https://img/x.png"))
    }

    @Test
    fun queryParamDecodesCallbackValues() {
        val callback = "$DISCORD_REDIRECT_URI?code=abc%20def&state=s1"
        assertEquals("abc def", discordQueryParam(callback, "code"))
        assertEquals("s1", discordQueryParam(callback, "state"))
        assertNull(discordQueryParam(callback, "missing"))
        assertNull(discordQueryParam(DISCORD_REDIRECT_URI, "code"))
    }
}
