package moe.rukamori.archivetune.shared.data.lyrics

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

@Serializable
internal data class NeteaseSearchResponse(
    val result: NeteaseSearchResult? = null,
)

@Serializable
internal data class NeteaseSearchResult(
    val songs: List<NeteaseSong> = emptyList(),
)

@Serializable
internal data class NeteaseSong(
    val id: Long = 0,
    val name: String? = null,
    val artists: List<NeteaseArtist> = emptyList(),
    val duration: Int = 0,
)

@Serializable
internal data class NeteaseArtist(
    val name: String,
)

@Serializable
internal data class PaxsenixSearchItem(
    val id: String? = null,
    @SerialName("trackId") val trackId: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val name: String? = null,
    val songName: String? = null,
    val artistName: String? = null,
    val duration: JsonElement? = null,
) {
    /** Some providers send the id as `trackId` instead of `id`. */
    val realId: String
        get() = id ?: trackId ?: ""

    /**
     * Milliseconds from a number, or from a `MM:SS` / `HH:MM:SS` string. Anything else reads as 0,
     * which the duration match treats as unknown rather than as a mismatch.
     */
    val durationMs: Long
        get() {
            val primitive = duration as? JsonPrimitive ?: return 0L
            primitive.longOrNull?.let { return it }
            val parts = primitive.content.trim().split(":")
            if (parts.size < 2) return 0L
            val seconds = parts.last().toLongOrNull() ?: 0L
            val minutes = parts[parts.size - 2].toLongOrNull() ?: 0L
            val hours = if (parts.size >= 3) parts[parts.size - 3].toLongOrNull() ?: 0L else 0L
            return (hours * 3600 + minutes * 60 + seconds) * 1000
        }
}

@Serializable
internal data class AppleMusicLyricsResponse(
    val type: String? = null,
    val content: List<AppleMusicLine> = emptyList(),
)

@Serializable
internal data class AppleMusicLine(
    val timestamp: Long = 0,
    val text: List<AppleMusicWord> = emptyList(),
)

@Serializable
internal data class AppleMusicWord(
    val text: String,
    val timestamp: Long? = null,
)

/** The `api/stats` diagnostics payload, public because [PaxsenixDiagnostics.getStats] returns it. */
@Serializable
data class PaxsenixStats(
    @SerialName("uptime_seconds") val uptimeSeconds: Double,
    @SerialName("started_at") val startedAt: String,
    @SerialName("total_requests") val totalRequests: Int,
    @SerialName("successful_requests") val successfulRequests: Int,
    @SerialName("failed_requests") val failedRequests: Int,
    @SerialName("overall_success_rate") val overallSuccessRate: String,
    val endpoints: Map<String, EndpointStats> = emptyMap(),
    val providers: Map<String, ProviderStats> = emptyMap(),
    @SerialName("request_log") val requestLog: List<RequestLogEntry> = emptyList(),
)

@Serializable
data class EndpointStats(
    val hits: Int,
    val errors: Int,
    @SerialName("success_rate") val successRate: String,
    @SerialName("avg_response_time_ms") val avgResponseTimeMs: Double,
    @SerialName("last_accessed") val lastAccessed: String,
)

@Serializable
data class ProviderStats(
    val hits: Int,
    val errors: Int,
    @SerialName("success_rate") val successRate: String,
)

@Serializable
data class RequestLogEntry(
    val timestamp: String,
    val endpoint: String,
    val provider: String,
    val success: Boolean,
    @SerialName("response_time_ms") val responseTimeMs: Double,
    val ip: String,
    @SerialName("user_agent") val userAgent: String,
)
