package app.natune.shared.ui.ios

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
import app.natune.shared.data.platform.ios.IosTelegramClient
import app.natune.shared.data.platform.ios.IosTelegramNativeAdmission
import app.natune.shared.domain.telegram.TelegramAuthState
import app.natune.shared.domain.telegram.TelegramCodeType
import app.natune.shared.ui.navigation.ShellNavigation
import app.natune.shared.ui.resources.Res
import app.natune.shared.ui.resources.ios_lane_telegram_check_failed
import app.natune.shared.ui.resources.ios_lane_telegram_checking
import app.natune.shared.ui.resources.ios_lane_telegram_code
import app.natune.shared.ui.resources.ios_lane_telegram_code_sent
import app.natune.shared.ui.resources.ios_lane_telegram_code_sent_app
import app.natune.shared.ui.resources.ios_lane_telegram_code_sent_call
import app.natune.shared.ui.resources.ios_lane_telegram_code_sent_sms
import app.natune.shared.ui.resources.ios_lane_telegram_code_title
import app.natune.shared.ui.resources.ios_lane_telegram_connected
import app.natune.shared.ui.resources.ios_lane_telegram_failed
import app.natune.shared.ui.resources.ios_lane_telegram_login_title
import app.natune.shared.ui.resources.ios_lane_telegram_native_unavailable
import app.natune.shared.ui.resources.ios_lane_telegram_next
import app.natune.shared.ui.resources.ios_lane_telegram_not_configured
import app.natune.shared.ui.resources.ios_lane_telegram_password
import app.natune.shared.ui.resources.ios_lane_telegram_password_description
import app.natune.shared.ui.resources.ios_lane_telegram_password_hint
import app.natune.shared.ui.resources.ios_lane_telegram_password_title
import app.natune.shared.ui.resources.ios_lane_telegram_phone_description
import app.natune.shared.ui.resources.ios_lane_telegram_phone_number
import app.natune.shared.ui.resources.ios_lane_telegram_phone_title
import app.natune.shared.ui.resources.ios_lane_telegram_resend_code
import app.natune.shared.ui.resources.ios_lane_telegram_resend_code_in
import app.natune.shared.ui.resources.ios_lane_telegram_unsupported
import app.natune.shared.ui.resources.m6_cancel
import org.jetbrains.compose.resources.stringResource

/**
 * The Telegram sign-in page: phone number, login code and two-step password, in the order TDLib asks for them. The
 * page returns to settings only once the account is admitted, not when TDLib first reports Ready, so a session whose
 * GetMe check fails stays here with its failure shown.
 */
@Composable
internal fun IosTelegramLoginPage(telegram: IosTelegramClient, navigation: ShellNavigation) {
    val state by telegram.authState.collectAsState()
    val admission by telegram.admission.collectAsState()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var notConfigured by remember { mutableStateOf(false) }

    LaunchedEffect(state) {
        // Idle means no client is running; a start with no credentials reports the build as not configured.
        if (state is TelegramAuthState.Idle && !telegram.start()) notConfigured = true
    }
    LaunchedEffect(admission) {
        if (admission is IosTelegramNativeAdmission.Admitted) navigation.back()
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
        Text(stringResource(Res.string.ios_lane_telegram_login_title), style = MaterialTheme.typography.headlineSmall)
        when (val current = state) {
            TelegramAuthState.Idle ->
                if (notConfigured) Hint(stringResource(Res.string.ios_lane_telegram_not_configured))
                else CircularProgressIndicator()
            TelegramAuthState.Connecting, TelegramAuthState.LoggingOut -> CircularProgressIndicator()
            TelegramAuthState.WaitPhoneNumber -> PhoneStep(busy) { phone -> submit { telegram.submitPhoneNumber(phone) } }
            is TelegramAuthState.WaitCode -> CodeStep(
                state = current,
                busy = busy,
                onSubmit = { code -> submit { telegram.submitCode(code) } },
                onResend = { submit { telegram.resendCode() } },
            )
            is TelegramAuthState.WaitPassword -> PasswordStep(current.passwordHint, busy) { password ->
                submit { telegram.submitPassword(password) }
            }
            TelegramAuthState.Ready -> when (admission) {
                is IosTelegramNativeAdmission.Admitted -> Hint(stringResource(Res.string.ios_lane_telegram_connected))
                IosTelegramNativeAdmission.Verifying -> {
                    CircularProgressIndicator()
                    Hint(stringResource(Res.string.ios_lane_telegram_checking))
                }
                IosTelegramNativeAdmission.NotAdmitted ->
                    Hint(stringResource(Res.string.ios_lane_telegram_check_failed))
            }
            is TelegramAuthState.Unsupported -> Hint(
                stringResource(
                    when (current.stateName) {
                        "NativeLibraryMissing" -> Res.string.ios_lane_telegram_native_unavailable
                        "InvalidApiCredentials" -> Res.string.ios_lane_telegram_not_configured
                        else -> Res.string.ios_lane_telegram_unsupported
                    }
                )
            )
        }
        if (failed) {
            Text(
                stringResource(Res.string.ios_lane_telegram_failed),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        TextButton(onClick = { navigation.back() }) { Text(stringResource(Res.string.m6_cancel)) }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun PhoneStep(busy: Boolean, onSubmit: (String) -> Unit) {
    var phone by remember { mutableStateOf("") }
    Text(stringResource(Res.string.ios_lane_telegram_phone_title), style = MaterialTheme.typography.titleMedium)
    Hint(stringResource(Res.string.ios_lane_telegram_phone_description))
    OutlinedTextField(
        value = phone,
        onValueChange = { phone = it },
        label = { Text(stringResource(Res.string.ios_lane_telegram_phone_number)) },
        singleLine = true,
        enabled = !busy,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
        modifier = Modifier.fillMaxWidth(),
    )
    Button(onClick = { onSubmit(phone) }, enabled = !busy && phone.isNotBlank()) {
        Text(stringResource(Res.string.ios_lane_telegram_next))
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
    val sent = when (state.codeType) {
        TelegramCodeType.TELEGRAM_APP -> Res.string.ios_lane_telegram_code_sent_app
        TelegramCodeType.SMS -> Res.string.ios_lane_telegram_code_sent_sms
        TelegramCodeType.CALL -> Res.string.ios_lane_telegram_code_sent_call
        TelegramCodeType.OTHER -> Res.string.ios_lane_telegram_code_sent
    }
    Text(stringResource(Res.string.ios_lane_telegram_code_title), style = MaterialTheme.typography.titleMedium)
    Hint(stringResource(sent, state.phoneNumber))
    OutlinedTextField(
        value = code,
        onValueChange = { code = it },
        label = { Text(stringResource(Res.string.ios_lane_telegram_code)) },
        singleLine = true,
        enabled = !busy,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { onSubmit(code) }, enabled = !busy && code.isNotBlank()) {
            Text(stringResource(Res.string.ios_lane_telegram_next))
        }
        if (state.canResend) {
            TextButton(onClick = onResend, enabled = !busy && remaining == 0) {
                Text(
                    if (remaining > 0) stringResource(Res.string.ios_lane_telegram_resend_code_in, remaining)
                    else stringResource(Res.string.ios_lane_telegram_resend_code)
                )
            }
        }
    }
}

@Composable
private fun PasswordStep(hint: String?, busy: Boolean, onSubmit: (String) -> Unit) {
    var password by remember { mutableStateOf("") }
    Text(stringResource(Res.string.ios_lane_telegram_password_title), style = MaterialTheme.typography.titleMedium)
    Hint(stringResource(Res.string.ios_lane_telegram_password_description))
    if (hint != null) Hint(stringResource(Res.string.ios_lane_telegram_password_hint, hint))
    OutlinedTextField(
        value = password,
        onValueChange = { password = it },
        label = { Text(stringResource(Res.string.ios_lane_telegram_password)) },
        singleLine = true,
        enabled = !busy,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
    Button(onClick = { onSubmit(password) }, enabled = !busy && password.isNotEmpty()) {
        Text(stringResource(Res.string.ios_lane_telegram_next))
    }
}
