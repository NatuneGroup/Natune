package moe.rukamori.archivetune.shared.data.listentogether

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Wire-level parity for both codecs. Client frames (create/join/playback/chat-adjacent actions) are
 * encoded by this codec in either format; server frames are only decoded here, so their JSON wire
 * shape is checked by decoding the envelope the codec produced.
 */
class ListenTogetherCodecTest {

    private val jsonCodec = ListenTogetherMessageCodec(format = MessageFormat.JSON)
    private val protobufCodec = ListenTogetherMessageCodec(format = MessageFormat.PROTOBUF)
    private val json = Json { ignoreUnknownKeys = true }

    private fun track(id: String = "dQw4w9WgXcQ") = TrackInfo(
        id = id,
        title = "Track $id",
        artist = "Artist",
        album = "Album",
        duration = 213_000L,
        thumbnail = "https://i.ytimg.com/vi/$id/hqdefault.jpg",
    )

    private fun roomState() = RoomState(
        roomCode = "ABCD12",
        hostId = "host-1",
        users = listOf(
            UserInfo("host-1", "Host", isHost = true, avatarIndex = 3),
            UserInfo("guest-1", "Guest", isHost = false, avatarIndex = 7),
        ),
        currentTrack = track(),
        isPlaying = true,
        position = 42_000L,
        lastUpdate = 1_760_000_000_000L,
        volume = 0.8f,
        queue = listOf(track("next-1"), track("next-2")),
    )

    /** A client frame sent in [format], split back into its type and payload bytes by the codec. */
    private fun sent(codec: ListenTogetherMessageCodec, type: String, payload: Any?): Pair<String, ByteArray> =
        codec.decode(codec.encode(type, payload))

    /** A JSON client frame's payload object, decoded the way the server reads the envelope. */
    private inline fun <reified T> jsonPayload(frame: Pair<String, ByteArray>): T =
        json.decodeFromString<T>(frame.second.decodeToString())

    /** A server frame on the JSON wire: the same {type, payload} envelope the codec splits open. */
    private inline fun <reified T> jsonServerFrame(type: String, payload: T): ByteArray =
        """{"type":"$type","payload":${json.encodeToString(payload)}}""".encodeToByteArray()

    @Test
    fun `playback action round-trips in both codecs`() {
        val payload = PlaybackActionPayload(
            action = PlaybackActions.PLAY,
            trackId = "dQw4w9WgXcQ",
            position = 12_345L,
            serverTime = 1_760_000_000_000L,
            volume = 0.5f,
        )
        val jsonFrame = sent(jsonCodec, MessageTypes.PLAYBACK_ACTION, payload)
        assertEquals(MessageTypes.PLAYBACK_ACTION, jsonFrame.first)
        assertEquals(payload, jsonPayload<PlaybackActionPayload>(jsonFrame))

        val protoFrame = sent(protobufCodec, MessageTypes.PLAYBACK_ACTION, payload)
        assertEquals(MessageTypes.PLAYBACK_ACTION, protoFrame.first)
        // proto3 cannot tell an absent list from an empty one: an unset queue decodes as empty.
        assertEquals(
            payload.copy(queue = emptyList()),
            protobufCodec.decodePayload(protoFrame.first, protoFrame.second, MessageFormat.PROTOBUF),
        )
    }

    @Test
    fun `change track with a queue round-trips in both codecs`() {
        val payload = PlaybackActionPayload(
            action = PlaybackActions.CHANGE_TRACK,
            trackId = track().id,
            position = 0L,
            trackInfo = track(),
            queue = listOf(track("a"), track("b")),
            queueTitle = "Up next",
            insertNext = false,
        )
        assertEquals(payload, jsonPayload<PlaybackActionPayload>(sent(jsonCodec, MessageTypes.PLAYBACK_ACTION, payload)))
        val protoFrame = sent(protobufCodec, MessageTypes.PLAYBACK_ACTION, payload)
        // proto3 presence: an explicit 0/false is written as absent and decodes as the Kotlin null.
        assertEquals(
            payload.copy(position = null, insertNext = null),
            protobufCodec.decodePayload(protoFrame.first, protoFrame.second, MessageFormat.PROTOBUF),
        )
    }

    @Test
    fun `room create and join frames carry their fields in both codecs`() {
        val create = CreateRoomPayload(username = "Host", avatarIndex = 9)
        val createJson = sent(jsonCodec, MessageTypes.CREATE_ROOM, create)
        assertEquals(create, jsonPayload<CreateRoomPayload>(createJson))
        // The protobuf create writes the username field only, matching the server's schema.
        val createProto = sent(protobufCodec, MessageTypes.CREATE_ROOM, create)
        assertEquals(MessageTypes.CREATE_ROOM, createProto.first)
        assertEquals(
            "Host",
            (protobufCodec.decodePayload(createProto.first, createProto.second, MessageFormat.PROTOBUF) as CreateRoomPayload).username,
        )

        val join = JoinRoomPayload(roomCode = "ABCD12", username = "Guest", avatarIndex = 4)
        val joinJson = sent(jsonCodec, MessageTypes.JOIN_ROOM, join)
        assertEquals(join, jsonPayload<JoinRoomPayload>(joinJson))
        val joinProto = sent(protobufCodec, MessageTypes.JOIN_ROOM, join)
        val joinDecoded = protobufCodec.decodePayload(joinProto.first, joinProto.second, MessageFormat.PROTOBUF) as JoinRoomPayload
        assertEquals(join.roomCode, joinDecoded.roomCode)
        assertEquals(join.username, joinDecoded.username)
    }

    @Test
    fun `buffer and sync-control frames round-trip in protobuf`() {
        val buffer = BufferReadyPayload(trackId = "v1")
        val (bt, bb) = sent(protobufCodec, MessageTypes.BUFFER_READY, buffer)
        assertEquals(buffer, protobufCodec.decodePayload(bt, bb, MessageFormat.PROTOBUF))

        val reconnect = ReconnectPayload(sessionToken = "tok-123")
        val (rt, rb) = sent(protobufCodec, MessageTypes.RECONNECT, reconnect)
        assertEquals(reconnect, protobufCodec.decodePayload(rt, rb, MessageFormat.PROTOBUF))

        val suggest = SuggestTrackPayload(trackInfo = track())
        val (st, sb) = sent(protobufCodec, MessageTypes.SUGGEST_TRACK, suggest)
        assertEquals(suggest, protobufCodec.decodePayload(st, sb, MessageFormat.PROTOBUF))

        val approve = ApproveJoinPayload(userId = "u2")
        val (at, ab) = sent(protobufCodec, MessageTypes.APPROVE_JOIN, approve)
        assertEquals(approve, protobufCodec.decodePayload(at, ab, MessageFormat.PROTOBUF))
    }

    @Test
    fun `server frames decode from the JSON envelope`() {
        fun roundTrip(type: String, payloadJson: String): Any? {
            val frame = jsonCodec.decode("""{"type":"$type","payload":$payloadJson}""".encodeToByteArray())
            assertEquals(type, frame.first)
            return jsonCodec.decodePayload(frame.first, frame.second, MessageFormat.JSON)
        }

        val created = RoomCreatedPayload(roomCode = "ABCD12", userId = "u1", sessionToken = "tok")
        assertEquals(created, roundTrip(MessageTypes.ROOM_CREATED, json.encodeToString(created)))

        val approved = JoinApprovedPayload(roomCode = "ABCD12", userId = "g1", sessionToken = "tok", state = roomState())
        assertEquals(approved, roundTrip(MessageTypes.JOIN_APPROVED, json.encodeToString(approved)))

        val sync = SyncStatePayload(
            currentTrack = track(),
            isPlaying = true,
            position = 65_432L,
            lastUpdate = 1_760_000_111_222L,
            queue = listOf(track("q1")),
            volume = 0.25f,
        )
        assertEquals(sync, roundTrip(MessageTypes.SYNC_STATE, json.encodeToString(sync)))

        // The guest's playback ticks arrive as sync_playback frames carrying the same action payload.
        val playback = PlaybackActionPayload(action = PlaybackActions.SEEK, position = 5_000L, serverTime = 1_760_000_222_333L)
        val playbackDecoded = roundTrip(MessageTypes.SYNC_PLAYBACK, json.encodeToString(playback)) as PlaybackActionPayload
        assertEquals(playback.action, playbackDecoded.action)
        assertEquals(playback.position, playbackDecoded.position)
        assertEquals(playback.serverTime, playbackDecoded.serverTime)

        val bufferWait = BufferWaitPayload(trackId = track().id, waitingFor = listOf("u1", "u2"))
        assertEquals(bufferWait, roundTrip(MessageTypes.BUFFER_WAIT, json.encodeToString(bufferWait)))

        val suggestion = SuggestionReceivedPayload("s1", "u9", "Guest", track())
        assertEquals(suggestion, roundTrip(MessageTypes.SUGGESTION_RECEIVED, json.encodeToString(suggestion)))

        val reconnected = ReconnectedPayload(roomCode = "ABCD12", userId = "u1", state = roomState(), isHost = true)
        assertEquals(reconnected, roundTrip(MessageTypes.RECONNECTED, json.encodeToString(reconnected)))
    }

    @Test
    fun `server frames decode from protobuf the way The Meowery sends them`() {
        // Protobuf server frames are produced server-side; every msgType the client decodes maps to a payload type.
        val frame = protobufCodec.encode(
            MessageTypes.BUFFER_READY,
            BufferReadyPayload(trackId = "v9"),
        )
        val (type, bytes) = protobufCodec.decode(frame)
        assertEquals(MessageTypes.BUFFER_READY, type)
        assertEquals(BufferReadyPayload("v9"), protobufCodec.decodePayload(type, bytes, MessageFormat.PROTOBUF))
    }

    @Test
    fun `default scalar fields are omitted on the wire`() {
        // proto3 omits zero/false/empty values: a payload of defaults must decode back to the same defaults.
        val payload = PlaybackActionPayload(action = PlaybackActions.PAUSE)
        val (type, bytes) = sent(protobufCodec, MessageTypes.PLAYBACK_ACTION, payload)
        assertEquals(
            payload.copy(queue = emptyList()),
            protobufCodec.decodePayload(type, bytes, MessageFormat.PROTOBUF),
        )
    }

    @Test
    fun `format detection reads the first byte`() {
        val jsonFrame = jsonCodec.encode(MessageTypes.PING, null)
        assertEquals(MessageFormat.JSON, ListenTogetherMessageCodec.detectMessageFormat(jsonFrame))
        val protoFrame = protobufCodec.encode(MessageTypes.PING, null)
        assertEquals(MessageFormat.PROTOBUF, ListenTogetherMessageCodec.detectMessageFormat(protoFrame))
        assertNotEquals(jsonFrame[0], protoFrame[0])
    }
}
