package moe.rukamori.archivetune.shared.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

enum class OnboardingPage { Welcome, Permissions, Login, Community }
enum class PermissionStatus { Allowed, NeedsAction, AllowedByInstall, Unavailable }

data class PermissionRow(
    val id: String,
    val title: StringResource,
    val description: StringResource,
    val status: PermissionStatus,
    val request: (() -> Unit)?,
)

class OnboardingActions(
    val next: () -> Unit,
    val back: () -> Unit,
    val finish: () -> Unit,
    val login: () -> Unit,
)

/** Platform permission checks, browser login and completion persistence stay with their existing host owner. */
@Composable
fun OnboardingScreen(
    page: OnboardingPage,
    permissions: List<PermissionRow>,
    signedIn: Boolean,
    finishing: Boolean,
    failed: Boolean,
    actions: OnboardingActions,
    community: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    welcome: @Composable () -> Unit = {},
) {
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(24.dp)) {
        Text(stringResource(when (page) {
            OnboardingPage.Welcome -> Res.string.m4_welcome
            OnboardingPage.Permissions -> Res.string.m4_permissions
            OnboardingPage.Login -> Res.string.m4_login_title
            OnboardingPage.Community -> Res.string.m4_community
        }), style = MaterialTheme.typography.headlineLarge)
        Text(stringResource(when (page) {
            OnboardingPage.Welcome -> Res.string.m4_welcome_description
            OnboardingPage.Permissions -> Res.string.m4_permissions_description
            OnboardingPage.Login -> Res.string.m4_login_description
            OnboardingPage.Community -> Res.string.m4_community_description
        }))
        LazyColumn(Modifier.weight(1f)) {
            when (page) {
                OnboardingPage.Welcome -> item { welcome() }
                OnboardingPage.Permissions -> items(permissions, key = { it.id }) { permission ->
                    ListItem(
                        headlineContent = { Text(stringResource(permission.title)) },
                        supportingContent = { Text(stringResource(permission.description)) },
                        trailingContent = {
                            when (permission.status) {
                                PermissionStatus.Allowed, PermissionStatus.AllowedByInstall -> Text(stringResource(Res.string.m4_allowed))
                                PermissionStatus.Unavailable -> Text(stringResource(Res.string.m4_unavailable))
                                PermissionStatus.NeedsAction -> permission.request?.let {
                                    TextButton(it, enabled = !finishing, modifier = Modifier.testTag("permission-${permission.id}")) {
                                        Text(stringResource(Res.string.m4_allow))
                                    }
                                }
                            }
                        })
                }
                OnboardingPage.Login -> item {
                    if (signedIn) Text(stringResource(Res.string.m4_signed_in))
                    else Button(actions.login, enabled = !finishing) { Text(stringResource(Res.string.m4_login)) }
                }
                OnboardingPage.Community -> item { community() }
            }
        }
        if (failed) Text(stringResource(Res.string.m4_setup_failed), color = MaterialTheme.colorScheme.error)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            if (page != OnboardingPage.Welcome) TextButton(actions.back, enabled = !finishing) {
                Text(stringResource(Res.string.m4_back))
            }
            Button(if (page == OnboardingPage.Community) actions.finish else actions.next,
                enabled = !finishing, modifier = Modifier.testTag("onboarding-next")) {
                Text(stringResource(if (page == OnboardingPage.Community) Res.string.m4_finish else Res.string.m4_next))
            }
        }
    }
}
