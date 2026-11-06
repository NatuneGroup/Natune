package moe.rukamori.archivetune.shared.domain.deviceconnect

import kotlinx.serialization.Serializable
import moe.rukamori.archivetune.shared.domain.crypto.HmacSha256
import moe.rukamori.archivetune.shared.domain.lyrics.ai.Sha256
import moe.rukamori.archivetune.shared.domain.queue.QueueRepeatMode
import moe.rukamori.archivetune.shared.domain.repository.ContentCredit
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.ContentRef

@Serializable
enum class DevicePlatform(val wire: String) {
    ANDROID("android"),
    DESKTOP("desktop"),
    IOS("ios"),
    UNKNOWN("unknown"),
    ;

    companion object {
        fun fromWire(value: String?): DevicePlatform = entries.firstOrNull { it.wire == value } ?: UNKNOWN
    }
}

@Serializable
enum class DeviceConnectRole(val wire: String) {
    INITIATOR("initiator"),
    RESPONDER("responder"),
    ;

    fun peer(): DeviceConnectRole = if (this == INITIATOR) RESPONDER else INITIATOR
}

/** A local identity. [installId] is random and never a hardware identifier. */
@Serializable
data class DeviceIdentity(
    val installId: String,
    val name: String,
    val platform: DevicePlatform,
    val model: String = "",
    val appVersion: String = "",
) {
    init {
        require(DeviceConnectHosts.isValidDeviceId(installId)) { "installId must be lowercase hex" }
        require(name.isNotBlank()) { "name must not be blank" }
    }
}

/** Non-secret facts about a paired peer. The pair secret itself never enters settings or a backup. */
@Serializable
data class DevicePairInfo(
    val peerId: String,
    val peerName: String,
    val peerPlatform: DevicePlatform,
    val peerModel: String = "",
    val accountScopeId: String? = null,
    val pairedAtEpochMs: Long = 0,
    val lastUsedAtEpochMs: Long = 0,
    /** Set when the local account identity changed; the secret stays until the user re-pairs or forgets. */
    val staleAccount: Boolean = false,
)

data class DevicePairGrant(
    val info: DevicePairInfo,
    val secret: ByteArray,
) {
    override fun toString(): String = "DevicePairGrant(${info.peerId}, REDACTED)"
}

enum class DevicePeerPresence {
    DISCOVERED,
    CONNECTING,
    PAIRED,
    AVAILABLE,
    PLAYING_REMOTE,
    OFFLINE,
}

data class DevicePeerState(
    val id: String,
    val name: String,
    val platform: DevicePlatform,
    val model: String = "",
    val presence: DevicePeerPresence = DevicePeerPresence.DISCOVERED,
    val endpoint: DeviceEndpoint? = null,
    val lastSeenEpochMs: Long = 0,
    val staleAccount: Boolean = false,
)

data class DevicePairingPrompt(
    val peerId: String,
    val peerName: String,
    val peerPlatform: DevicePlatform,
    val code: String,
    val expiresAtEpochMs: Long,
    /** True on the side that sent the request: it waits for the other user instead of showing an approve action. */
    val isRequester: Boolean = false,
)

@Serializable
enum class DeviceConnectErrorCode {
    CONFLICT,
    UNSHAREABLE_QUEUE,
    TOO_LARGE,
    STALE_QUEUE,
    STALE_TRACK,
    NOT_ALLOWED,
    UNAVAILABLE,
    INVALID_MESSAGE,
    BUSY,
    SESSION_GONE,
    SAS_MISMATCH,
    TIMEOUT,
    UNAUTHENTICATED,
    UNSUPPORTED_VERSION,
    ;

    /** A stale answer means the controller may re-pull and retry; it is not a terminal session failure. */
    val isStale: Boolean
        get() = this == STALE_QUEUE || this == STALE_TRACK
}

@Serializable
data class DeviceConnectError(
    val code: DeviceConnectErrorCode,
    val message: String = "",
)

/** The local player and queue as a transfer carries them. */
@Serializable
data class DevicePlaybackSnapshot(
    val sessionId: String = "",
    val queueRevision: Long = 0,
    val trackRevision: Long = 0,
    val stateRevision: Long = 0,
    val entries: List<DeviceQueueEntry> = emptyList(),
    val currentOccurrenceId: String? = null,
    val playOrder: List<String> = emptyList(),
    val shuffle: Boolean = false,
    val repeatMode: Int = QueueRepeatMode.OFF.storedValue,
    val playing: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long? = null,
    val speed: Float = 1f,
    val pitch: Float = 1f,
    val volume: Float? = null,
    val ready: Boolean = true,
    val finite: Boolean = true,
    val staysFinite: Boolean = false,
    val anchorEpochMs: Long = 0,
    val queueHash: String = "",
    val accounting: DevicePlaybackAccounting? = null,
) {
    fun repeatModeValue(): QueueRepeatMode = QueueRepeatMode.fromStoredValue(repeatMode)

    /** A shallow validity check before a peer applies untrusted state. */
    fun isStructurallyValid(): Boolean {
        if (positionMs < 0 || queueRevision < 0 || trackRevision < 0) return false
        if (entries.size > DeviceConnectLimits.MAX_QUEUE_ENTRIES) return false
        val ids = entries.map { it.occurrenceId }
        if (ids.any { it.isBlank() } || ids.toSet().size != ids.size) return false
        if (currentOccurrenceId != null && currentOccurrenceId !in ids) return false
        if (playOrder.isNotEmpty() && (playOrder.size != ids.size || playOrder.toSet() != ids.toSet())) return false
        if (!speed.isFinite() || speed <= 0f) return false
        return pitch.isFinite() && pitch > 0f
    }
}

/** Play accounting continues on the device that is audible, so a transfer never restarts a scrobble. */
@Serializable
data class DevicePlaybackAccounting(
    val songId: String,
    val startedAtEpochMs: Long,
    val accumulatedPlayedMs: Long,
    val completed: Boolean = false,
    val pending: Boolean = false,
)

@Serializable
data class DeviceQueueEntry(
    val occurrenceId: String,
    val provider: String,
    val kind: String,
    val id: String,
    val title: String,
    val subtitle: String = "",
    val artworkUri: String? = null,
    val durationMs: Long? = null,
    val explicit: Boolean = false,
    val album: String? = null,
    val albumId: String? = null,
    val isrc: String? = null,
    val isVideo: Boolean = false,
    val setVideoId: String? = null,
    val artists: List<DeviceArtist> = emptyList(),
)

@Serializable
data class DeviceArtist(val name: String, val id: String? = null)

fun ContentOccurrence.toDeviceQueueEntry(): DeviceQueueEntry = DeviceQueueEntry(
    occurrenceId = occurrenceId,
    provider = item.ref.provider,
    kind = item.ref.kind.name,
    id = item.ref.id,
    title = item.title,
    subtitle = item.subtitle,
    artworkUri = item.artworkUri,
    durationMs = item.durationMs,
    explicit = item.explicit,
    album = item.album,
    albumId = item.albumId,
    isrc = item.isrc,
    isVideo = item.isVideo,
    setVideoId = setVideoId,
    artists = item.artists.map { DeviceArtist(it.name, it.id) },
)

fun DeviceQueueEntry.toContentOccurrenceOrNull(): ContentOccurrence? {
    val contentKind = ContentKind.entries.firstOrNull { it.name == kind } ?: return null
    if (occurrenceId.isBlank() || provider.isBlank() || id.isBlank() || title.isBlank()) return null
    if (provider == "local") return null
    return ContentOccurrence(
        occurrenceId = occurrenceId,
        item = ContentItem(
            ref = ContentRef(provider, contentKind, id),
            title = title,
            subtitle = subtitle,
            artworkUri = artworkUri,
            durationMs = durationMs,
            explicit = explicit,
            album = album,
            artists = artists.map { ContentCredit(it.name, it.id) },
            albumId = albumId,
            isVideo = isVideo,
            isrc = isrc,
        ),
        setVideoId = setVideoId,
    )
}

/** Stable content hash over the occurrence wire fields, so a restart cannot make equal revisions look equal. */
object DeviceQueueHash {
    fun of(entries: List<DeviceQueueEntry>): String {
        val canonical = buildString {
            entries.forEach { entry ->
                append(entry.occurrenceId.length).append(':').append(entry.occurrenceId)
                append(entry.provider.length).append(':').append(entry.provider)
                append(entry.kind.length).append(':').append(entry.kind)
                append(entry.id.length).append(':').append(entry.id)
                append(entry.title.length).append(':').append(entry.title)
                append(entry.setVideoId.orEmpty().length).append(':').append(entry.setVideoId.orEmpty())
                append(';')
            }
        }
        return Sha256.hexDigest(canonical.encodeToByteArray())
    }

    fun shareable(entries: List<DeviceQueueEntry>): Boolean =
        entries.all { entry -> entry.provider != "local" && entry.id.isNotBlank() }
}

@Serializable
enum class DevicePlaybackAction {
    PLAY,
    PAUSE,
    SEEK,
    NEXT,
    PREVIOUS,
    SELECT,
    REPLACE_QUEUE,
    ADD,
    PLAY_NEXT,
    REMOVE,
    MOVE,
    CLEAR,
    SHUFFLE,
    SPEED,
    PITCH,
    VOLUME,
    REPEAT,
}

@Serializable
data class DeviceRemoteCommand(
    val action: DevicePlaybackAction,
    val occurrenceId: String? = null,
    val index: Int? = null,
    val toIndex: Int? = null,
    val positionMs: Long? = null,
    val enabled: Boolean? = null,
    val volume: Float? = null,
    val speed: Float? = null,
    val pitch: Float? = null,
    val repeatMode: Int? = null,
    val entries: List<DeviceQueueEntry> = emptyList(),
    val expectedTrackRevision: Long? = null,
    val expectedQueueRevision: Long? = null,
    val expectedSessionId: String? = null,
)

@Serializable
enum class DeviceSyncMessageType {
    ATTACH,
    TRANSFER,
    PULL,
    STATE,
    UPDATE,
    COMMAND,
    RELEASE,
    ERROR,
}

@Serializable
data class DeviceSyncMessage(
    val type: DeviceSyncMessageType,
    val sessionId: String? = null,
    val snapshot: DevicePlaybackSnapshot? = null,
    val command: DeviceRemoteCommand? = null,
    val revision: Long = 0,
    val queueRevision: Long = 0,
    val trackRevision: Long = 0,
    val positionMs: Long? = null,
    val playing: Boolean? = null,
    val error: DeviceConnectError? = null,
)

/** A locally observed session, in the shape both the owner and the UI need. */
data class DeviceSessionState(
    val sessionId: String,
    val targetId: String? = null,
    val targetName: String? = null,
    val controllerIds: Set<String> = emptySet(),
    val isController: Boolean = false,
    val isTarget: Boolean = false,
    val remoteState: DevicePlaybackSnapshot? = null,
    val error: DeviceConnectError? = null,
)

data class DeviceTransferRequest(
    val sessionId: String,
    val fromId: String,
    val fromName: String,
    val snapshot: DevicePlaybackSnapshot,
)

data class DeviceConnectState(
    val enabled: Boolean = false,
    val serverRunning: Boolean = false,
    val serverStarting: Boolean = false,
    val serverPort: Int? = null,
    val local: DeviceIdentity? = null,
    val peers: List<DevicePeerState> = emptyList(),
    val paired: List<DevicePairInfo> = emptyList(),
    val pairing: DevicePairingPrompt? = null,
    val session: DeviceSessionState? = null,
    val incomingTransfer: DeviceTransferRequest? = null,
    val error: DeviceConnectError? = null,
    val permissionRequired: Boolean = false,
) {
    fun peer(id: String): DevicePeerState? = peers.firstOrNull { it.id == id }

    fun pair(id: String): DevicePairInfo? = paired.firstOrNull { it.peerId == id }
}

/** The three playback modes Device Connect must not silently replace. */
data class DeviceConnectEnvironment(
    val casting: Boolean = false,
    val listenTogetherActive: Boolean = false,
) {
    val conflict: DeviceConnectErrorCode?
        get() = when {
            casting -> DeviceConnectErrorCode.CONFLICT
            listenTogetherActive -> DeviceConnectErrorCode.CONFLICT
            else -> null
        }
}

/** Pairing proofs are length-prefixed so no two phase/role/id combinations collide. */
object DeviceConnectProofs {
    fun proof(
        key: ByteArray,
        phase: String,
        senderRole: DeviceConnectRole,
        senderId: String,
        receiverId: String,
        transcript: ByteArray,
    ): ByteArray = HmacSha256.sign(key, lengthPrefixed(
        DeviceConnectLimits.PROTOCOL_LABEL,
        phase,
        senderRole.wire,
        senderId,
        receiverId,
        transcript.toHex(),
    ))

    fun matches(expected: ByteArray, actual: ByteArray): Boolean {
        if (expected.size != actual.size) return false
        var difference = 0
        for (i in expected.indices) difference = difference or (expected[i].toInt() xor actual[i].toInt())
        return difference == 0
    }
}

internal fun lengthPrefixed(vararg values: String): ByteArray {
    val encoded = values.map { it.encodeToByteArray() }
    val out = ByteArray(encoded.sumOf { 4 + it.size })
    var offset = 0
    encoded.forEach { bytes ->
        out[offset] = (bytes.size ushr 24).toByte()
        out[offset + 1] = (bytes.size ushr 16).toByte()
        out[offset + 2] = (bytes.size ushr 8).toByte()
        out[offset + 3] = bytes.size.toByte()
        offset += 4
        bytes.copyInto(out, offset)
        offset += bytes.size
    }
    return out
}

fun ByteArray.toHex(): String = joinToString(separator = "") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

fun String.hexToBytesOrNull(): ByteArray? {
    if (length % 2 != 0) return null
    val out = ByteArray(length / 2)
    for (i in out.indices) {
        val high = this[i * 2].digitToIntOrNull(16) ?: return null
        val low = this[i * 2 + 1].digitToIntOrNull(16) ?: return null
        out[i] = ((high shl 4) or low).toByte()
    }
    return out
}
