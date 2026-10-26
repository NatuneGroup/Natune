package moe.rukamori.archivetune.shared.data.listentogether

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Meowery speaks protobuf only and has no chat relay. On the wire the room must never see a
 * `{` byte from us, and a chat send must surface CHAT_UNSUPPORTED_BY_SERVER instead of writing a
 * frame the server cannot read.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ListenTogetherMeoweryTest {

    private class FakeSocket : ListenTogetherSocket {
        var listener: ListenTogetherSocketListener? = null
        val sent = mutableListOf<ByteArray>()

        override fun send(frame: ByteArray): Boolean {
            sent += frame.copyOf()
            return true
        }

        override fun close(code: Int, reason: String) {}
        override fun cancel() {}
    }

    private class FakeFactory(val socket: FakeSocket) : ListenTogetherSocketFactory {
        var lastUrl: String? = null

        override fun open(url: String, listener: ListenTogetherSocketListener): ListenTogetherSocket {
            lastUrl = url
            socket.listener = listener
            return socket
        }
    }

    private class FakePreferences : ListenTogetherPreferences {
        override fun serverUrl() = MEOWERY_URL
        override fun autoApproveJoinRequests() = true
        override fun autoApproveSuggestions() = false
        override fun avatarIndex() = 0
    }

    private class FakeStore : ListenTogetherStore {
        var session: ListenTogetherSession? = null
        var blocked: Set<String> = emptySet()

        override suspend fun loadSession() = session
        override suspend fun saveSession(session: ListenTogetherSession) {
            this.session = session
        }
        override suspend fun clearSession() {
            session = null
        }
        override suspend fun loadBlockedUsernames() = blocked
        override suspend fun saveBlockedUsernames(usernames: Set<String>) {
            blocked = usernames
        }
    }

    /** One protobuf frame as The Meowery writes it: a typed envelope around a field-encoded payload. */
    private fun serverFrame(type: String, payload: ByteArray): ByteArray =
        ProtoWriter().apply {
            writeString(ListenTogetherMessageCodec.FIELD_ENVELOPE_TYPE, type)
            writeBytes(ListenTogetherMessageCodec.FIELD_ENVELOPE_PAYLOAD, payload)
        }.toByteArray()

    private fun roomCreatedPayload(): ByteArray =
        ProtoWriter().apply {
            writeString(ListenTogetherMessageCodec.ROOM_CREATED_ROOM_CODE, "ABCD12")
            writeString(ListenTogetherMessageCodec.ROOM_CREATED_USER_ID, "u1")
            writeString(ListenTogetherMessageCodec.ROOM_CREATED_SESSION_TOKEN, "tok")
        }.toByteArray()

    private fun host(test: TestScope, socket: FakeSocket, factory: FakeFactory): Pair<ListenTogetherClient, MutableList<ListenTogetherEvent>> {
        // backgroundScope is cancelled by runTest, so the client's ping and deadline jobs never outlive a test.
        val scope = test.backgroundScope
        val events = mutableListOf<ListenTogetherEvent>()
        val client = ListenTogetherClient(
            scope = scope,
            sockets = factory,
            codec = ListenTogetherMessageCodec(),
            preferences = FakePreferences(),
            store = FakeStore(),
            network = MutableStateFlow(true),
            nowMs = { 1_760_000_000_000L },
        )
        scope.launch { client.events.collect { events += it } }
        return client to events
    }

    @Test
    fun `every frame sent to The Meowery is protobuf`() = runTest {
        val socket = FakeSocket()
        val factory = FakeFactory(socket)
        val (client, _) = host(this, socket, factory)

        client.connect()
        testScheduler.runCurrent()
        assertEquals(MEOWERY_URL, factory.lastUrl)
        socket.listener!!.onOpen()
        testScheduler.runCurrent()
        assertEquals(MessageFormat.PROTOBUF, client.protocol.value)

        client.createRoom("Host")
        testScheduler.runCurrent()
        assertTrue(socket.sent.isNotEmpty())
        // The create frame is protobuf binary, which never starts with a '{' byte.
        socket.sent.forEach { frame -> assertTrue(frame.isNotEmpty() && frame[0] != '{'.code.toByte()) }

        client.sendPlaybackAction(PlaybackActionPayload(action = PlaybackActions.PLAY, trackId = "v1", position = 0L))
        client.sendBufferReady("v1")
        client.requestSync()
        client.leaveRoom()
        testScheduler.runCurrent()
        socket.sent.forEach { frame -> assertTrue(frame[0] != '{'.code.toByte()) }
    }

    @Test
    fun `chat is refused on The Meowery instead of sending a JSON frame`() = runTest {
        val socket = FakeSocket()
        val factory = FakeFactory(socket)
        val (client, events) = host(this, socket, factory)

        client.connect()
        testScheduler.runCurrent()
        socket.listener!!.onOpen()
        testScheduler.runCurrent()
        client.createRoom("Host")
        testScheduler.runCurrent()
        val framesBefore = socket.sent.size

        // The room answers room_created in protobuf, populating the room state.
        socket.listener!!.onMessage(serverFrame(MessageTypes.ROOM_CREATED, roomCreatedPayload()))
        testScheduler.runCurrent()

        client.sendChat(ChatPayload("hello room"))
        testScheduler.runCurrent()

        // No chat frame left the device; the member sees the unsupported notice.
        assertEquals(framesBefore, socket.sent.size)
        assertTrue(events.any { it is ListenTogetherEvent.Notice && it.notice == ListenTogetherNotice.CHAT_UNSUPPORTED_BY_SERVER })
        socket.sent.forEach { frame -> assertTrue(frame[0] != '{'.code.toByte()) }
    }

    @Test
    fun `chat reaches the JSON host as a JSON frame`() = runTest {
        val socket = FakeSocket()
        val factory = object : ListenTogetherSocketFactory {
            override fun open(url: String, listener: ListenTogetherSocketListener): ListenTogetherSocket {
                socket.listener = listener
                return socket
            }
        }
        val scope = backgroundScope
        val events = mutableListOf<ListenTogetherEvent>()
        val client = ListenTogetherClient(
            scope = scope,
            sockets = factory,
            codec = ListenTogetherMessageCodec(),
            preferences = object : ListenTogetherPreferences {
                override fun serverUrl() = ListenTogetherServers.defaultServerUrl
                override fun autoApproveJoinRequests() = true
                override fun autoApproveSuggestions() = false
                override fun avatarIndex() = 0
            },
            store = FakeStore(),
            network = MutableStateFlow(true),
            nowMs = { 1_760_000_000_000L },
        )
        scope.launch { client.events.collect { events += it } }

        client.connect()
        testScheduler.runCurrent()
        socket.listener!!.onOpen()
        testScheduler.runCurrent()
        client.createRoom("Host")
        testScheduler.runCurrent()
        assertEquals(MessageFormat.JSON, client.protocol.value)

        // The JSON host's room_created arrives as a {type, payload} text frame.
        socket.listener!!.onMessage(
            """{"type":"room_created","payload":{"room_code":"ABCD12","user_id":"u1","session_token":"tok"}}"""
                .encodeToByteArray(),
        )
        testScheduler.runCurrent()
        val framesBefore = socket.sent.size

        client.sendChat(ChatPayload("hello room"))
        testScheduler.runCurrent()
        assertTrue(socket.sent.size > framesBefore)
        assertEquals('{'.code.toByte(), socket.sent.last()[0])
    }

    private companion object {
        const val MEOWERY_URL = "wss://metroserverx.meowery.eu/ws"
    }
}
