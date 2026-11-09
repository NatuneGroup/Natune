package moe.rukamori.archivetune.shared.ui.settings

import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.stringResource

/** Shared About state and dialogs; hosts supply only build/device facts, data and URL/artwork adapters. */
@Composable
fun HostedAboutSettings(
    controller: SettingsController,
    versionLine: String,
    distributionLabel: String,
    deviceLabel: String,
    loadContributors: suspend () -> Result<List<AboutContributor>>,
    loadTranslations: suspend () -> Result<List<AboutTranslationEntry>>,
    memberAvatar: @Composable (String, String, Modifier) -> Unit,
    navigate: (String) -> Unit,
    openUri: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var contributorsState by remember { mutableStateOf<AboutContributorsState>(AboutContributorsState.Loading) }
    var translationsState by remember { mutableStateOf<AboutTranslationsState>(AboutTranslationsState.Loading) }
    var dialog by remember { mutableStateOf(AboutDialog.NONE) }
    fun refreshContributors() {
        contributorsState = AboutContributorsState.Loading
        scope.launch {
            contributorsState = loadContributors().fold(
                onSuccess = { if (it.isEmpty()) AboutContributorsState.Empty else AboutContributorsState.Loaded(it) },
                onFailure = { AboutContributorsState.Failed(AboutFailureMessages.requestFailed) },
            )
        }
    }
    fun refreshTranslations() {
        translationsState = AboutTranslationsState.Loading
        scope.launch {
            translationsState = loadTranslations().fold(
                onSuccess = { AboutTranslationsState.Loaded(it) },
                onFailure = { AboutTranslationsState.Failed(AboutFailureMessages.requestFailed) },
            )
        }
    }
    LaunchedEffect(loadContributors) { refreshContributors() }
    AboutSettings(
        controller = controller,
        isNightlyBuild = false,
        updaterAvailable = false,
        appName = stringResource(Res.string.app_name),
        versionLine = versionLine,
        distributionLabel = distributionLabel,
        debugOrAbiLabel = deviceLabel,
        appLogo = { NatuneAboutLogo() },
        memberAvatar = memberAvatar,
        contributorsState = contributorsState,
        translationsState = translationsState,
        licensesState = AboutLicensesState.Loaded(AboutBundledNotices.entries, bundledOnly = true),
        activeDialog = dialog,
        navigate = navigate,
        onOpenUri = openUri,
        onRetryContributors = ::refreshContributors,
        onOpenTranslations = {
            dialog = AboutDialog.TRANSLATION_CONTRIBUTORS
            if (translationsState !is AboutTranslationsState.Loaded) refreshTranslations()
        },
        onRetryTranslations = ::refreshTranslations,
        onOpenLicenses = { dialog = AboutDialog.DEPENDENCY_LICENSES },
        onRetryLicenses = { },
        onDismissDialog = { dialog = AboutDialog.NONE },
    )
}
