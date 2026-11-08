package moe.rukamori.archivetune.desktop

import java.net.InetAddress
import moe.rukamori.archivetune.innertube.YouTube
import moe.rukamori.archivetune.shared.domain.settings.*
import okhttp3.Dns

/** Android's existing DoH factory and provider choices, read dynamically by the desktop HTTP client. */
internal class DesktopDns(private val preferences: () -> PreferenceSnapshot?) : Dns {
    private var selectedUrl: String? = null
    private var selected: Dns = Dns.SYSTEM
    @Synchronized private fun resolver(): Dns {
        val prefs = preferences()
        val provider = prefs?.get(DnsOverHttpsProviderKey, "Cloudflare")
        val url = if (prefs?.get(EnableDnsOverHttpsKey, false) != true) null else when (provider) {
            "Google" -> "https://dns.google/dns-query"
            "Cloudflare" -> "https://cloudflare-dns.com/dns-query"
            "AdGuard" -> "https://dns.adguard.com/dns-query"
            "Quad9" -> "https://dns.quad9.net/dns-query"
            "Custom" -> prefs[StringPreferenceKey("customDnsUrl")]
            else -> null
        }?.takeIf { it.startsWith("https://") }
        if (url != selectedUrl) {
            selected = url?.let { YouTube.createDnsOverHttps(it) } ?: Dns.SYSTEM
            selectedUrl = url
        }
        YouTube.dns = selected
        return selected
    }
    override fun lookup(hostname: String): List<InetAddress> = resolver().lookup(hostname)
    fun apply() { resolver() }
}
