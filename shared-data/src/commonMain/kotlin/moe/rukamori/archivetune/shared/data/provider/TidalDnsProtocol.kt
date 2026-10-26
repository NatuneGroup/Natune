package moe.rukamori.archivetune.shared.data.provider

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The DNS-over-HTTPS protocol shared by every platform's `TidalDns`: the JSON DoH endpoints,
 * the hardcoded bootstrap provider IPs (so the DoH endpoints themselves never need a working
 * system resolver), the 5-minute cache TTL, and the answer filter (only A/AAAA records of the
 * queried type; CNAME and others skipped). The resolution order — cache, system, DoH, then a
 * stale cache entry, then failure — lives in each platform's `TidalDns` because address
 * resolution and the DoH transport are platform APIs (expect/actual boundary: DNS).
 */
object TidalDnsProtocol {
    const val CLOUDFLARE_DOH = "https://cloudflare-dns.com/dns-query"
    const val GOOGLE_DOH = "https://dns.google/resolve"

    /** Stable anycast addresses for the respective providers. */
    val BOOTSTRAP: Map<String, List<String>> = mapOf(
        "cloudflare-dns.com" to listOf("104.16.248.249", "104.16.249.249"),
        "dns.google" to listOf("8.8.8.8", "8.8.4.4"),
    )

    const val CACHE_TTL_MS = 5L * 60L * 1000L

    /** IPv4 (1) then IPv6 (28) record-type order used by every `resolveOverHttps` port. */
    val RECORD_TYPES = listOf(1, 28)

    /** Answers of exactly [type] out of an `application/dns-json` body; blank rows dropped. */
    fun parseAnswers(body: String, type: Int): List<String> = try {
        Json.parseToJsonElement(body).jsonObject["Answer"]?.jsonArray.orEmpty().mapNotNull { entry ->
            val obj = entry.jsonObject
            if (obj["type"]?.jsonPrimitive?.content?.toIntOrNull() != type) return@mapNotNull null
            obj["data"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        }
    } catch (e: Exception) {
        emptyList()
    }
}
