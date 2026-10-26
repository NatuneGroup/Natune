package moe.rukamori.archivetune.shared.data.lyrics

import io.ktor.client.HttpClient
import io.ktor.client.request.parameter
import kotlinx.serialization.decodeFromString
import kotlin.coroutines.cancellation.CancellationException

/**
 * Port of the Android Paxsenix endpoint probe and stats call.
 *
 * The probe asks one question per provider: does this endpoint still serve the first path that
 * provider's lyrics lookup uses? It makes exactly one request per provider and never tries a second
 * path. A miss is reported as is, not retried on a sibling path, because the Android probe did not
 * do that and a retry would hide a retired route. The query values are throwaways, so the probe
 * asks "is this route served", not "can it find this song".
 */
class PaxsenixDiagnostics(
    client: HttpClient,
    settings: PaxsenixSettings = PaxsenixSettings(),
) {
    private val endpoint = PaxsenixEndpoint(client, settings)

    /** Outcome of probing one provider path on the configured endpoint. */
    enum class PathStatus {
        /** The endpoint serves this path: a 2xx, or a 4xx that only rejects the throwaway query. */
        AVAILABLE,

        /** The endpoint answered and refuses this path outright; upstream retired it. */
        RETIRED,

        /** No usable answer: a connection failure, a timeout, or a server-side error. */
        UNREACHABLE,
    }

    data class PathCheck(
        /** Human-readable provider name, for example "Apple Music". */
        val provider: String,
        /** Path relative to the configured endpoint, for example "netease/search". */
        val path: String,
        val status: PathStatus,
        /** HTTP status code, or null when the request never completed. */
        val httpCode: Int?,
    )

    /**
     * Probes each provider's path in the order shown to the user. A 403 is read as "retired", which is
     * how the public service signals a removed route. Any other 4xx means the route exists and only
     * disliked the throwaway query. Failures are reported as [PathStatus.UNREACHABLE], and cancellation
     * propagates.
     */
    suspend fun checkProviderPaths(): List<PathCheck> = PROBES.map { probe -> probeOne(probe) }

    private suspend fun probeOne(probe: Probe): PathCheck =
        try {
            val status =
                endpoint
                    .get(probe.path) { parameter(probe.paramName, probe.paramValue) }
                    .status
            PathCheck(probe.provider, probe.path, outcomeFor(status), status)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            PathCheck(probe.provider, probe.path, PathStatus.UNREACHABLE, null)
        }

    private fun outcomeFor(status: Int): PathStatus =
        when {
            status == FORBIDDEN -> PathStatus.RETIRED
            status >= SERVER_ERROR_FLOOR -> PathStatus.UNREACHABLE
            else -> PathStatus.AVAILABLE
        }

    /** The `api/stats` diagnostics payload. Any non-200 answer is a failure, not a decode attempt. */
    suspend fun getStats(): Result<PaxsenixStats> =
        suspendRunCatching {
            val response = endpoint.get("api/stats")
            if (response.status != OK_STATUS) throw LyricsHttpStatusException(response.status)
            PaxsenixJson.decodeFromString<PaxsenixStats>(response.requireBody())
        }

    private class Probe(
        val provider: String,
        val path: String,
        val paramName: String,
        val paramValue: String,
    )

    private companion object {
        const val FORBIDDEN = 403
        const val SERVER_ERROR_FLOOR = 500

        // Ordered as the user sees them in settings. Each names the first path its lookup uses.
        val PROBES =
            listOf(
                Probe("Apple Music", "apple-music/lyrics", "id", "1440857781"),
                Probe("NetEase", "netease/search", "q", "test"),
                Probe("Spotify", "spotify/search", "q", "test"),
                Probe("YouTube", "youtube/search", "q", "test"),
                Probe("Musixmatch", "musixmatch/lyrics", "q", "test"),
            )
    }
}
