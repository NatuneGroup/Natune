package moe.rukamori.archivetune.shared.data.telegram

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TdJsonTelegramBotSessionTest {
    private class FakeTd(private val answer: (type: String, query: JsonObject, call: Int) -> JsonObject) : TdJsonRequester {
        val queries = mutableListOf<JsonObject>()

        override suspend fun request(query: JsonObject, timeoutMs: Long): JsonObject {
            queries += query
            val type = query["@type"]?.jsonPrimitive?.contentOrNull ?: error("query without @type")
            return answer(type, query, queries.count { it["@type"] == JsonPrimitive(type) })
        }
    }

    @Test
    fun aBotIsResolvedThroughItsPrivateChatAndUser() = runTest {
        val td = FakeTd { type, _, _ ->
            when (type) {
                "searchPublicChat" -> json("""{"@type":"chat","id":"555","type":{"@type":"chatTypePrivate","user_id":99}}""")
                "getUser" -> json("""{"@type":"user","first_name":"Finder","type":{"@type":"userTypeBot"}}""")
                else -> error(type)
            }
        }
        val bot = assertNotNull(TdJsonTelegramBotSession(td).resolveBot("@FinderBot"))
        assertEquals(555L, bot.chatId)
        assertEquals("Finder", bot.title)
        assertEquals(JsonPrimitive("finderbot"), td.queries.first()["username"])
    }

    @Test
    fun aPersonIsNotABot() = runTest {
        val td = FakeTd { type, _, _ ->
            when (type) {
                "searchPublicChat" -> json("""{"@type":"chat","id":1,"type":{"@type":"chatTypePrivate","user_id":2}}""")
                else -> json("""{"@type":"user","first_name":"Ann","type":{"@type":"userTypeRegular"}}""")
            }
        }
        assertNull(TdJsonTelegramBotSession(td).resolveBot("ann"))
    }

    @Test
    fun repliesAreCollectedUntilTheBotFallsQuiet() = runTest {
        val td = FakeTd { _, _, call ->
            val messages = if (call == 1) {
                listOf(audioMessage("5", 10, outgoing = true))
            } else {
                listOf(audioMessage("5", 12), audioMessage("5", 11), audioMessage("5", 10, outgoing = true))
            }
            json("""{"@type":"messages","total_count":3,"messages":[${messages.joinToString(",")}]}""")
        }
        val session = TdJsonTelegramBotSession(td, pollIntervalMs = 1_000, replyTimeoutMs = 10_000, graceMs = 2_000)
        val replies = session.collectReplies(chatId = 5, afterMessageId = 10)
        assertEquals(listOf(11L, 12L), replies.map { it.messageId })
        replies.forEach { assertIs<TdJsonBotReply.Track>(it) }
        // One empty poll, one with the answer, then the quiet window: the poll count stays bounded.
        assertEquals(5, td.queries.size)
    }

    @Test
    fun aSilentBotTimesOutWithNoReplies() = runTest {
        val td = FakeTd { _, _, _ -> json("""{"@type":"messages","total_count":0,"messages":[]}""") }
        val session = TdJsonTelegramBotSession(td, pollIntervalMs = 1_000, replyTimeoutMs = 3_000, graceMs = 1_000)
        assertTrue(session.collectReplies(chatId = 5, afterMessageId = 0).isEmpty())
        assertEquals(4, td.queries.size)
    }

    @Test
    fun sendingTextNamesTheFunctionAndReturnsTheSentId() = runTest {
        val td = FakeTd { _, _, _ -> json("""{"@type":"message","id":321}""") }
        assertEquals(321L, TdJsonTelegramBotSession(td).sendText(5, "/start"))
        val query = td.queries.single()
        assertEquals(JsonPrimitive("sendMessage"), query["@type"])
        assertEquals(JsonPrimitive(5L), query["chat_id"])
    }
}
