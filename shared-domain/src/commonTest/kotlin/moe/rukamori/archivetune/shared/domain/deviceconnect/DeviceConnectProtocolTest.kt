package moe.rukamori.archivetune.shared.domain.deviceconnect

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceConnectProtocolTest {
    @Test
    fun plainFrameRoundTrips() {
        val payload = """{"role":"initiator"}""".encodeToByteArray()
        val bytes = DeviceFrameCodec.encode(DeviceMessageType.HELLO_COMMIT, 1, payload)
        val frame = assertNotNull(DeviceFrameCodec.decode(bytes))
        assertEquals(DeviceMessageType.HELLO_COMMIT, frame.header.type)
        assertEquals(1, frame.header.seq)
        assertFalse(frame.header.encrypted)
        assertNull(frame.nonce)
        assertContentEquals(payload, frame.payload)
    }

    @Test
    fun encryptedFrameCarriesNonceAndCiphertext() {
        val nonce = ByteArray(12) { it.toByte() }
        val ciphertext = ByteArray(32) { (it * 3).toByte() }
        val bytes = DeviceFrameCodec.encode(DeviceMessageType.PLAYBACK, 42, ciphertext, nonce)
        val frame = assertNotNull(DeviceFrameCodec.decode(bytes))
        assertTrue(frame.header.encrypted)
        assertEquals(42, frame.header.seq)
        assertContentEquals(nonce, frame.nonce)
        assertContentEquals(ciphertext, frame.payload)
    }

    @Test
    fun headerIsTheAadPrefixAndBindsTranscript() {
        val header = DeviceFrameHeader(1, DeviceMessageType.PLAYBACK, DeviceFrameCodec.FLAG_ENCRYPTED, 0x01020304)
        val aad = header.aad(ByteArray(32) { 7 })
        assertEquals(7 + 32, aad.size)
        assertEquals(1, aad[0].toInt())
        assertEquals(DeviceMessageType.PLAYBACK.code, aad[1].toInt())
        assertEquals(0x01020304, ((aad[3].toInt() and 0xff) shl 24) or ((aad[4].toInt() and 0xff) shl 16) or
            ((aad[5].toInt() and 0xff) shl 8) or (aad[6].toInt() and 0xff))
        assertEquals(7, aad[7].toInt())
    }

    @Test
    fun decodeRejectsBadVersionTypeFlagsAndSeq() {
        val valid = DeviceFrameCodec.encode(DeviceMessageType.PING, 2, ByteArray(0))
        val badVersion = valid.copyOf().also { it[0] = 2 }
        assertNull(DeviceFrameCodec.decode(badVersion))
        val badType = valid.copyOf().also { it[1] = 99 }
        assertNull(DeviceFrameCodec.decode(badType))
        val badFlags = valid.copyOf().also { it[2] = 8 }
        assertNull(DeviceFrameCodec.decode(badFlags))
        val zeroSeq = valid.copyOf().also { for (i in 3..6) it[i] = 0 }
        assertNull(DeviceFrameCodec.decode(zeroSeq))
    }

    @Test
    fun encodeRefusesFramesAboveTheCap() {
        assertFailsWith<IllegalArgumentException> {
            DeviceFrameCodec.encode(DeviceMessageType.PLAYBACK, 1, ByteArray(DeviceConnectLimits.MAX_FRAME_BYTES))
        }
    }

    @Test
    fun decodeRejectsEncryptedFrameWithoutTag() {
        val nonce = ByteArray(12)
        val bytes = DeviceFrameCodec.encode(DeviceMessageType.PLAYBACK, 3, ByteArray(8), nonce)
        assertNull(DeviceFrameCodec.decode(bytes))
    }

    @Test
    fun jsonToleratesUnknownFields() {
        val decoded = DeviceWireJson.json.decodeFromString(
            HelloPayload.serializer(),
            """{"role":"initiator","deviceId":"0123456789abcdef","nonce":"00","publicKey":"00","future":true}""",
        )
        assertEquals("initiator", decoded.role)
        assertTrue(decoded.version == DeviceConnectLimits.PROTOCOL_VERSION)
    }
}

class ReplayWindowTest {
    @Test
    fun acceptsInOrderAndRejectsDuplicates() {
        val window = DeviceReplayWindow()
        assertTrue(window.accept(1))
        assertTrue(window.accept(2))
        assertFalse(window.accept(2))
        assertFalse(window.accept(1))
        assertTrue(window.accept(3))
    }

    @Test
    fun acceptsOlderFramesInsideTheWindowOnce() {
        val window = DeviceReplayWindow()
        assertTrue(window.accept(10))
        assertTrue(window.accept(9))
        assertFalse(window.accept(9))
        assertTrue(window.accept(8))
    }

    @Test
    fun dropsFramesOlderThanTheWindowAndHandlesJumps() {
        val window = DeviceReplayWindow(size = 8)
        assertTrue(window.accept(1))
        assertTrue(window.accept(100))
        assertFalse(window.accept(92))
        assertTrue(window.accept(99))
        assertFalse(window.accept(0))
    }
}

class DeviceConnectLimitsTest {
    @Test
    fun privateAndPublicHostsAreClassified() {
        assertTrue(DeviceConnectHosts.isPrivateOrLocal("192.168.1.10"))
        assertTrue(DeviceConnectHosts.isPrivateOrLocal("10.0.0.2"))
        assertTrue(DeviceConnectHosts.isPrivateOrLocal("172.16.0.9"))
        assertTrue(DeviceConnectHosts.isPrivateOrLocal("127.0.0.1"))
        assertTrue(DeviceConnectHosts.isPrivateOrLocal("fe80::1"))
        assertTrue(DeviceConnectHosts.isPrivateOrLocal("fd00::1"))
        assertTrue(DeviceConnectHosts.isPrivateOrLocal("speaker.local"))
        assertFalse(DeviceConnectHosts.isPrivateOrLocal("8.8.8.8"))
        assertFalse(DeviceConnectHosts.isPrivateOrLocal("example.com"))
        assertFalse(DeviceConnectHosts.isPrivateOrLocal("172.32.0.1"))
    }

    @Test
    fun manualEndpointParsesIpv4Ipv6AndRejectsPublicHosts() {
        assertEquals(DeviceEndpoint("192.168.0.5", 8080), DeviceConnectHosts.parseManualEndpoint("192.168.0.5:8080"))
        assertEquals(DeviceEndpoint("fe80::1", 9000), DeviceConnectHosts.parseManualEndpoint("[fe80::1]:9000"))
        assertEquals(DeviceEndpoint("127.0.0.1", 1234), DeviceConnectHosts.parseManualEndpoint("127.0.0.1:1234"))
        assertNull(DeviceConnectHosts.parseManualEndpoint("8.8.8.8:80"))
        assertNull(DeviceConnectHosts.parseManualEndpoint("192.168.0.5"))
        assertNull(DeviceConnectHosts.parseManualEndpoint("192.168.0.5:0"))
        assertNull(DeviceConnectHosts.parseManualEndpoint("[fe80::1]"))
    }

    @Test
    fun nameNormalisationStripsControlsAndCapsLength() {
        assertEquals("Living Room", DeviceConnectHosts.normalizeDeviceName("  Living Room  "))
        assertEquals(48, DeviceConnectHosts.normalizeDeviceName("x".repeat(80)).length)
        assertEquals("Natune", DeviceConnectHosts.normalizeDeviceName("\u0000"))
    }

    @Test
    fun queueHashChangesWithContentButNotWithFormattingFields() {
        val one = entry("a", "youtube", "1", "One")
        val same = entry("a", "youtube", "1", "One").copy(subtitle = "artist")
        val two = entry("a", "youtube", "1", "Two")
        assertEquals(DeviceQueueHash.of(listOf(one)), DeviceQueueHash.of(listOf(same)))
        assertTrue(DeviceQueueHash.of(listOf(one)) != DeviceQueueHash.of(listOf(two)))
        assertFalse(DeviceQueueHash.shareable(listOf(one.copy(provider = "local"))))
        assertTrue(DeviceQueueHash.shareable(listOf(one)))
    }

    private fun entry(occurrence: String, provider: String, id: String, title: String) =
        DeviceQueueEntry(occurrence, provider, "SONG", id, title)
}

class DeviceConnectStateMachineTest {
    @Test
    fun helloIsAcceptedOnceAndOnlyBeforeAuthentication() {
        val state = DeviceHandshakeState(DeviceConnectRole.INITIATOR)
        assertTrue(state.allowsPlainText(DeviceMessageType.HELLO_COMMIT))
        assertFalse(state.allowsPlainText(DeviceMessageType.PLAYBACK))
        assertTrue(state.onHelloCommit())
        assertFalse(state.onHelloCommit())
        assertTrue(state.allowsPlainText(DeviceMessageType.HELLO))
        assertTrue(state.onHello("0123456789abcdef"))
        assertFalse(state.onHello("fedcba9876543210"))
        assertFalse(state.allowsPlainText(DeviceMessageType.HELLO))
        assertTrue(state.awaitingPairApproval(1_000))
        assertTrue(state.awaitingPairCommit())
        assertTrue(state.awaitingPairApproval(0).not())
        state.authenticated()
        assertFalse(state.allowsPlainText(DeviceMessageType.HELLO_COMMIT))
    }

    @Test
    fun pairingDeadlineIsFiveMinutes() {
        val state = DeviceHandshakeState(DeviceConnectRole.RESPONDER)
        state.onHelloCommit()
        state.onHello("0123456789abcdef")
        state.awaitingPairApproval(1_000)
        assertFalse(state.pairingExpired(1_000 + DeviceConnectLimits.PAIRING_TIMEOUT_MS))
        assertTrue(state.pairingExpired(1_001 + DeviceConnectLimits.PAIRING_TIMEOUT_MS))
    }

    @Test
    fun nothingButTheHelloTravelsInTheClear() {
        val state = DeviceHandshakeState(DeviceConnectRole.INITIATOR)
        assertTrue(state.allowsPlainText(DeviceMessageType.HELLO_COMMIT))
        state.onHelloCommit()
        assertTrue(state.allowsPlainText(DeviceMessageType.HELLO))
        state.onHello("0123456789abcdef")
        val forbidden = listOf(
            DeviceMessageType.HELLO,
            DeviceMessageType.AUTH_PROOF,
            DeviceMessageType.PAIR_REQUEST,
            DeviceMessageType.PAIR_APPROVE,
            DeviceMessageType.PAIR_COMMIT,
            DeviceMessageType.AUTH_RESULT,
            DeviceMessageType.DEVICE_INFO,
            DeviceMessageType.PLAYBACK,
            DeviceMessageType.PLAYBACK_CHUNK,
            DeviceMessageType.PING,
            DeviceMessageType.ERROR,
        )
        for (type in forbidden) assertFalse(state.allowsPlainText(type), "$type must be sealed")
    }

    @Test
    fun theInitiatorAcceptsTheAuthResultOnlyAfterItsOwnCommit() {
        val state = DeviceHandshakeState(DeviceConnectRole.INITIATOR)
        state.onHelloCommit()
        state.onHello("0123456789abcdef")
        // The key exchange alone must not be enough: no pairing step has happened yet.
        assertTrue(state.canAuthenticate())
        assertFalse(state.allowsEncrypted(DeviceMessageType.AUTH_RESULT))
        assertFalse(state.canAcceptAuthResult())
        assertTrue(state.allowsEncrypted(DeviceMessageType.AUTH_PROOF))
        assertFalse(state.allowsEncrypted(DeviceMessageType.PAIR_REQUEST))
        assertFalse(state.allowsEncrypted(DeviceMessageType.PLAYBACK))
        assertTrue(state.awaitingPairApproval(1_000))
        assertTrue(state.canAcceptPairApprove())
        assertTrue(state.allowsEncrypted(DeviceMessageType.PAIR_APPROVE))
        // The responder's stored-grant proof can still arrive after this side asked to pair; the frame
        // is allowed through and ignored, and this phase can never authenticate, so it proves nothing.
        assertTrue(state.allowsEncrypted(DeviceMessageType.AUTH_PROOF))
        assertTrue(state.canAcceptAuthProof())
        assertFalse(state.canAuthenticate())
        assertFalse(state.allowsEncrypted(DeviceMessageType.PAIR_COMMIT))
        assertFalse(state.allowsEncrypted(DeviceMessageType.AUTH_RESULT))
        assertTrue(state.awaitingPairCommit())
        assertTrue(state.canAcceptAuthResult())
        assertTrue(state.allowsEncrypted(DeviceMessageType.AUTH_RESULT))
        assertFalse(state.canAcceptPairCommit())
        state.authenticated()
        assertTrue(state.allowsEncrypted(DeviceMessageType.PLAYBACK))
        assertTrue(state.allowsEncrypted(DeviceMessageType.PING))
        assertFalse(state.allowsEncrypted(DeviceMessageType.AUTH_RESULT))
        assertFalse(state.allowsEncrypted(DeviceMessageType.AUTH_PROOF))
        assertFalse(state.allowsPlainText(DeviceMessageType.PLAYBACK))
    }

    @Test
    fun theResponderAcceptsOnlyTheCommitItAskedFor() {
        val state = DeviceHandshakeState(DeviceConnectRole.RESPONDER)
        state.onHelloCommit()
        state.onHello("0123456789abcdef")
        assertTrue(state.allowsEncrypted(DeviceMessageType.PAIR_REQUEST))
        assertFalse(state.allowsEncrypted(DeviceMessageType.PAIR_COMMIT))
        assertFalse(state.canAcceptPairCommit())
        assertFalse(state.canAcceptAuthResult())
        assertTrue(state.awaitingPairApproval(1_000))
        assertFalse(state.allowsEncrypted(DeviceMessageType.PAIR_APPROVE))
        assertTrue(state.allowsEncrypted(DeviceMessageType.PAIR_REJECT))
        // Only the initiator tolerates a late stored-grant proof; the responder never asked to pair.
        assertFalse(state.allowsEncrypted(DeviceMessageType.AUTH_PROOF))
        assertFalse(state.canAcceptAuthProof())
        assertFalse(state.allowsEncrypted(DeviceMessageType.AUTH_RESULT))
        assertTrue(state.awaitingPairCommit())
        assertTrue(state.canAcceptPairCommit())
        assertFalse(state.canAcceptAuthResult())
        assertTrue(state.allowsEncrypted(DeviceMessageType.PAIR_COMMIT))
        assertFalse(state.allowsEncrypted(DeviceMessageType.PLAYBACK))
    }
}

class DeviceCommandPolicyTest {
    private val queueRevision = 7L
    private val trackRevision = 3L
    private val ids = setOf("a", "b")

    @Test
    fun staleQueueAndTrackAreTyped() {
        val staleQueue = DeviceCommandPolicy.validate(
            DeviceRemoteCommand(DevicePlaybackAction.PLAY, expectedQueueRevision = 6),
            "session", queueRevision, trackRevision, ids,
        )
        assertEquals(DeviceConnectErrorCode.STALE_QUEUE, staleQueue?.code)
        assertTrue(staleQueue!!.code.isStale)
        val staleTrack = DeviceCommandPolicy.validate(
            DeviceRemoteCommand(DevicePlaybackAction.PLAY, expectedTrackRevision = 2),
            "session", queueRevision, trackRevision, ids,
        )
        assertEquals(DeviceConnectErrorCode.STALE_TRACK, staleTrack?.code)
        assertNull(
            DeviceCommandPolicy.validate(
                DeviceRemoteCommand(DevicePlaybackAction.PLAY, expectedQueueRevision = 7, expectedTrackRevision = 3),
                "session", queueRevision, trackRevision, ids,
            ),
        )
    }

    @Test
    fun localFilesAndOversizedQueuesAreRefusedBeforeApply() {
        val local = DeviceQueueEntry("a", "local", "SONG", "1", "Local")
        val error = DeviceCommandPolicy.validate(
            DeviceRemoteCommand(DevicePlaybackAction.REPLACE_QUEUE, entries = listOf(local)),
            "session", queueRevision, trackRevision, ids,
        )
        assertEquals(DeviceConnectErrorCode.UNSHAREABLE_QUEUE, error?.code)
        val tooMany = List(DeviceConnectLimits.MAX_QUEUE_ENTRIES + 1) { i ->
            DeviceQueueEntry("o$i", "youtube", "SONG", "id$i", "Title")
        }
        val size = DeviceCommandPolicy.validate(
            DeviceRemoteCommand(DevicePlaybackAction.REPLACE_QUEUE, entries = tooMany),
            "session", queueRevision, trackRevision, ids,
        )
        assertEquals(DeviceConnectErrorCode.TOO_LARGE, size?.code)
    }

    @Test
    fun commandIntentMapsOntoQueueAndPlayerActions() {
        val playOrder = listOf("a", "b")
        assertEquals(DeviceCommandIntent.Play, DeviceCommandPolicy.intent(
            DeviceRemoteCommand(DevicePlaybackAction.PLAY), playOrder, "a", moe.rukamori.archivetune.shared.domain.queue.QueueRepeatMode.OFF,
        ))
        assertEquals(
            DeviceCommandIntent.Select("b", 0),
            DeviceCommandPolicy.intent(
                DeviceRemoteCommand(DevicePlaybackAction.NEXT), playOrder, "a", moe.rukamori.archivetune.shared.domain.queue.QueueRepeatMode.OFF,
            ),
        )
        assertEquals(
            DeviceCommandIntent.Select("a", 0),
            DeviceCommandPolicy.intent(
                DeviceRemoteCommand(DevicePlaybackAction.PREVIOUS), playOrder, "b", moe.rukamori.archivetune.shared.domain.queue.QueueRepeatMode.OFF,
            ),
        )
        assertEquals(
            DeviceCommandIntent.Seek(1200),
            DeviceCommandPolicy.intent(
                DeviceRemoteCommand(DevicePlaybackAction.SEEK, positionMs = 1200), playOrder, "a", moe.rukamori.archivetune.shared.domain.queue.QueueRepeatMode.OFF,
            ),
        )
        assertEquals(
            DeviceCommandIntent.Volume(0.5f),
            DeviceCommandPolicy.intent(
                DeviceRemoteCommand(DevicePlaybackAction.VOLUME, volume = 0.5f), playOrder, "a", moe.rukamori.archivetune.shared.domain.queue.QueueRepeatMode.OFF,
            ),
        )
    }
}
