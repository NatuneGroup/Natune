package moe.rukamori.archivetune.shared.data.deviceconnect

import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceFrame
import moe.rukamori.archivetune.shared.domain.deviceconnect.DeviceFrameCodec

/** One framed, ordered byte channel. Ktor supplies the real one; tests use an in-memory pair. */
interface DeviceFrameChannel {
    val frames: Flow<DeviceFrame>

    suspend fun send(bytes: ByteArray)

    suspend fun close(reason: String = "")
}

/** Wraps either a client or a server WebSocket as the same binary-frame channel. */
class KtorDeviceChannel(private val session: WebSocketSession) : DeviceFrameChannel {
    override val frames: Flow<DeviceFrame> = flow {
        for (frame in session.incoming) {
            when (frame) {
                is Frame.Binary -> DeviceFrameCodec.decode(frame.data)?.let { emit(it) }
                is Frame.Close -> break
                else -> Unit
            }
        }
    }

    override suspend fun send(bytes: ByteArray) {
        session.outgoing.send(Frame.Binary(true, bytes))
    }

    override suspend fun close(reason: String) {
        runCatching {
            session.outgoing.send(Frame.Close(CloseReason(CloseReason.Codes.NORMAL, reason)))
        }
        runCatching { session.outgoing.close() }
    }
}
