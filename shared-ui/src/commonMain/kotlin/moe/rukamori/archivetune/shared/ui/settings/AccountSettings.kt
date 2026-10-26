package moe.rukamori.archivetune.shared.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.domain.settings.*
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/** The account home destination. The summary's signed-in primary action opens it, as the baseline did. */
const val YouTubeAccountRoute = "account"

/** The shared browser sign-in destination. The host registers it; the page only names it. */
const val YouTubeLoginRoute = "login"

const val YouTubeHiddenPlaylistsRoute = "settings/hidden_playlists"
const val YouTubePoTokenRoute = "settings/po_token"

/**
 * YouTube account settings: the signed-in summary, saved accounts, channels, the browser and token sign-in routes,
 * the sync choices and the Misc rows.
 *
 * Each stored change is one [SettingsController] edit built by the pure policy in the domain module. Verifying a
 * candidate against the YouTube account menu, revoking the OAuth refresh token and clearing the in-memory or web
 * session are host work and arrive as callbacks. Verification that is rejected or throws leaves the stored session
 * untouched and reports [SettingsNotice] text. A host callback that completes is the only signal for a commit, so a
 * notice never announces a change that did not persist.
 *
 * The page never displays a cookie, visitor data, data-sync id, OAuth token or PO token on its own. The token editor
 * starts empty instead of prefilling stored secrets, because a prefilled block would put the secrets back on screen.
 * The only read is the explicit Reveal gesture in the account switcher: the host returns the active account's login
 * block on that tap, the block is held in memory only while its dialog is open, and it is dropped when the active
 * account changes so one account's secrets never stay on screen for another.
 *
 * [revealCredentialBlock] must return the block in the layout that [parseYouTubeManualAuth] accepts, or null when the
 * active account has no usable session. [copyToClipboard] is the host clipboard; null hides the Copy action.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun AccountSettings(
    controller: SettingsController,
    channels: List<YouTubeAccountChannel>,
    verifyAccount: suspend (YouTubeSessionCandidate) -> YouTubeAccountIdentity?,
    logOut: suspend () -> Unit,
    revokeCodeSession: suspend () -> Unit,
    onAuthCommitted: () -> Unit,
    onForceSyncAfterSwitch: () -> Unit,
    navigate: (String) -> Unit,
    onNotice: (SettingsNotice) -> Unit,
    onFailure: () -> Unit,
    scrollTo: String? = null,
    resumed: Boolean = true,
    motionEnabled: Boolean = true,
    avatar: @Composable (url: String, description: String, modifier: Modifier) -> Unit = { _, _, _ -> },
    modifier: Modifier = Modifier,
    /** Host-owned read of the active account's login block, called only after an explicit Reveal tap. Null hides it. */
    revealCredentialBlock: (suspend () -> String?)? = null,
    /** Host clipboard for the revealed block. Null hides the Copy action in the reveal dialog. */
    copyToClipboard: ((String) -> Unit)? = null,
) {
    val snapshot by controller.store.snapshots.collectAsState()
    val preferences = snapshot
    if (preferences == null) { CircularProgressIndicator(); return }
    val scope = rememberCoroutineScope()

    var busy by remember { mutableStateOf(false) }
    var switcherOpen by rememberSaveable { mutableStateOf(false) }
    var unsavedOpen by rememberSaveable { mutableStateOf(false) }
    var tokenOpen by rememberSaveable { mutableStateOf(false) }
    /** Plain remember, never rememberSaveable: a revealed login block must not reach saved instance state. */
    var revealed by remember { mutableStateOf<RevealedLoginBlock?>(null) }

    val signedIn = preferences.youTubeSignedIn()
    val identity = preferences.youTubeIdentity()
    val savedAccounts = preferences.savedYouTubeAccounts()
    val activeId = preferences.activeYouTubeSavedAccountId()
    val oauthSignedIn = preferences.youTubeOAuthSignedIn()
    val currentDataSyncId = preferences[DataSyncIdKey, ""]
    val forceSyncOnSwitch = preferences[ForceSyncOnAccountSwitchKey, false]
    val ytmSyncOn = preferences[YtmSyncKey, true]
    val hasSwitchableChannels = channels.size > 1

    /** Identifies the active account for the reveal gesture; a change drops any revealed block at once. */
    val accountKey = "${activeId.orEmpty()}|$currentDataSyncId"
    val latestAccountKey by rememberUpdatedState(accountKey)
    LaunchedEffect(accountKey) { revealed = null }

    val signedInFallback = stringResource(Res.string.m6_account_title)
    val signedOutFallback = stringResource(Res.string.m6_account_login_group)
    val displayName = identity.name.ifEmpty { if (signedIn) signedInFallback else signedOutFallback }
    val untitledAccount = stringResource(Res.string.m6_account_title)

    /** One account change at a time. A failure reports through [onFailure] and never commits a partial session. */
    fun accountAction(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                onFailure()
            } finally {
                busy = false
            }
        }
    }

    /** A candidate without a signed-in cookie or a data-sync id is rejected before the host is asked. */
    suspend fun verified(candidate: YouTubeSessionCandidate): YouTubeAccountIdentity? {
        if (!hasYouTubeSignInCookie(candidate.cookie) || candidate.dataSyncId.isBlank()) return null
        return try {
            verifyAccount(candidate)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    fun reportValidationFailure() {
        onNotice(SettingsNotice.Message(Res.string.m6_account_validation_failed))
    }

    fun openTokenEditor() {
        tokenOpen = true
    }

    fun switchToSaved(account: YouTubeSavedAccount) {
        if (account.id == activeId) return
        switcherOpen = false
        accountAction {
            val verifiedIdentity = verified(account.toSessionCandidate())
            if (verifiedIdentity == null) {
                reportValidationFailure()
                return@accountAction
            }
            controller.edit { commitYouTubeAccountSwitch(account, verifiedIdentity) }
            onAuthCommitted()
            if (forceSyncOnSwitch && account.ytmSync) onForceSyncAfterSwitch()
        }
    }

    fun switchToChannel(channel: YouTubeAccountChannel) {
        if (channel.dataSyncId == currentDataSyncId) return
        switcherOpen = false
        accountAction {
            val candidate = preferences.youTubeChannelCandidate(channel.dataSyncId)
            val verifiedIdentity = if (candidate == null) null else verified(candidate)
            if (verifiedIdentity == null) {
                reportValidationFailure()
                return@accountAction
            }
            controller.edit { commitYouTubeChannelSwitch(channel.dataSyncId, verifiedIdentity) }
            onAuthCommitted()
            if (forceSyncOnSwitch && ytmSyncOn) onForceSyncAfterSwitch()
        }
    }

    fun removeSaved(id: String) {
        accountAction { controller.edit { removeSavedYouTubeAccount(id) } }
    }

    fun saveCurrent() {
        if (!signedIn || activeId != null) return
        val id = newYouTubeSavedAccountId()
        accountAction { controller.edit { saveCurrentYouTubeAccount(id) } }
    }

    fun saveThenLogin() {
        val id = newYouTubeSavedAccountId()
        accountAction {
            controller.edit { saveCurrentYouTubeAccount(id) }
            navigate(YouTubeLoginRoute)
        }
    }

    fun addAnother() {
        switcherOpen = false
        if (signedIn && activeId == null) {
            unsavedOpen = true
        } else {
            navigate(YouTubeLoginRoute)
        }
    }

    fun logOutAccount() {
        accountAction {
            logOut()
            controller.edit { clearYouTubeSession() }
            onAuthCommitted()
        }
    }

    fun signOutCodeSession() {
        accountAction {
            try {
                revokeCodeSession()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A failed server revoke must not keep the local session, so the local clear below always runs.
            }
            controller.edit { clearYouTubeOAuthSession() }
            onAuthCommitted()
            onNotice(SettingsNotice.Message(Res.string.m6_account_oauth_signed_out))
        }
    }

    fun importToken(auth: YouTubeManualAuth) {
        accountAction {
            val verifiedIdentity = verified(auth.toSessionCandidate())
            if (verifiedIdentity == null) {
                reportValidationFailure()
                return@accountAction
            }
            controller.edit { commitYouTubeManualImport(auth, verifiedIdentity) }
            onAuthCommitted()
        }
    }

    /**
     * Reads the login block only on the tap that asked for it. The result is kept only when the active account is still
     * the one that was asked about, so a switch while the read was in flight cannot reveal another account's block.
     */
    fun revealCredentials(read: suspend () -> String?) {
        val requestedKey = accountKey
        accountAction {
            val text = read()
            if (text.isNullOrBlank()) {
                onNotice(SettingsNotice.Message(Res.string.m6_account_credentials_unavailable))
            } else if (latestAccountKey == requestedKey) {
                revealed = RevealedLoginBlock(requestedKey, text)
            }
        }
    }

    val rows = buildList<SettingsRow> {
        if (!signedIn || oauthSignedIn) {
            add(SettingsRow.Header("account_login_group", Res.string.m6_account_login_group))
        }
        if (!signedIn) {
            add(
                SettingsRow.Action(
                    anchor = "account_browser_login",
                    title = Res.string.m6_account_browser_sign_in,
                    run = { navigate(YouTubeLoginRoute) },
                    description = Res.string.m6_account_browser_sign_in_desc,
                ),
            )
        }
        if (oauthSignedIn) {
            add(
                SettingsRow.Action(
                    anchor = "account_oauth_sign_out",
                    title = Res.string.m6_account_oauth_sign_out,
                    run = { signOutCodeSession() },
                    description = Res.string.m6_account_oauth_sign_out_desc,
                ),
            )
        }
        if (signedIn) {
            add(SettingsRow.Header("account_general_group", Res.string.m6_account_general_group))
            add(
                SettingsRow.Toggle(
                    anchor = "account_more_content",
                    title = Res.string.m6_account_more_content,
                    key = UseLoginForBrowse,
                    default = true,
                    description = Res.string.m6_account_more_content_desc,
                ),
            )
            add(
                SettingsRow.Toggle(
                    anchor = "account_auto_sync",
                    title = Res.string.m6_account_auto_sync,
                    key = YtmSyncKey,
                    default = true,
                ),
            )
            add(
                SettingsRow.Toggle(
                    anchor = "account_force_sync",
                    title = Res.string.m6_account_force_sync,
                    key = ForceSyncOnAccountSwitchKey,
                    default = false,
                    description = Res.string.m6_account_force_sync_desc,
                ),
            )
        }
        add(SettingsRow.Header("account_misc_group", Res.string.m6_account_misc_group))
        add(
            SettingsRow.Action(
                anchor = "account_hidden_playlists",
                title = Res.string.m6_account_hidden_playlists,
                run = { navigate(YouTubeHiddenPlaylistsRoute) },
                description = Res.string.m6_account_hidden_playlists_desc,
            ),
        )
        add(
            SettingsRow.Action(
                anchor = "account_token",
                title = if (signedIn) Res.string.m6_account_replace_token else Res.string.m6_account_login_with_token,
                run = { openTokenEditor() },
                description = Res.string.m6_account_token_adv_description,
            ),
        )
        add(
            SettingsRow.Action(
                anchor = "account_po_token",
                title = Res.string.m6_account_po_token,
                run = { navigate(YouTubePoTokenRoute) },
                description = Res.string.m6_account_po_token_desc,
            ),
        )
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
        leadingItems = {
            item(key = "account_summary") {
                AccountSummaryCard(
                    signedIn = signedIn,
                    displayName = displayName,
                    email = identity.email,
                    handle = identity.channelHandle,
                    imageUrl = identity.imageUrl,
                    switcherEnabled = signedIn || savedAccounts.isNotEmpty() || hasSwitchableChannels,
                    avatar = avatar,
                    onPrimary = { navigate(if (signedIn) YouTubeAccountRoute else YouTubeLoginRoute) },
                    onSecondary = { if (signedIn) logOutAccount() else openTokenEditor() },
                    onSwitcher = { switcherOpen = true },
                )
            }
        },
        leadingItemCount = 1,
    )

    if (switcherOpen) {
        // The baseline switcher is a bottom sheet. Its scrim and system back both dismiss it through onDismissRequest.
        ModalBottomSheet(onDismissRequest = { switcherOpen = false }) {
            Text(
                text = stringResource(Res.string.m6_account_switch_accounts),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 600.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (hasSwitchableChannels) {
                    Text(
                        stringResource(Res.string.m6_account_channels),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    channels.forEach { channel ->
                        val selected = channel.dataSyncId == currentDataSyncId
                        AccountChoiceRow(
                            title = channel.name,
                            subtitle = channel.handle,
                            selected = selected,
                            enabled = !selected,
                            onClick = { switchToChannel(channel) },
                        )
                    }
                    HorizontalDivider()
                }
                Text(
                    stringResource(Res.string.m6_account_saved_accounts),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (savedAccounts.isEmpty()) {
                    Text(
                        stringResource(Res.string.m6_account_no_saved_accounts),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    savedAccounts.forEach { account ->
                        val active = account.id == activeId
                        AccountChoiceRow(
                            title = account.name.ifBlank { account.email }.ifBlank { untitledAccount },
                            subtitle = account.email.takeIf { it.isNotBlank() && it != account.name },
                            selected = active,
                            enabled = !active,
                            onClick = { switchToSaved(account) },
                            onRemove = { removeSaved(account.id) },
                        )
                    }
                }
                if (signedIn) {
                    HorizontalDivider()
                    FilledTonalButton(
                        onClick = { saveCurrent() },
                        enabled = activeId == null,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                    ) { Text(stringResource(Res.string.m6_account_save_current)) }
                    OutlinedButton(
                        onClick = { addAnother() },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                    ) { Text(stringResource(Res.string.m6_account_add_another)) }
                    if (revealCredentialBlock != null) {
                        // Explicit tap only: the sheet closes and the host read runs once, for the active account.
                        OutlinedButton(
                            onClick = {
                                switcherOpen = false
                                revealCredentials(revealCredentialBlock)
                            },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                        ) { Text(stringResource(Res.string.m6_account_reveal_credentials)) }
                    }
                }
            }
        }
    }

    if (unsavedOpen) {
        AlertDialog(
            onDismissRequest = { unsavedOpen = false },
            title = { Text(stringResource(Res.string.m6_account_unsaved_title)) },
            text = { Text(stringResource(Res.string.m6_account_unsaved_text)) },
            confirmButton = {
                TextButton(onClick = {
                    unsavedOpen = false
                    saveThenLogin()
                }) { Text(stringResource(Res.string.m6_account_unsaved_save)) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        unsavedOpen = false
                        navigate(YouTubeLoginRoute)
                    }) { Text(stringResource(Res.string.m6_account_unsaved_no_thanks)) }
                    TextButton(onClick = { unsavedOpen = false }) {
                        Text(stringResource(Res.string.m6_account_unsaved_cancel))
                    }
                }
            },
        )
    }

    revealed?.takeIf { it.accountKey == accountKey }?.let { reveal ->
        AlertDialog(
            onDismissRequest = { revealed = null },
            title = { Text(stringResource(Res.string.m6_account_credentials_title)) },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        stringResource(Res.string.m6_account_credentials_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(reveal.text, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                if (copyToClipboard != null) {
                    TextButton(onClick = {
                        copyToClipboard(reveal.text)
                        revealed = null
                        onNotice(SettingsNotice.Message(Res.string.m6_account_credentials_copied))
                    }) { Text(stringResource(Res.string.m6_account_credentials_copy)) }
                }
            },
            dismissButton = {
                TextButton(onClick = { revealed = null }) { Text(stringResource(Res.string.m6_done)) }
            },
        )
    }

    if (tokenOpen) {
        SettingsTextEntryDialog(
            title = if (signedIn) Res.string.m6_account_replace_token else Res.string.m6_account_login_with_token,
            placeholder = Res.string.m6_account_token_placeholder,
            multiline = true,
            masked = true,
            description = Res.string.m6_account_token_adv_description,
            isValid = { parseYouTubeManualAuth(it) != null },
            onConfirm = { text ->
                val auth = parseYouTubeManualAuth(text)
                if (auth != null) importToken(auth)
            },
            onDismiss = { tokenOpen = false },
        )
    }
}

/**
 * An explicitly revealed login block for one account. A plain class with no generated toString, so the block can
 * never be printed by accident through a data-class representation.
 */
private class RevealedLoginBlock(val accountKey: String, val text: String)

@Composable
private fun AccountSummaryCard(
    signedIn: Boolean,
    displayName: String,
    email: String?,
    handle: String?,
    imageUrl: String?,
    switcherEnabled: Boolean,
    avatar: @Composable (url: String, description: String, modifier: Modifier) -> Unit,
    onPrimary: () -> Unit,
    onSecondary: () -> Unit,
    onSwitcher: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = displayName.take(1).uppercase(),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    if (signedIn && imageUrl != null) {
                        avatar(imageUrl, displayName, Modifier.fillMaxSize())
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (signedIn && handle != null) {
                        Text(
                            text = handle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    val subline = if (signedIn) email else stringResource(Res.string.m6_account_not_logged_in)
                    if (subline != null) {
                        Text(
                            text = subline,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onPrimary,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Text(stringResource(if (signedIn) Res.string.m6_account_title else Res.string.m6_account_login_group))
                }
                FilledTonalIconButton(onClick = onSwitcher, enabled = switcherEnabled) {
                    Icon(
                        painter = painterResource(Res.drawable.m6_icon_expand_more),
                        contentDescription = stringResource(Res.string.m6_account_switch_accounts),
                    )
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(
                    onClick = onSecondary,
                    colors = if (signedIn) {
                        ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    } else {
                        ButtonDefaults.textButtonColors()
                    },
                ) {
                    Text(
                        stringResource(
                            if (signedIn) Res.string.m6_action_logout else Res.string.m6_account_login_with_token,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun AccountChoiceRow(
    title: String,
    subtitle: String?,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    onRemove: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (selected) {
            Text(
                text = stringResource(Res.string.m6_account_current),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        onRemove?.let { remove ->
            IconButton(onClick = remove) {
                Icon(
                    painter = painterResource(Res.drawable.m6_icon_close),
                    contentDescription = stringResource(Res.string.m6_account_remove),
                )
            }
        }
    }
}
