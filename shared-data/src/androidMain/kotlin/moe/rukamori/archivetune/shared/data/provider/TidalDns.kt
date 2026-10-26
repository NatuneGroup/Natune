package moe.rukamori.archivetune.shared.data.provider

import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * DNS resolver that falls back to DNS-over-HTTPS (Cloudflare / Google) when the system resolver
 * can't resolve a host — exact port of the original's `tidal/TidalDns.kt`. This is what lets
 * TIDAL work on networks/ISPs that DNS-block `tidal.com` / `auth.tidal.com`. The DoH providers
 * are reached through the bootstrap resolver, so they never depend on the system DNS.
 * Installed per-client by the host (`.dns(TidalDns)` on the tidal OkHttp/Ktor client).
 */
object TidalDns : Dns {
    private class CacheEntry(val addresses: List<InetAddress>, val expiresAt: Long)

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    /** Static bootstrap resolver used ONLY to reach the DoH provider hostnames. */
    private val bootstrapDns = Dns { hostname ->
        TidalDnsProtocol.BOOTSTRAP[hostname]
            ?.mapNotNull { ip -> runCatching { InetAddress.getByName(ip) }.getOrNull() }
            ?: Dns.SYSTEM.lookup(hostname)
    }

    // Plain client for the DoH endpoints. Uses the bootstrap resolver (never TidalDns) to avoid
    // both infinite recursion and any dependency on the system DNS.
    private val dohClient = OkHttpClient.Builder()
        .dns(bootstrapDns)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .build()

    override fun lookup(hostname: String): List<InetAddress> {
        // Serve from cache first (covers brief network drops during playback).
        cache[hostname]?.let { entry ->
            if (entry.expiresAt > System.currentTimeMillis() && entry.addresses.isNotEmpty()) {
                return entry.addresses
            }
        }

        // Prefer the system resolver; it's faster and respects local/VPN DNS when it works.
        val system = runCatching { Dns.SYSTEM.lookup(hostname) }.getOrDefault(emptyList())
        if (system.isNotEmpty()) {
            cache[hostname] = CacheEntry(system, System.currentTimeMillis() + CACHE_TTL_MS)
            return system
        }

        // System DNS returned nothing (blocked / EAI_NODATA). Fall back to DoH.
        val viaDoh = resolveOverHttps(hostname)
        if (viaDoh.isNotEmpty()) {
            cache[hostname] = CacheEntry(viaDoh, System.currentTimeMillis() + CACHE_TTL_MS)
            return viaDoh
        }

        // Last resort: if DoH also failed but we have a stale cache entry, use it rather than crash.
        cache[hostname]?.addresses?.takeIf { it.isNotEmpty() }?.let { return it }
        throw UnknownHostException("Unable to resolve host \"$hostname\" via system DNS or DoH")
    }

    private fun resolveOverHttps(hostname: String): List<InetAddress> {
        // Query A (IPv4) first, then AAAA (IPv6) only if needed.
        val a = queryDoh(hostname, type = 1)
        val aaaa = if (a.isEmpty()) queryDoh(hostname, type = 28) else emptyList()
        return (a + aaaa).mapNotNull { ip ->
            // getByName on an IP literal does not perform a DNS lookup, so this is safe/offline.
            runCatching { InetAddress.getByName(ip) }.getOrNull()
        }
    }

    private fun queryDoh(hostname: String, type: Int): List<String> {
        for (endpoint in listOf(TidalDnsProtocol.CLOUDFLARE_DOH, TidalDnsProtocol.GOOGLE_DOH)) {
            val result = runCatching {
                val request = Request.Builder()
                    .url("$endpoint?name=$hostname&type=$type")
                    .header("Accept", "application/dns-json")
                    .get()
                    .build()
                dohClient.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful || body.isBlank()) emptyList()
                    else TidalDnsProtocol.parseAnswers(body, type)
                }
            }.getOrElse { emptyList() }
            if (result.isNotEmpty()) return result
        }
        return emptyList()
    }

    private const val CACHE_TTL_MS = TidalDnsProtocol.CACHE_TTL_MS
}
