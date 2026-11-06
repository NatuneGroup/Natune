package moe.rukamori.archivetune.utils

import moe.rukamori.archivetune.BuildConfig
import moe.rukamori.archivetune.shared.data.provider.ArchivePoolEndpoints
import moe.rukamori.archivetune.shared.data.provider.PoolReportBudget
import moe.rukamori.archivetune.shared.data.provider.PoolResponse
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * OkHttp transport for the ArchivePool deployments shared-data's [ArchivePoolEndpoints] orders:
 * the build-configured deployment first, led by the deployment that last produced a definitive
 * answer while that memory is fresh (~10 minutes). A call moves to the next endpoint on a transport
 * failure (connection refused, DNS failure, timeout) or a host-level status (402, or a 5xx other
 * than 503); API-level answers — 400/401/403/404 and also 429/503 — are definitive and stop the
 * walk, carrying their `Retry-After` back to the caller. A report advances only on an explicit 402
 * or failover 5xx answer: a transport failure may mean the report already reached the server, so a
 * transport failure stops its walk and a report is never delivered twice.
 *
 * GETs revalidate: the `ETag` of the last body is sent as `If-None-Match` (persisted, so a relaunch
 * revalidates too) and a `304` comes back as one, which the caller answers from its own cache.
 * Nothing here logs a token.
 */
internal object PoolEndpoints {

    /**
     * The build-configured pool deployment: the dedicated ARCHIVEPOOL_BASE_URL build value when
     * set and https, else the legacy SOURCE_PROVIDER_URL build value, else the public default.
     */
    fun configuredBaseUrl(): String =
        ArchivePoolEndpoints.primary(
            listOf(BuildConfig.ARCHIVEPOOL_BASE_URL, BuildConfig.SOURCE_PROVIDER_URL)
                .firstOrNull { it.trim().startsWith("https://") },
        )

    /** The deduplicated deployments one request walks, remembered endpoint first when fresh. */
    fun bases(): List<String> = ArchivePoolEndpoints.candidates(configuredBaseUrl())

    /** GETs [path] against each candidate deployment until one answers definitively. */
    suspend fun get(
        client: OkHttpClient,
        path: String,
        readKey: String,
    ): PoolResponse? {
        val reply = send(client, path, readKey, body = null, ifNoneMatch = PoolDiscovery.feedEtag(path))
        if (reply?.status == 200) PoolDiscovery.rememberFeedEtag(path, reply.etag)
        return reply
    }

    /**
     * POSTs [body] to a report endpoint (/api/report) against each candidate deployment. Only an
     * explicit 402/failover-5xx answer moves the report to the next endpoint — that proves it was
     * never processed; a transport failure or a 2xx/4xx answer stops the walk so it is never sent
     * twice. A 429/503 is the pool refusing the report, which is never retried.
     */
    suspend fun postReport(
        client: OkHttpClient,
        path: String,
        readKey: String,
        body: String,
    ): PoolResponse? {
        // Under the server's own 20-per-5-minutes report bucket: the excess is dropped, not queued.
        if (!reportBudget.allow()) return null
        return send(client, path, readKey, body)
    }

    private suspend fun send(
        client: OkHttpClient,
        path: String,
        readKey: String,
        body: String?,
        ifNoneMatch: String? = null,
    ): PoolResponse? =
        ArchivePoolEndpoints.acrossEndpoints(configuredBaseUrl(), report = body != null) { base ->
            try {
                val builder =
                    Request
                        .Builder()
                        .url("$base$path")
                        .header("User-Agent", "ArchiveTune-Android")
                        // v2 of the pool protocol: sensitive fields are encrypted with a key derived
                        // from the read key; older servers ignore the header.
                        .header("X-Pool-Client", "v2")
                if (readKey.isNotBlank()) {
                    builder.header("Authorization", "Bearer $readKey")
                }
                if (ifNoneMatch != null && body == null) {
                    builder.header("If-None-Match", ifNoneMatch)
                }
                if (body != null) {
                    builder.post(body.toRequestBody(JSON_MEDIA))
                }
                client.newCall(builder.build()).execute().use { response ->
                    PoolResponse(
                        status = response.code,
                        body = if (response.code == 200) response.body?.string().orEmpty() else "",
                        retryAfterMs = PoolResponse.retryAfterMs(response.header("Retry-After")),
                        etag = response.header("ETag"),
                    )
                }
            } catch (_: Exception) {
                null
            }
        }

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaTypeOrNull()

    private val reportBudget = PoolReportBudget(nowMs = System::currentTimeMillis)
}
