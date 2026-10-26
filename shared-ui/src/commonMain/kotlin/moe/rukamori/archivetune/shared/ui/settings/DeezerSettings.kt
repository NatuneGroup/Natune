/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.domain.settings.*
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.stringResource

/** Route that the host's WebView sign-in answers to. The host owns that page; this module only links to it. */
const val DeezerLoginRoute = "settings/deezer/login"

/**
 * Account facts the host returns after it verifies an ARL with Deezer. Premium maps to the lossless entitlement. [userId]
 * is the gateway's USER_ID, the account identity the ARL is committed as; an anonymous ARL reports none.
 */
data class DeezerArlAccount(val name: String, val premium: Boolean, val userId: Long = 0L)

/**
 * Deezer account and API-instance settings. ARL verification is host-owned: [verifyArl] answers a verified account
 * or null for a rejected ARL. The attempt begins with [accounts] before Deezer is asked, and a verified ARL is committed
 * as the account that Deezer named. [onSignOut] drops the host's in-memory ARL first, and [accounts] then signs out. [onNotice] carries the rejection, sign-in
 * and sign-out outcomes that the baseline announced with toasts; the host must show them, not drop them.
 */
@Composable
fun DeezerSettings(
    controller: SettingsController,
    accounts: ProviderAccountLifecycle,
    poolConfigured: Boolean,
    verifyArl: suspend (arl: String) -> DeezerArlAccount?,
    onSignOut: suspend () -> Unit,
    navigate: (String) -> Unit,
    onFailure: () -> Unit,
    onNotice: (SettingsNotice) -> Unit,
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
    var arlDialogOpen by remember { mutableStateOf(false) }
    var instancesDialogOpen by remember { mutableStateOf(false) }
    val signedIn = preferences.deezerSignedIn()
    val instanceCount = preferences.deezerInstanceList().size
    val instancesValue = when (instanceCount) {
        0 -> stringResource(Res.string.m6_deezer_api_instances_none)
        1 -> stringResource(Res.string.m6_deezer_api_instances_one, instanceCount)
        else -> stringResource(Res.string.m6_deezer_api_instances_other, instanceCount)
    }
    // The pool feed only contributes while the build supplies it and the user has not turned it off.
    val poolOn = poolConfigured && preferences[UsePoolAccountsKey, true]
    val poolValue = stringResource(if (poolOn) Res.string.m6_deezer_api_pool_on else Res.string.m6_deezer_api_pool_off)
    val rows = buildList<SettingsRow> {
        add(SettingsRow.Header("deezer_account", Res.string.m6_integration_deezer))
        if (signedIn) {
            add(SettingsRow.Status("deezer_signed_in", Res.string.m6_deezer_account, preferences.deezerAccountName()))
            add(SettingsRow.Action("deezer_sign_out", Res.string.m6_deezer_sign_out, run = {
                hostAction {
                    onSignOut()
                    accounts.signOut(ProviderAccountLifecycle.DEEZER)
                    onNotice(SettingsNotice.DeezerSignedOut)
                }
            }))
        } else {
            add(SettingsRow.Action(
                "deezer_login", Res.string.m6_deezer_login, run = { navigate(DeezerLoginRoute) },
                description = Res.string.m6_deezer_login_description,
            ))
            add(SettingsRow.Action(
                "deezer_arl_login", Res.string.m6_deezer_arl_login, run = { arlDialogOpen = true },
                description = Res.string.m6_deezer_arl_login_description,
            ))
        }
        add(SettingsRow.Header("deezer_api", Res.string.m6_deezer_api_group))
        add(SettingsRow.Status(
            "deezer_api_instances", Res.string.m6_deezer_api_instances, instancesValue,
            run = { instancesDialogOpen = true },
        ))
        add(SettingsRow.Status("deezer_api_pool", Res.string.m6_deezer_api_pool, poolValue))
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
    if (arlDialogOpen) {
        DeezerArlDialog(
            accounts = accounts,
            verifyArl = verifyArl,
            onFailure = onFailure,
            onNotice = onNotice,
            onDismiss = { arlDialogOpen = false },
        )
    }
    if (instancesDialogOpen) {
        DeezerInstancesDialog(
            controller = controller,
            initial = preferences[DeezerInstancesKey, ""],
            onFailure = onFailure,
            onDismiss = { instancesDialogOpen = false },
        )
    }
}

/**
 * Masked ARL entry. The dialog stays open while the host verifies the value; a rejected ARL keeps it open with the
 * baseline message, and only a verified account is committed through the logical vault.
 */
@Composable
private fun DeezerArlDialog(
    accounts: ProviderAccountLifecycle,
    verifyArl: suspend (String) -> DeezerArlAccount?,
    onFailure: () -> Unit,
    onNotice: (SettingsNotice) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var arl by remember { mutableStateOf("") }
    var verifying by remember { mutableStateOf(false) }
    var rejected by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!verifying) onDismiss() },
        title = { Text(stringResource(Res.string.m6_deezer_arl_login)) },
        text = {
            Column {
                Text(stringResource(Res.string.m6_deezer_arl_login_description))
                OutlinedTextField(
                    value = arl,
                    onValueChange = { arl = it; rejected = false },
                    singleLine = true,
                    enabled = !verifying,
                    placeholder = { Text(stringResource(Res.string.m6_deezer_arl_placeholder)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                if (verifying) Text(stringResource(Res.string.m6_deezer_arl_verifying))
                if (rejected) Text(stringResource(Res.string.m6_deezer_arl_invalid))
            }
        },
        confirmButton = {
            TextButton(
                enabled = !verifying && isPlausibleDeezerArl(arl),
                onClick = {
                    val submitted = normalizeDeezerArl(arl)
                    verifying = true
                    scope.launch {
                        try {
                            val attempt = accounts.begin(ProviderAccountLifecycle.DEEZER)
                            val account = verifyArl(submitted)
                            val outcome = account?.takeIf { it.name.isNotBlank() }?.let {
                                accounts.commitDeezer(attempt, it.userId, submitted, it.name, it.premium)
                            }
                            when (outcome) {
                                ProviderSignInOutcome.Accepted -> {
                                    onNotice(SettingsNotice.DeezerSignedIn(requireNotNull(account).name))
                                    onDismiss()
                                }
                                null, ProviderSignInOutcome.Unverified -> {
                                    rejected = true
                                    onNotice(SettingsNotice.DeezerArlRejected)
                                }
                                else -> onFailure()
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            onFailure()
                        } finally {
                            verifying = false
                        }
                    }
                },
            ) { Text(stringResource(Res.string.m6_done)) }
        },
        dismissButton = {
            TextButton(enabled = !verifying, onClick = onDismiss) { Text(stringResource(Res.string.m6_cancel)) }
        },
    )
}

/** Free-text instance list. Saving normalizes it and turns Deezer on when at least one entry remains. */
@Composable
private fun DeezerInstancesDialog(
    controller: SettingsController,
    initial: String,
    onFailure: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.m6_deezer_api_instances)) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    minLines = 3,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                Text(stringResource(Res.string.m6_deezer_api_instances_hint))
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val submitted = text
                onDismiss()
                scope.launch {
                    try {
                        controller.edit { setDeezerInstances(submitted) }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        onFailure()
                    }
                }
            }) { Text(stringResource(Res.string.m6_done)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.m6_cancel)) } },
    )
}
