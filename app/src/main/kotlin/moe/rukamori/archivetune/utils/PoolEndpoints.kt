package moe.rukamori.archivetune.utils

import moe.rukamori.archivetune.BuildConfig
import moe.rukamori.archivetune.shared.data.provider.ArchivePoolEndpoints
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * OkHttp transport for the ordered ArchivePool deployments shared-data's [ArchivePoolEndpoints]
 * computes: the build-configured deployment first, the public vercel.app fallback after it, and
 * the deployment that last produced a definitive API answer first while that memory is fresh
 * (~10 minutes). A call moves to the next endpoint on a transport failure (connection refused,
 * DNS failure, timeout) or a host-level status (402/5xx); API-level answers like 400/401/403/
 * 404/429 are definitive and stop the walk. A report advances only on an explicit 402/5xx answer:
 * a transport failure may mean the report already reached the server, so a transport failure
 * stops its walk and a report is never delivered twice. Nothing here logs a token.
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
    ): Pair<Int, String>? = send(client, path, readKey, body = null)

    /**
     * POSTs [body] to a report endpoint (/api/report) against each candidate deployment. Only an
     * explicit 402/5xx answer moves the report to the next endpoint — that proves it was never
     * processed; a transport failure or a 2xx/4xx answer stops the walk so it is never sent twice.
     */
    suspend fun postReport(
        client: OkHttpClient,
        path: String,
        readKey: String,
        body: String,
    ): Pair<Int, String>? = send(client, path, readKey, body)

    private suspend fun send(
        client: OkHttpClient,
        path: String,
        readKey: String,
        body: String?,
    ): Pair<Int, String>? =
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
                if (body != null) {
                    builder.post(body.toRequestBody(JSON_MEDIA))
                }
                client.newCall(builder.build()).execute().use { response ->
                    response.code to if (response.isSuccessful) response.body?.string().orEmpty() else ""
                }
            } catch (_: Exception) {
                null
            }
        }

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaTypeOrNull()
}
