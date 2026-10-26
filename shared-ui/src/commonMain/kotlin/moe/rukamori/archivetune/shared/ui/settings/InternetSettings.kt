/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CancellationException
import moe.rukamori.archivetune.shared.domain.settings.*
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.stringResource

/** Sanitized UI projection; the transport must not supply raw responses, proxy URLs or credentials. */
enum class ProxyConnectionResult { CONNECTED, INVALID_CONFIGURATION, AUTHENTICATION, TIMEOUT, DNS_FAILURE, UNREACHABLE, FAILED }
private enum class InternetAction { PROXY, ROTATION_REFRESH }

@Composable
fun InternetSettings(
    controller: SettingsController,
    activeRotationCount: Int,
    applyNetworkSettings: suspend (PreferenceSnapshot) -> Unit,
    applyIpRotation: suspend (enabled: Boolean, refresh: Boolean) -> Int,
    testProxy: suspend (PreferenceSnapshot) -> ProxyConnectionResult,
    onFailure: () -> Unit,
    scrollTo: String? = null,
    modifier: Modifier = Modifier,
) {
    val snapshot by controller.store.snapshots.collectAsState()
    val preferences = snapshot
    if (preferences == null) { CircularProgressIndicator(); return }
    var action by remember { mutableStateOf<InternetAction?>(null) }
    var rotating by remember { mutableStateOf(false) }
    LaunchedEffect(preferences[ProxyEnabledKey], preferences[IpRotationEnabledKey]) {
        if (action == InternetAction.PROXY && !preferences[ProxyEnabledKey, false] ||
            action == InternetAction.ROTATION_REFRESH && !preferences[IpRotationEnabledKey, false]) action = null
    }
    SettingsPage(internetSettingsRows(preferences, !rotating, { action = InternetAction.PROXY },
        { action = InternetAction.ROTATION_REFRESH }),
        preferences, controller, scrollTo, onFailure = onFailure, modifier = modifier,
        onCommitted = { change ->
            if (IpRotationEnabledKey in change.changedKeys) {
                rotating = true
                val enabled = change.after[IpRotationEnabledKey, false]
                try {
                    val count = applyIpRotation(enabled, false)
                    if (enabled && count == 0) {
                        applyIpRotation(false, false)
                        controller.edit { if (this[IpRotationEnabledKey] == true) this[IpRotationEnabledKey] = false }
                        onFailure()
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    if (enabled) controller.edit { if (this[IpRotationEnabledKey] == true) this[IpRotationEnabledKey] = false }
                    throw failure
                } finally { rotating = false }
            } else applyNetworkSettings(change.after)
        })
    val selected = action
    if (selected != null) {
        var result by remember(selected, preferences) { mutableStateOf<ProxyConnectionResult?>(null) }
        var refreshed by remember(selected, preferences) { mutableStateOf<Int?>(null) }
        var failed by remember(selected, preferences) { mutableStateOf(false) }
        LaunchedEffect(selected, preferences) {
            try {
                if (selected == InternetAction.PROXY) result = testProxy(preferences)
                else { rotating = true; refreshed = applyIpRotation(true, true) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failed = true }
            finally { rotating = false }
        }
        AlertDialog(
            onDismissRequest = { action = null },
            title = { Text(stringResource(if (selected == InternetAction.PROXY) Res.string.m6_test_proxy else Res.string.m6_refresh_rotation)) },
            text = { Column {
                when {
                    failed -> Text(stringResource(Res.string.m6_ai_request_failed))
                    result != null -> Text(stringResource(when (result!!) {
                        ProxyConnectionResult.CONNECTED -> Res.string.m6_proxy_connected
                        ProxyConnectionResult.INVALID_CONFIGURATION -> Res.string.m6_proxy_invalid
                        ProxyConnectionResult.AUTHENTICATION -> Res.string.m6_proxy_auth_failed
                        ProxyConnectionResult.TIMEOUT -> Res.string.m6_proxy_timeout
                        ProxyConnectionResult.DNS_FAILURE -> Res.string.m6_proxy_dns_failed
                        ProxyConnectionResult.UNREACHABLE -> Res.string.m6_proxy_unreachable
                        ProxyConnectionResult.FAILED -> Res.string.m6_ai_request_failed
                    }))
                    refreshed != null -> Text(stringResource(Res.string.m6_rotation_count, refreshed!!))
                    else -> CircularProgressIndicator()
                }
                if (preferences[IpRotationEnabledKey, false])
                    Text(stringResource(Res.string.m6_rotation_count, activeRotationCount))
            } },
            confirmButton = { TextButton(onClick = { action = null }) { Text(stringResource(Res.string.m6_done)) } },
        )
    }
}

fun internetSettingsRows(
    preferences: PreferenceSnapshot,
    rotationIdle: Boolean,
    testProxy: () -> Unit,
    refreshRotation: () -> Unit,
): List<SettingsRow> = buildList {
    add(SettingsRow.Choice("yt_music_region", Res.string.m6_yt_region, YouTubeMusicRegionKey, "SYSTEM_DEFAULT",
        youtubeRegionOptions(), write = { setYouTubeMusicRegion(it) }))
    add(SettingsRow.Toggle("dns_over_https", Res.string.m6_doh, EnableDnsOverHttpsKey, false))
    if (preferences[EnableDnsOverHttpsKey, false]) {
        add(SettingsRow.Choice("dns_provider", Res.string.m6_dns_provider, DnsOverHttpsProviderKey, "Cloudflare",
            listOf("Cloudflare" to Res.string.m6_dns_cloudflare, "Google" to Res.string.m6_dns_google,
                "AdGuard" to Res.string.m6_dns_adguard, "Quad9" to Res.string.m6_dns_quad9, "Custom" to Res.string.m6_custom)
                .map { SettingsRow.Option(it.first, it.second) }))
        if (preferences[DnsOverHttpsProviderKey, "Cloudflare"] == "Custom")
            add(SettingsRow.Text("dns_custom_url", Res.string.m6_dns_custom, InlineCustomDnsUrlKey, default = "https://",
                valid = { it.startsWith("https://") && validLyricsEndpoint(it) }))
    }
    add(SettingsRow.Toggle("proxy_settings", Res.string.m6_proxy_enable, ProxyEnabledKey, false))
    if (preferences[ProxyEnabledKey, false]) {
        add(SettingsRow.Choice("proxy_type", Res.string.m6_proxy_type, ProxyTypeKey, "HTTP", listOf(
            SettingsRow.Option("HTTP", Res.string.m6_proxy_http), SettingsRow.Option("SOCKS", Res.string.m6_proxy_socks))))
        add(SettingsRow.Text("proxy_host", Res.string.m6_proxy_host, ProxyHostKey,
            valid = { parseProxyHostInput(it) != null }, write = { setProxyHostInput(it) }))
        add(SettingsRow.Number("proxy_port", Res.string.m6_proxy_port, ProxyPortKey, 8080, 1..65535))
        add(SettingsRow.Text("proxy_username", Res.string.m6_proxy_username, ProxyUsernameKey, masked = true))
        add(SettingsRow.Text("proxy_password", Res.string.m6_proxy_password, ProxyPasswordKey, masked = true))
        add(SettingsRow.Toggle("stream_bypass_proxy", Res.string.m6_proxy_bypass, StreamBypassProxyKey, false))
        add(SettingsRow.Action("test_proxy", Res.string.m6_test_proxy, testProxy))
    }
    add(SettingsRow.Toggle("ip_rotation", Res.string.m6_ip_rotation, IpRotationEnabledKey, false, enabled = rotationIdle))
    if (preferences[IpRotationEnabledKey, false])
        add(SettingsRow.Action("download_speed_limit", Res.string.m6_refresh_rotation, refreshRotation, enabled = rotationIdle))
}

private fun youtubeRegionOptions(): List<SettingsRow.Option> = listOf(
    SettingsRow.Option("SYSTEM_DEFAULT", Res.string.m6_follow_system),
    SettingsRow.Option("JP", Res.string.m6_region_jp), SettingsRow.Option("KR", Res.string.m6_region_kr),
    SettingsRow.Option("US", Res.string.m6_region_us), SettingsRow.Option("GB", Res.string.m6_region_gb),
    SettingsRow.Option("CN", Res.string.m6_region_cn), SettingsRow.Option("TW", Res.string.m6_region_tw),
    SettingsRow.Option("HK", Res.string.m6_region_hk), SettingsRow.Option("FR", Res.string.m6_region_fr),
    SettingsRow.Option("DE", Res.string.m6_region_de), SettingsRow.Option("ES", Res.string.m6_region_es),
    SettingsRow.Option("MX", Res.string.m6_region_mx), SettingsRow.Option("BR", Res.string.m6_region_br),
    SettingsRow.Option("RU", Res.string.m6_region_ru), SettingsRow.Option("IT", Res.string.m6_region_it),
    SettingsRow.Option("NL", Res.string.m6_region_nl), SettingsRow.Option("PL", Res.string.m6_region_pl),
    SettingsRow.Option("TR", Res.string.m6_region_tr), SettingsRow.Option("AU", Res.string.m6_region_au),
    SettingsRow.Option("CA", Res.string.m6_region_ca), SettingsRow.Option("IN", Res.string.m6_region_in),
    SettingsRow.Option("ID", Res.string.m6_region_id), SettingsRow.Option("TH", Res.string.m6_region_th),
    SettingsRow.Option("VN", Res.string.m6_region_vn), SettingsRow.Option("NG", Res.string.m6_region_ng),
    SettingsRow.Option("PH", Res.string.m6_region_ph), SettingsRow.Option("MY", Res.string.m6_region_my),
    SettingsRow.Option("SG", Res.string.m6_region_sg), SettingsRow.Option("AR", Res.string.m6_region_ar),
    SettingsRow.Option("CL", Res.string.m6_region_cl), SettingsRow.Option("CO", Res.string.m6_region_co),
    SettingsRow.Option("PE", Res.string.m6_region_pe), SettingsRow.Option("ZA", Res.string.m6_region_za),
    SettingsRow.Option("EG", Res.string.m6_region_eg), SettingsRow.Option("SA", Res.string.m6_region_sa),
    SettingsRow.Option("AE", Res.string.m6_region_ae),
)
