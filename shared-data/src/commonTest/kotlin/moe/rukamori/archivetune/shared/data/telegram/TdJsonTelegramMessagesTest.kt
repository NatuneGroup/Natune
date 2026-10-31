package moe.rukamori.archivetune.shared.data.telegram

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import moe.rukamori.archivetune.shared.domain.telegram.TelegramTrackRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TdJsonTelegramMessagesTest {
    @Test
    fun anAudioMessageReadsAsATrackWithItsTelegramMediaId() {
        val track = assertNotNull(TdJsonTelegramMessages.audioTrack(json(audioMessage(chatId = "-1009", id = 42))))
        assertEquals(TelegramTrackRef(-1009, 42, 7, "uniq"), TelegramTrackRef.fromMediaId(track.mediaId))
        assertEquals("telegram:-1009:42", track.occurrenceId)
        val item = track.item(fallbackArtist = "Channel")
        assertEquals("Song", item.title)
        assertEquals("Singer", item.subtitle)
        assertEquals(180_000L, item.durationMs)
        assertEquals(TelegramTrackRef.SCHEME, item.ref.provider)
    }

    @Test
    fun aDocumentThatIsNotAudioIsSkipped() {
        val message = """{"@type":"message","id":5,"chat_id":1,"content":{"@type":"messageDocument",
            "document":{"file_name":"notes.pdf","mime_type":"application/pdf","document":{"id":3,"size":10}}}}"""
        assertNull(TdJsonTelegramMessages.audioTrack(json(message)))
    }

    @Test
    fun anAudioDocumentFallsBackToItsFileNameAndTheChannelTitle() {
        val message = """{"@type":"message","id":6,"chat_id":1,"content":{"@type":"messageDocument",
            "document":{"file_name":"Band - Tune.flac","mime_type":"audio/flac","document":{"id":3,"size":10}}}}"""
        val track = assertNotNull(TdJsonTelegramMessages.audioTrack(json(message)))
        assertTrue(track.isLossless)
        assertEquals("Tune", track.item("Channel").title)
        assertEquals("Band", track.item("Channel").subtitle)
    }

    @Test
    fun anInlineKeyboardReadsAsAPromptWithCallbackAndLinkButtonsOnly() {
        val message = """{"@type":"message","id":9,"chat_id":"77","is_outgoing":false,
            "content":{"@type":"messageText","text":{"@type":"formattedText","text":"Pick one"}},
            "reply_markup":{"@type":"replyMarkupInlineKeyboard","rows":[
              [{"@type":"inlineKeyboardButton","text":"First","type":{"@type":"inlineKeyboardButtonTypeCallback","data":"AQI="}},
               {"@type":"inlineKeyboardButton","text":"Site","type":{"@type":"inlineKeyboardButtonTypeUrl","url":"https://example.org"}}],
              [{"@type":"inlineKeyboardButton","text":"Pay","type":{"@type":"inlineKeyboardButtonTypeBuy"}}]
            ]}}"""
        val reply = assertIs<TdJsonBotReply.Prompt>(TdJsonTelegramMessages.botReply(json(message)))
        assertEquals("Pick one", reply.prompt.text)
        assertEquals(77L, reply.prompt.chatId)
        assertEquals(1, reply.prompt.rows.size)
        assertEquals(TdJsonInlineButton("First", "AQI=", null), reply.prompt.rows[0][0])
        assertEquals(TdJsonInlineButton("Site", null, "https://example.org"), reply.prompt.rows[0][1])
    }

    @Test
    fun aCancelChipIsRecognised() {
        val prompt = TdJsonInlinePrompt(1, 2, "", emptyList())
        assertTrue(prompt.isCancel(TdJsonInlineButton("Cancel search", "x", null)))
        assertTrue(prompt.isCancel(TdJsonInlineButton("\u2715", "x", null)))
    }

    @Test
    fun audioWinsOverAKeyboardOnTheSameMessage() {
        assertIs<TdJsonBotReply.Track>(TdJsonTelegramMessages.botReply(json(audioMessage(chatId = "1", id = 3))))
    }
}

internal fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

internal fun audioMessage(chatId: String, id: Long, outgoing: Boolean = false): String =
    """{"@type":"message","id":$id,"chat_id":"$chatId","is_outgoing":$outgoing,"content":{"@type":"messageAudio",
        "audio":{"duration":180,"title":"Song","performer":"Singer","file_name":"song.mp3","mime_type":"audio/mpeg",
        "audio":{"@type":"file","id":7,"size":1000,"remote":{"unique_id":"uniq"}}}}}"""
