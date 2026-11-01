package moe.rukamori.archivetune.shared.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import moe.rukamori.archivetune.shared.domain.settings.*
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.runtime.getValue

/** Host answer for the remote contributor list. The page renders it and never fetches it. */
sealed interface AboutContributorsState {
    data object Loading : AboutContributorsState
    data object Empty : AboutContributorsState
    data class Failed(val message: StringResource) : AboutContributorsState
    data class Loaded(val contributors: List<AboutContributor>) : AboutContributorsState
}

data class AboutContributor(val login: String, val avatarUrl: String, val profileUrl: String)

/** Host answer for the translation credits dialog. */
sealed interface AboutTranslationsState {
    data object Loading : AboutTranslationsState
    data object Empty : AboutTranslationsState
    data class Failed(val message: StringResource) : AboutTranslationsState
    data class Loaded(val entries: List<AboutTranslationEntry>) : AboutTranslationsState
}

data class AboutTranslationEntry(val language: String, val contributors: String?)

/** Host answer for the open-source licenses dialog. */
sealed interface AboutLicensesState {
    data object Loading : AboutLicensesState
    data object Empty : AboutLicensesState
    data class Failed(val message: StringResource) : AboutLicensesState
    /** [bundledOnly] marks a list of only bundled-data notices, flagged so the dialog explains the catalog is absent. */
    data class Loaded(val entries: List<AboutLicenseEntry>, val bundledOnly: Boolean = false) : AboutLicensesState
}

/**
 * A null [license] is shown as the localized unknown-license label, never as an empty value.
 * A non-null [noticeResource] names a bundled `files/` compose resource the entry opens on tap.
 */
data class AboutLicenseEntry(val name: String, val version: String?, val license: String?, val noticeResource: String? = null)

/** The open full-screen list. The host owns it because the host starts the loader that fills the list. */
enum class AboutDialog { NONE, TRANSLATION_CONTRIBUTORS, DEPENDENCY_LICENSES }

/** The Natune mark for the [AboutSettings] logo slot, for hosts that keep no copy of the artwork themselves. */
@Composable
fun NatuneAboutLogo(modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(Res.drawable.natune_logo),
        contentDescription = null,
        modifier = modifier.size(112.dp).clip(RoundedCornerShape(28.dp)),
    )
}

/** Failure texts a host can hand to the About list states without reaching into this module's resources. */
object AboutFailureMessages {
    val requestFailed: StringResource get() = Res.string.m6_about_unknown_error
    val unavailableOnHost: StringResource get() = Res.string.m6_about_host_unavailable
}

private const val ContributorsReadMoreUrl = "https://github.com/rukamori/ArchiveTune/graphs/contributors"

private class AboutLink(val label: StringResource, val url: String) {
    /** The destination picks the brand mark, as the baseline link list did. */
    val icon: DrawableResource
        get() = when {
            "discord" in url -> Res.drawable.m6_icon_discord
            "t.me/" in url -> Res.drawable.m6_icon_telegram
            else -> Res.drawable.m6_icon_github
        }
}

private class AboutCredit(
    val name: String,
    val position: StringResource,
    val avatarUrl: String,
    val profileUrl: String,
    val links: List<AboutLink>,
)

/**
 * Fixed product destinations and credits. The people groups read in the docs/CREDITS.md
 * lineage order: InnerTune (Zion Huang) -> Metrolist (MO AGAMY) -> ArchiveTune (morie) ->
 * the Natune Kotlin Multiplatform port (vossgraves). The GitHub row points at the public
 * NatuneGroup/ArchiveTune project; the KMP port repository is private.
 */
private val communityLinks = listOf(
    AboutLink(Res.string.m6_about_github, "https://github.com/NatuneGroup/ArchiveTune"),
    AboutLink(Res.string.m6_about_telegram, "https://t.me/ArchiveTuneGC"),
    AboutLink(Res.string.m6_about_discord, "https://discord.gg/XF2fpb9rTq"),
)

private val leadDevelopers = listOf(
    AboutCredit(
        name = "morie",
        position = Res.string.m6_about_position_lead_dev,
        avatarUrl = "https://avatars.githubusercontent.com/u/107134739?v=4",
        profileUrl = "https://github.com/rukamori",
        links = listOf(
            AboutLink(Res.string.m6_about_github, "https://github.com/rukamori"),
            AboutLink(Res.string.m6_about_discord, "https://discord.com/users/886971572668219392"),
        ),
    ),
)

private val teamMembers = listOf(
    AboutCredit(
        name = "VossGraves",
        position = Res.string.m6_about_position_vossgraves,
        avatarUrl = "https://avatars.githubusercontent.com/vossgraves",
        profileUrl = "https://github.com/vossgraves",
        links = listOf(AboutLink(Res.string.m6_about_github, "https://github.com/vossgraves")),
    ),
    AboutCredit(
        name = "Miko",
        position = Res.string.m6_about_position_developers,
        avatarUrl = "https://avatars.githubusercontent.com/u/89002922?v=4",
        profileUrl = "https://github.com/mikooochi",
        links = listOf(AboutLink(Res.string.m6_about_github, "https://github.com/mikooochi")),
    ),
    AboutCredit(
        name = "WTTexe",
        position = Res.string.m6_about_position_developers,
        avatarUrl = "https://avatars.githubusercontent.com/u/93458424?v=4",
        profileUrl = "https://github.com/Windowstechtips",
        links = listOf(
            AboutLink(Res.string.m6_about_github, "https://github.com/Windowstechtips"),
            AboutLink(Res.string.m6_about_discord, "https://discord.com/users/840839409640800258"),
        ),
    ),
    AboutCredit(
        name = "Reze",
        position = Res.string.m6_about_position_yuki,
        avatarUrl = "https://avatars.githubusercontent.com/u/203143605?v=4",
        profileUrl = "https://github.com/4nx3b",
        links = listOf(AboutLink(Res.string.m6_about_github, "https://github.com/4nx3b")),
    ),
)

private val acknowledgements = listOf(
    AboutCredit(
        name = "Zion Huang",
        position = Res.string.m6_about_position_zion_huang,
        avatarUrl = "https://avatars.githubusercontent.com/u/110614797?v=4",
        profileUrl = "https://github.com/z-huang",
        links = listOf(AboutLink(Res.string.m6_about_github, "https://github.com/z-huang")),
    ),
    AboutCredit(
        name = "MO AGAMY",
        position = Res.string.m6_about_position_mo_agamy,
        avatarUrl = "https://avatars.githubusercontent.com/u/80542861?v=4",
        profileUrl = "https://github.com/mostafaalagamy",
        links = listOf(AboutLink(Res.string.m6_about_github, "https://github.com/mostafaalagamy")),
    ),
)

/**
 * About keeps the baseline non-credit section order, the community links and the updater row gated
 * by [updaterAvailable]; the credit sections read in lineage order as noted on the lists below.
 * Build identity, the logo and member avatars are host slots, and the remote contributor,
 * translator and license lists arrive as host states. The page never starts a network request and
 * never opens a URI itself; every outward action is a callback.
 */
@Composable
fun AboutSettings(
    controller: SettingsController,
    isNightlyBuild: Boolean,
    updaterAvailable: Boolean,
    appName: String,
    versionLine: String,
    distributionLabel: String,
    debugOrAbiLabel: String,
    appLogo: @Composable () -> Unit,
    memberAvatar: @Composable (url: String, description: String, modifier: Modifier) -> Unit,
    contributorsState: AboutContributorsState,
    translationsState: AboutTranslationsState,
    licensesState: AboutLicensesState,
    activeDialog: AboutDialog,
    navigate: (String) -> Unit,
    onOpenUri: (String) -> Unit,
    onRetryContributors: () -> Unit,
    onOpenTranslations: () -> Unit,
    onRetryTranslations: () -> Unit,
    onOpenLicenses: () -> Unit,
    onRetryLicenses: () -> Unit,
    onDismissDialog: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snapshot by controller.store.snapshots.collectAsState()
    val preferences = snapshot
    if (preferences == null) { CircularProgressIndicator(); return }
    val channel = preferences.selectedUpdateChannel(isNightlyBuild)
    Box(modifier = modifier, contentAlignment = Alignment.TopCenter) {
        LazyColumn(modifier = Modifier.widthIn(max = 840.dp).fillMaxSize()) {
            item(key = "about_header") {
                AboutHeader(appName, versionLine, channel, distributionLabel, debugOrAbiLabel, appLogo)
            }
            item(key = "label_app") { AboutSectionLabel(stringResource(Res.string.m6_about_app)) }
            item(key = "about_version") { AboutRow(stringResource(Res.string.m6_about_version), icon = Res.drawable.m6_icon_info, supporting = versionLine) }
            item(key = "about_changelog") {
                AboutRow(
                    stringResource(Res.string.m6_update_changelog),
                    icon = Res.drawable.m6_icon_history,
                    onClick = { navigate("settings/changelog?channel=${channel.name}") },
                )
            }
            if (updaterAvailable) {
                item(key = "about_updates") {
                    AboutRow(stringResource(Res.string.m6_about_updates), icon = Res.drawable.m6_icon_sync, onClick = { navigate("settings/update") })
                }
            }
            item(key = "about_licenses") {
                AboutRow(stringResource(Res.string.m6_about_licenses), icon = Res.drawable.m6_icon_solar_info, onClick = onOpenLicenses)
            }

            item(key = "label_community") { AboutSectionLabel(stringResource(Res.string.m6_about_community)) }
            communityLinks.forEachIndexed { index, link ->
                item(key = "community_$index") {
                    AboutRow(
                        stringResource(link.label),
                        icon = link.icon,
                        supporting = link.url.removePrefix("https://"),
                        onClick = { onOpenUri(link.url) },
                    )
                }
            }

            item(key = "label_acknowledgements") {
                AboutSectionLabel(stringResource(Res.string.m6_about_acknowledgements))
            }
            acknowledgements.forEachIndexed { index, credit ->
                item(key = "ack_$index") { AboutCreditRow(credit, memberAvatar, onOpenUri) }
            }

            item(key = "label_lead") { AboutSectionLabel(stringResource(Res.string.m6_about_lead_developer)) }
            leadDevelopers.forEachIndexed { index, credit ->
                item(key = "lead_$index") { AboutCreditRow(credit, memberAvatar, onOpenUri) }
            }

            item(key = "label_team") { AboutSectionLabel(stringResource(Res.string.m6_about_team)) }
            teamMembers.forEachIndexed { index, credit ->
                item(key = "team_$index") { AboutCreditRow(credit, memberAvatar, onOpenUri) }
            }

            item(key = "label_translators") { AboutSectionLabel(stringResource(Res.string.m6_about_translators)) }
            item(key = "about_translation") {
                AboutRow(stringResource(Res.string.m6_about_translation), icon = Res.drawable.m6_icon_translate, onClick = onOpenTranslations)
            }

            item(key = "label_contributors") { AboutSectionLabel(stringResource(Res.string.m6_about_contributors)) }
            when (val state = contributorsState) {
                AboutContributorsState.Loading -> item(key = "contributors_status") {
                    AboutStatus(stringResource(Res.string.m6_about_loading), showRetry = false, onRetry = onRetryContributors)
                }
                AboutContributorsState.Empty -> item(key = "contributors_status") {
                    AboutStatus(stringResource(Res.string.m6_about_no_results), showRetry = true, onRetry = onRetryContributors)
                }
                is AboutContributorsState.Failed -> item(key = "contributors_status") {
                    AboutStatus(stringResource(state.message), showRetry = true, onRetry = onRetryContributors)
                }
                is AboutContributorsState.Loaded -> {
                    state.contributors.forEachIndexed { index, contributor ->
                        item(key = "contributor_$index") { AboutContributorRow(contributor, memberAvatar, onOpenUri) }
                    }
                    // Only a loaded list offers the full graph, as in the baseline.
                    item(key = "contributors_more") {
                        AboutRow(stringResource(Res.string.m6_about_more), icon = Res.drawable.m6_icon_add_circle, onClick = { onOpenUri(ContributorsReadMoreUrl) })
                    }
                }
            }
        }
    }

    when (activeDialog) {
        AboutDialog.NONE -> Unit
        AboutDialog.TRANSLATION_CONTRIBUTORS -> AboutFullScreenDialog(
            title = stringResource(Res.string.m6_about_translation),
            onDismiss = onDismissDialog,
        ) { dialogModifier ->
            TranslationsDialogContent(translationsState, onRetryTranslations, dialogModifier)
        }
        AboutDialog.DEPENDENCY_LICENSES -> AboutFullScreenDialog(
            title = stringResource(Res.string.m6_about_licenses),
            onDismiss = onDismissDialog,
        ) { dialogModifier ->
            LicensesDialogContent(licensesState, onRetryLicenses, dialogModifier)
        }
    }
}

@Composable
private fun AboutHeader(
    appName: String,
    versionLine: String,
    channel: UpdateChannel,
    distributionLabel: String,
    debugOrAbiLabel: String,
    appLogo: @Composable () -> Unit,
) {
    val stable = channel == UpdateChannel.STABLE
    val channelLabel = stringResource(
        if (stable) Res.string.m6_update_channel_stable else Res.string.m6_update_channel_nightly,
    )
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        appLogo()
        Text(
            appName,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            versionLine,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
            if (stable) {
                AboutPill(channelLabel, MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
            } else {
                AboutPill(channelLabel, MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
            }
            AboutPill(distributionLabel)
            AboutPill(debugOrAbiLabel)
        }
    }
}

@Composable
private fun AboutPill(
    text: String,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Surface(shape = RoundedCornerShape(50), color = containerColor, contentColor = contentColor) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun AboutSectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun AboutRow(
    title: String,
    icon: DrawableResource,
    supporting: String? = null,
    onClick: (() -> Unit)? = null,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = {
            if (supporting != null) Text(supporting, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        leadingContent = { Icon(painterResource(icon), contentDescription = null) },
        modifier = Modifier.clickable(enabled = onClick != null) { onClick?.invoke() },
    )
}

/** A credit: the avatar comes from the host slot, and each outward link is an icon button labelled for its destination. */
@Composable
private fun AboutCreditRow(
    credit: AboutCredit,
    memberAvatar: @Composable (url: String, description: String, modifier: Modifier) -> Unit,
    onOpenUri: (String) -> Unit,
) {
    ListItem(
        headlineContent = {
            Text(credit.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Text(stringResource(credit.position), maxLines = 2, overflow = TextOverflow.Ellipsis)
        },
        leadingContent = {
            memberAvatar(credit.avatarUrl, credit.name, Modifier.size(48.dp).clip(CircleShape))
        },
        trailingContent = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                credit.links.forEach { link ->
                    FilledTonalIconButton(
                        onClick = { onOpenUri(link.url) },
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            painter = painterResource(link.icon),
                            contentDescription = stringResource(link.label),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        },
        modifier = Modifier.clickable { onOpenUri(credit.profileUrl) },
    )
}

@Composable
private fun AboutContributorRow(
    contributor: AboutContributor,
    memberAvatar: @Composable (url: String, description: String, modifier: Modifier) -> Unit,
    onOpenUri: (String) -> Unit,
) {
    ListItem(
        headlineContent = {
            Text(contributor.login, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        leadingContent = {
            memberAvatar(contributor.avatarUrl, contributor.login, Modifier.size(48.dp).clip(CircleShape))
        },
        modifier = Modifier.clickable { onOpenUri(contributor.profileUrl) },
    )
}

/** Loading shows a spinner; empty and failed states offer a retry. Neither invents a result. */
@Composable
private fun AboutStatus(
    message: String,
    showRetry: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (!showRetry) CircularProgressIndicator(modifier = Modifier.size(32.dp))
        Text(message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (showRetry) TextButton(onClick = onRetry) { Text(stringResource(Res.string.m6_retry)) }
    }
}

@Composable
private fun AboutFullScreenDialog(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            painter = painterResource(Res.drawable.m6_icon_close),
                            contentDescription = stringResource(Res.string.m6_about_close),
                        )
                    }
                }
                content(Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun TranslationsDialogContent(
    state: AboutTranslationsState,
    onRetry: () -> Unit,
    modifier: Modifier,
) {
    when (state) {
        AboutTranslationsState.Loading -> AboutStatus(
            stringResource(Res.string.m6_about_loading), showRetry = false, onRetry = onRetry, modifier = modifier,
        )
        AboutTranslationsState.Empty -> AboutStatus(
            stringResource(Res.string.m6_about_no_results), showRetry = true, onRetry = onRetry, modifier = modifier,
        )
        is AboutTranslationsState.Failed -> AboutStatus(
            stringResource(state.message), showRetry = true, onRetry = onRetry, modifier = modifier,
        )
        is AboutTranslationsState.Loaded -> LazyColumn(modifier = modifier) {
            items(state.entries) { entry ->
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(entry.language, style = MaterialTheme.typography.bodyLarge)
                    entry.contributors?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun LicensesDialogContent(
    state: AboutLicensesState,
    onRetry: () -> Unit,
    modifier: Modifier,
) {
    when (state) {
        AboutLicensesState.Loading -> AboutStatus(
            stringResource(Res.string.m6_about_loading), showRetry = false, onRetry = onRetry, modifier = modifier,
        )
        AboutLicensesState.Empty -> AboutStatus(
            stringResource(Res.string.m6_about_no_results), showRetry = true, onRetry = onRetry, modifier = modifier,
        )
        is AboutLicensesState.Failed -> AboutStatus(
            stringResource(state.message), showRetry = true, onRetry = onRetry, modifier = modifier,
        )
        is AboutLicensesState.Loaded -> {
            var detail by remember { mutableStateOf<AboutLicenseEntry?>(null) }
            LazyColumn(modifier = modifier) {
                items(state.entries) { entry ->
                    val details = listOfNotNull(
                        entry.version,
                        entry.license ?: stringResource(Res.string.m6_about_unknown_license),
                    ).joinToString(separator = " · ")
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (entry.noticeResource != null) Modifier.clickable { detail = entry } else Modifier)
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Text(entry.name, style = MaterialTheme.typography.bodyLarge)
                        Text(details, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (state.bundledOnly) {
                    item(key = "licenses_bundled_note") {
                        Text(
                            stringResource(Res.string.m6_about_bundled_notices_only),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
            }
            detail?.let { entry ->
                val notice by produceState<String?>(initialValue = null, entry) {
                    value = AboutBundledNotices.readNotice(entry).orEmpty()
                }
                AlertDialog(
                    onDismissRequest = { detail = null },
                    confirmButton = {
                        TextButton(onClick = { detail = null }) {
                            Text(stringResource(Res.string.m6_about_close))
                        }
                    },
                    title = { Text(entry.name) },
                    text = {
                        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                            Text(
                                when (notice) {
                                    null -> stringResource(Res.string.m6_about_loading)
                                    "" -> stringResource(Res.string.m6_about_unknown_license)
                                    else -> notice.orEmpty()
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    },
                )
            }
        }
    }
}
