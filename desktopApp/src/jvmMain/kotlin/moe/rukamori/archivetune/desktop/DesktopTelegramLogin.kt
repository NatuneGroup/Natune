package moe.rukamori.archivetune.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.desktop.integration.DesktopStrings
import moe.rukamori.archivetune.desktop.telegram.TdlightClientSession
import moe.rukamori.archivetune.desktop.telegram.TelegramNativeAdmission
import moe.rukamori.archivetune.shared.domain.telegram.TelegramAuthState
import moe.rukamori.archivetune.shared.domain.telegram.TelegramCodeType
import moe.rukamori.archivetune.shared.ui.navigation.ShellNavigation

/**
 * The Telegram sign-in page: phone number, login code and two-step password, in the order TDLib asks for them. The page
 * returns to settings only once the account is admitted, not when TDLib first reports Ready, so a session whose GetMe
 * check fails stays here with its failure shown.
 */
@Composable
internal fun DesktopTelegramLoginPage(session: TdlightClientSession, navigation: ShellNavigation) {
    val state by session.authState.collectAsState()
    val admission by session.admission.collectAsState()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }

    LaunchedEffect(state) {
        // A logout ends in Idle, so returning to this page after one starts a new client for the next sign-in.
        if (state is TelegramAuthState.Idle && session.configured) session.start()
    }
    LaunchedEffect(admission) {
        if (admission is TelegramNativeAdmission.Admitted) navigation.back()
    }

    fun submit(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        failed = false
        scope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failed = true
            } finally {
                busy = false
            }
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp).widthIn(max = 560.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(DesktopStrings["telegram_login_title"], style = MaterialTheme.typography.headlineSmall)
        when (val current = state) {
            TelegramAuthState.Idle ->
                if (session.configured) CircularProgressIndicator() else Hint(DesktopStrings["telegram_not_configured"])
            TelegramAuthState.Connecting, TelegramAuthState.LoggingOut -> CircularProgressIndicator()
            TelegramAuthState.WaitPhoneNumber -> PhoneStep(busy) { phone -> submit { session.submitPhoneNumber(phone) } }
            is TelegramAuthState.WaitCode -> CodeStep(
                state = current,
                busy = busy,
                onSubmit = { code -> submit { session.submitCode(code) } },
                onResend = { submit { session.resendCode() } },
            )
            is TelegramAuthState.WaitPassword -> PasswordStep(current.passwordHint, busy) { password ->
                submit { session.submitPassword(password) }
            }
            TelegramAuthState.Ready -> when (admission) {
                is TelegramNativeAdmission.Admitted -> Hint(DesktopStrings["telegram_login_success"])
                TelegramNativeAdmission.Verifying -> {
                    CircularProgressIndicator()
                    Hint(DesktopStrings["telegram_checking_account"])
                }
                TelegramNativeAdmission.NotAdmitted -> Hint(DesktopStrings["telegram_account_check_failed"])
            }
            is TelegramAuthState.Unsupported -> Hint(
                when (current.stateName) {
                    "NativeLibraryMissing" -> DesktopStrings["telegram_native_unavailable"]
                    "InvalidApiCredentials" -> DesktopStrings["telegram_not_configured"]
                    else -> DesktopStrings["telegram_login_unsupported"]
                },
            )
        }
        if (failed) {
            Text(
                DesktopStrings["telegram_login_failed"],
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        TextButton(onClick = { navigation.back() }) { Text(DesktopStrings["cancel"]) }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun PhoneStep(busy: Boolean, onSubmit: (String) -> Unit) {
    var phone by remember { mutableStateOf("") }
    Text(DesktopStrings["telegram_phone_step_title"], style = MaterialTheme.typography.titleMedium)
    Hint(DesktopStrings["telegram_phone_step_description"])
    OutlinedTextField(
        value = phone,
        onValueChange = { phone = it },
        label = { Text(DesktopStrings["telegram_phone_number_short"]) },
        singleLine = true,
        enabled = !busy,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
        modifier = Modifier.fillMaxWidth(),
    )
    Button(onClick = { onSubmit(phone) }, enabled = !busy && phone.isNotBlank()) {
        Text(DesktopStrings["telegram_next"])
    }
}

@Composable
private fun CodeStep(
    state: TelegramAuthState.WaitCode,
    busy: Boolean,
    onSubmit: (String) -> Unit,
    onResend: () -> Unit,
) {
    var code by remember { mutableStateOf("") }
    var remaining by remember(state) { mutableIntStateOf(state.resendTimeoutSeconds.coerceAtLeast(0)) }
    LaunchedEffect(state) {
        while (remaining > 0) {
            delay(1_000L)
            remaining--
        }
    }
    val sentKey = when (state.codeType) {
        TelegramCodeType.TELEGRAM_APP -> "telegram_code_sent_app"
        TelegramCodeType.SMS -> "telegram_code_sent_sms"
        TelegramCodeType.CALL -> "telegram_code_sent_call"
        TelegramCodeType.OTHER -> "telegram_code_sent"
    }
    Text(DesktopStrings["telegram_code_step_title"], style = MaterialTheme.typography.titleMedium)
    Hint(DesktopStrings[sentKey].format(state.phoneNumber))
    OutlinedTextField(
        value = code,
        onValueChange = { code = it },
        label = { Text(DesktopStrings["telegram_code"]) },
        singleLine = true,
        enabled = !busy,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { onSubmit(code) }, enabled = !busy && code.isNotBlank()) {
            Text(DesktopStrings["telegram_next"])
        }
        if (state.canResend) {
            TextButton(onClick = onResend, enabled = !busy && remaining == 0) {
                Text(
                    if (remaining > 0) DesktopStrings["telegram_resend_code_in"].format(remaining)
                    else DesktopStrings["telegram_resend_code"],
                )
            }
        }
    }
}

@Composable
private fun PasswordStep(hint: String?, busy: Boolean, onSubmit: (String) -> Unit) {
    var password by remember { mutableStateOf("") }
    Text(DesktopStrings["telegram_password_step_title"], style = MaterialTheme.typography.titleMedium)
    Hint(DesktopStrings["telegram_password_step_description"])
    if (hint != null) Hint(DesktopStrings["telegram_password_hint_label"].format(hint))
    OutlinedTextField(
        value = password,
        onValueChange = { password = it },
        label = { Text(DesktopStrings["telegram_password"]) },
        singleLine = true,
        enabled = !busy,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
    Button(onClick = { onSubmit(password) }, enabled = !busy && password.isNotEmpty()) {
        Text(DesktopStrings["telegram_next"])
    }
}
