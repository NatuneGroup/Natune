package moe.rukamori.archivetune.shared.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.domain.settings.QobuzTokenEntry
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.stringResource

/** Passwords are held only in this dialog, cleared on submission, and never saved with the token. */
@Composable
fun QobuzPasswordLogin(
    exchange: suspend (String, String, String, String) -> QobuzTokenEntry?,
    save: suspend (QobuzTokenEntry) -> Unit,
    onFailure: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true }) { Text(stringResource(Res.string.builtin_qobuz_password_login)) }
    if (!open) return
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var appId by remember { mutableStateOf("") }
    var appSecret by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!busy) open = false },
        title = { Text(stringResource(Res.string.builtin_qobuz_password_login)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(email, { email = it }, enabled = !busy, singleLine = true,
                    label = { Text(stringResource(Res.string.builtin_qobuz_email)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                OutlinedTextField(password, { password = it }, enabled = !busy, singleLine = true,
                    label = { Text(stringResource(Res.string.builtin_qobuz_password)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                OutlinedTextField(appId, { appId = it }, enabled = !busy, singleLine = true,
                    label = { Text(stringResource(Res.string.m6_qobuz_login_app_id_hint)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(appSecret, { appSecret = it }, enabled = !busy, singleLine = true,
                    label = { Text(stringResource(Res.string.m6_qobuz_login_app_secret_hint)) },
                    visualTransformation = PasswordVisualTransformation())
                if (failed) Text(stringResource(Res.string.builtin_qobuz_sign_in_failed), color = MaterialTheme.colorScheme.error)
                if (busy) CircularProgressIndicator()
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && email.isNotBlank() && password.isNotEmpty() &&
                appId.isNotBlank() && appId.all { it in '0'..'9' } && Regex("^[a-f0-9]{32}$").matches(appSecret),
                onClick = {
                    val submittedPassword = password
                    password = ""
                    failed = false
                    busy = true
                    scope.launch {
                        try {
                            val token = exchange(email.trim(), submittedPassword, appId, appSecret)
                            if (token == null) { failed = true; onFailure() } else { save(token); open = false }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { failed = true; onFailure() }
                        finally { busy = false }
                    }
                }) { Text(stringResource(Res.string.m6_qobuz_login_start)) }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = { open = false }) { Text(stringResource(Res.string.m6_cancel)) }
        },
    )
}
