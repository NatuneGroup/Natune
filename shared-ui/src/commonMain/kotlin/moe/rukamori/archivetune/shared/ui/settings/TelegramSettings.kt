/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.ui.settings

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.domain.settings.*
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.stringResource

/** Destinations the host registers. The login, channel browser and bot screens stay host-owned. */
const val TelegramLoginRoute = "settings/telegram/login"
const val TelegramBrowseRoute = "telegram/browse"
const val TelegramBotsRoute = "telegram/bots"

/**
 * Telegram channel settings. [ready] reports the host's TDLib session state. [ensureStarted] starts TDLib so the
 * session restores or the login step is ready. [loadAccount] reads the signed-in account once, only while the name
 * is unknown; a failed lookup keeps the fallback label. [logOut] signs the TDLib session out first, and only then is
 * the local account cleared in one edit.
 */
@Composable
fun TelegramSettings(
    controller: SettingsController,
    ready: Boolean,
    ensureStarted: suspend () -> Unit,
    loadAccount: suspend () -> TelegramAccountFacts?,
    logOut: suspend () -> Unit,
    navigate: (String) -> Unit,
    onFailure: () -> Unit,
    scrollTo: String? = null,
    resumed: Boolean = true,
    motionEnabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val snapshot by controller.store.snapshots.collectAsState()
    val preferences = snapshot
    if (preferences == null) { CircularProgressIndicator(); return }
    val scope = rememberCoroutineScope()
    fun hostAction(block: suspend () -> Unit) {
        scope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { onFailure() }
        }
    }
    var logoutOpen by remember { mutableStateOf(false) }
    val name = preferences.telegramAccountName()
    val phone = preferences.telegramAccountPhone()
    val signedInValue = when {
        name.isNotBlank() && phone.isNotBlank() -> stringResource(Res.string.m6_telegram_account_with_phone, name, phone)
        name.isNotBlank() -> name
        phone.isNotBlank() -> phone
        else -> stringResource(Res.string.m6_integration_telegram)
    }

    // A start failure is not a toast on entry: the login row stays visible and carries the host's state instead.
    LaunchedEffect(Unit) {
        try { ensureStarted() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { }
    }
    LaunchedEffect(ready, name.isBlank()) {
        if (!ready || name.isNotBlank()) return@LaunchedEffect
        val facts = try {
            loadAccount()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        if (facts == null) return@LaunchedEffect
        try { controller.edit { commitTelegramAccount(facts) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { onFailure() }
    }

    val rows = buildList<SettingsRow> {
        add(SettingsRow.Header("telegram_account_group", Res.string.m6_telegram_account_group))
        if (ready) {
            add(SettingsRow.Status("telegram_logged_in_as", Res.string.m6_telegram_signed_in, signedInValue))
            add(SettingsRow.Action("telegram_logout", Res.string.m6_telegram_logout, run = { logoutOpen = true }))
        } else {
            add(SettingsRow.Action(
                "telegram_login", Res.string.m6_telegram_login,
                run = {
                    hostAction { ensureStarted() }
                    navigate(TelegramLoginRoute)
                },
                description = Res.string.m6_telegram_login_summary,
            ))
        }
        add(SettingsRow.Header("telegram_browse_group", Res.string.m6_telegram_browse_group))
        add(SettingsRow.Action(
            "telegram_browse_channels", Res.string.m6_telegram_browse_channels,
            run = { navigate(TelegramBrowseRoute) },
            enabled = ready,
            description = if (ready) Res.string.m6_telegram_browse_channels_description else Res.string.m6_telegram_login_required,
        ))
        add(SettingsRow.Toggle(
            "telegram_lossless_only", Res.string.m6_telegram_lossless_only, TelegramLosslessOnlyKey, default = true,
            description = Res.string.m6_telegram_lossless_only_description,
        ))
        add(SettingsRow.Header("telegram_bots_group", Res.string.m6_telegram_bots_group))
        add(SettingsRow.Action(
            "telegram_bots_title", Res.string.m6_telegram_bots_title,
            run = { navigate(TelegramBotsRoute) },
            enabled = ready,
            description = if (ready) Res.string.m6_telegram_bots_summary else Res.string.m6_telegram_login_required,
        ))
    }
    SettingsPage(
        rows = rows,
        preferences = preferences,
        controller = controller,
        scrollTo = scrollTo,
        resumed = resumed,
        motionEnabled = motionEnabled,
        onFailure = onFailure,
        modifier = modifier,
    )
    if (logoutOpen) {
        TelegramLogoutDialog(
            onConfirm = {
                logOut()
                controller.edit { clearTelegramAccount() }
            },
            onDismiss = { logoutOpen = false },
            onFailure = onFailure,
        )
    }
}

/** Confirms sign-out. The dialog stays open and non-dismissable while the host session ends. */
@Composable
private fun TelegramLogoutDialog(
    onConfirm: suspend () -> Unit,
    onDismiss: () -> Unit,
    onFailure: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        text = {
            if (busy) CircularProgressIndicator()
            else Text(stringResource(Res.string.m6_telegram_logout_confirm))
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    try { onConfirm(); onDismiss() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { onFailure() }
                    finally { busy = false }
                }
            }) { Text(stringResource(Res.string.m6_telegram_logout)) }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(Res.string.m6_cancel)) }
        },
    )
}
