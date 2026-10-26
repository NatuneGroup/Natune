package moe.rukamori.archivetune.shared.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.domain.settings.*
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/** Route of the Discord experimental page (translator and activity buttons). The integrator registers it here. */
const val DiscordExperimentalRoute = "settings/discord/experimental"

/** Progress of the browser authorization, shown in the account card. The host runs the PKCE exchange. */
enum class DiscordAuthorizationUiMode { IDLE, WAITING, SUCCESS, FAILURE }

/**
 * Discord Rich Presence settings. [authorize] launches the host's PKCE browser flow and suspends until it returns the
 * session, or null when the user backs out; the page then commits the tokens through the logical vault in one edit.
 * [verifyAccount] checks the saved token when the page opens and whenever the token changes. [refreshPresence] is a
 * manual refresh. [signOut] drops the host's in-memory tokens before the page clears the saved session. The access
 * token is never displayed. PKCE, the Gateway, presence updates and cached artwork cleanup stay host-owned.
 */
@Composable
fun DiscordSettings(
    controller: SettingsController,
    presenceSupported: Boolean,
    currentTimeMillis: () -> Long,
    authorize: suspend () -> DiscordSessionFacts?,
    verifyAccount: suspend (accessToken: String) -> DiscordAccountCheck,
    refreshPresence: suspend () -> Boolean,
    signOut: suspend () -> Unit,
    navigate: (String) -> Unit,
    onNotice: (SettingsNotice) -> Unit,
    onFailure: () -> Unit,
    scrollTo: String? = null,
    resumed: Boolean = true,
    motionEnabled: Boolean = true,
    avatar: @Composable (url: String, description: String, modifier: Modifier) -> Unit = { _, _, _ -> },
    richPresencePreview: @Composable () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val snapshot by controller.store.snapshots.collectAsState()
    val preferences = snapshot
    if (preferences == null) { CircularProgressIndicator(); return }
    val scope = rememberCoroutineScope()

    var authorization by remember { mutableStateOf(DiscordAuthorizationUiMode.IDLE) }
    var authJob by remember { mutableStateOf<Job?>(null) }
    var savedAuthorization by remember { mutableStateOf(DiscordSavedAuthorization.MISSING) }
    var refreshing by remember { mutableStateOf(false) }
    var logoutOpen by remember { mutableStateOf(false) }
    var nowMillis by remember { mutableLongStateOf(currentTimeMillis()) }

    val token = preferences[DiscordTokenKey, ""].trim()
    val expiresAt = preferences.discordTokenExpiresAt()
    val rpcEnabled = preferences.discordRichPresenceEnabled()
    val expired = token.isNotEmpty() && expiresAt > 0L && nowMillis >= expiresAt
    val loggedIn = token.isNotEmpty() && !expired && savedAuthorization == DiscordSavedAuthorization.LINKED
    val profile = preferences.discordAccount()

    // One timer to the stored expiry, so an expired token stops reading as connected without any polling.
    LaunchedEffect(expiresAt) {
        nowMillis = currentTimeMillis()
        if (expiresAt > nowMillis) {
            delay(expiresAt - nowMillis)
            nowMillis = currentTimeMillis()
        }
    }

    LaunchedEffect(token, expiresAt) {
        if (token.isEmpty()) {
            savedAuthorization = DiscordSavedAuthorization.MISSING
            return@LaunchedEffect
        }
        if (expiresAt > 0L && currentTimeMillis() >= expiresAt) {
            savedAuthorization = DiscordSavedAuthorization.EXPIRED
            return@LaunchedEffect
        }
        savedAuthorization = DiscordSavedAuthorization.CHECKING
        val check = try {
            verifyAccount(token)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            DiscordAccountCheck.Unverified
        }
        savedAuthorization = when (check) {
            is DiscordAccountCheck.Verified -> {
                try {
                    controller.edit { commitDiscordProfile(check.account) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    onFailure()
                }
                DiscordSavedAuthorization.LINKED
            }
            DiscordAccountCheck.Rejected -> DiscordSavedAuthorization.REJECTED
            DiscordAccountCheck.Unverified -> DiscordSavedAuthorization.UNVERIFIED
        }
    }

    LaunchedEffect(authorization) {
        if (authorization == DiscordAuthorizationUiMode.SUCCESS || authorization == DiscordAuthorizationUiMode.FAILURE) {
            delay(2_600L)
            authorization = DiscordAuthorizationUiMode.IDLE
        }
    }

    fun startAuthorization() {
        authJob?.cancel()
        authorization = DiscordAuthorizationUiMode.WAITING
        authJob = scope.launch {
            val session = try {
                authorize()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                authorization = DiscordAuthorizationUiMode.FAILURE
                return@launch
            }
            if (session == null) {
                authorization = DiscordAuthorizationUiMode.IDLE
                return@launch
            }
            try {
                controller.edit { commitDiscordSignIn(session) }
                authorization = DiscordAuthorizationUiMode.SUCCESS
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                authorization = DiscordAuthorizationUiMode.FAILURE
                onFailure()
            }
        }
    }

    fun confirmSignOut() {
        logoutOpen = false
        authJob?.cancel()
        authorization = DiscordAuthorizationUiMode.IDLE
        scope.launch {
            try {
                signOut()
                controller.edit { clearDiscordSession() }
                savedAuthorization = DiscordSavedAuthorization.MISSING
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                onFailure()
            }
        }
    }

    fun refreshNow() {
        scope.launch {
            refreshing = true
            val refreshed = try {
                refreshPresence()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            refreshing = false
            onNotice(
                SettingsNotice.Message(
                    if (refreshed) Res.string.m6_discord_refresh_success else Res.string.m6_discord_refresh_failed,
                ),
            )
        }
    }

    val accountTitle = when {
        loggedIn && profile.displayName.isNotEmpty() -> profile.displayName
        loggedIn && profile.username.isNotEmpty() -> profile.username
        loggedIn -> stringResource(Res.string.m6_discord_group_account)
        expired || savedAuthorization == DiscordSavedAuthorization.EXPIRED ->
            stringResource(Res.string.m6_discord_saved_auth_expired)
        savedAuthorization == DiscordSavedAuthorization.CHECKING -> stringResource(Res.string.m6_discord_saved_auth_checking)
        savedAuthorization == DiscordSavedAuthorization.REJECTED -> stringResource(Res.string.m6_discord_saved_auth_rejected)
        savedAuthorization == DiscordSavedAuthorization.UNVERIFIED ->
            stringResource(Res.string.m6_discord_saved_auth_unverified)
        else -> stringResource(Res.string.m6_discord_not_logged_in)
    }
    val savedMessage: StringResource? = when {
        expired || savedAuthorization == DiscordSavedAuthorization.EXPIRED ->
            Res.string.m6_discord_saved_auth_expired_message
        savedAuthorization == DiscordSavedAuthorization.REJECTED -> Res.string.m6_discord_saved_auth_rejected_message
        savedAuthorization == DiscordSavedAuthorization.UNVERIFIED -> Res.string.m6_discord_saved_auth_unverified_message
        else -> null
    }

    val rows = buildList<SettingsRow> {
        add(
            SettingsRow.Toggle(
                anchor = "enable_discord_rpc",
                title = Res.string.m6_discord_enable_rpc,
                key = EnableDiscordRPCKey,
                default = DiscordSettingsDefaults.RICH_PRESENCE_ENABLED,
                enabled = (rpcEnabled && !presenceSupported) || (loggedIn && presenceSupported),
            ),
        )
        add(SettingsRow.Header("discord_options", Res.string.m6_discord_group_options))
        add(
            SettingsRow.Action(
                anchor = "discord_refresh",
                title = Res.string.m6_refresh,
                run = { refreshNow() },
                enabled = presenceSupported && rpcEnabled && loggedIn && !refreshing,
                description = if (presenceSupported) {
                    Res.string.m6_discord_refresh_description
                } else {
                    Res.string.m6_discord_presence_unavailable_title
                },
            ),
        )
        add(
            SettingsRow.Action(
                anchor = "discord_experimental",
                title = Res.string.m6_discord_experiment_settings,
                run = { navigate(DiscordExperimentalRoute) },
            ),
        )
        add(SettingsRow.Header("discord_connection", Res.string.m6_discord_group_connection))
        add(
            SettingsRow.Choice(
                anchor = "activity_status",
                title = Res.string.m6_discord_activity_status,
                key = DiscordPresenceStatusKey,
                default = DiscordSettingsDefaults.PRESENCE_STATUS,
                options = discordPresenceStatusOptions,
            ),
        )
        add(
            SettingsRow.Choice(
                anchor = "platform_status",
                title = Res.string.m6_discord_platform,
                key = DiscordActivityPlatformKey,
                default = DiscordSettingsDefaults.PLATFORM,
                options = discordPlatformOptions,
            ),
        )
        add(SettingsRow.Header("discord_activity", Res.string.m6_discord_group_activity))
        add(
            SettingsRow.Choice(
                anchor = "discord_activity_name",
                title = Res.string.m6_discord_activity_name,
                key = DiscordActivityNameKey,
                default = DiscordSettingsDefaults.ACTIVITY_NAME_SOURCE.name,
                options = discordActivitySourceOptions,
            ),
        )
        add(
            SettingsRow.Choice(
                anchor = "discord_activity_details",
                title = Res.string.m6_discord_activity_details,
                key = DiscordActivityDetailsKey,
                default = DiscordSettingsDefaults.ACTIVITY_DETAILS_SOURCE.name,
                options = discordActivitySourceOptions,
            ),
        )
        add(
            SettingsRow.Choice(
                anchor = "discord_activity_state",
                title = Res.string.m6_discord_activity_state,
                key = DiscordActivityStateKey,
                default = DiscordSettingsDefaults.ACTIVITY_STATE_SOURCE.name,
                options = discordActivitySourceOptions,
            ),
        )
        add(
            SettingsRow.Toggle(
                anchor = "discord_show_when_paused",
                title = Res.string.m6_discord_show_when_paused,
                key = DiscordShowWhenPausedKey,
                default = DiscordSettingsDefaults.SHOW_WHEN_PAUSED,
                description = Res.string.m6_discord_show_when_paused_description,
            ),
        )
        add(
            SettingsRow.Choice(
                anchor = "discord_activity_type",
                title = Res.string.m6_discord_activity_type,
                key = DiscordActivityTypeKey,
                default = DiscordSettingsDefaults.ACTIVITY_TYPE,
                options = discordActivityTypeOptions,
            ),
        )
        add(SettingsRow.Header("discord_images", Res.string.m6_discord_group_images))
        add(
            SettingsRow.Choice(
                anchor = "large_image",
                title = Res.string.m6_discord_large_image,
                key = DiscordLargeImageTypeKey,
                default = DiscordSettingsDefaults.LARGE_IMAGE,
                options = discordLargeImageOptions,
            ),
        )
        if (preferences[DiscordLargeImageTypeKey, DiscordSettingsDefaults.LARGE_IMAGE] == "custom") {
            add(
                SettingsRow.Text(
                    anchor = "large_image_custom_url",
                    title = Res.string.m6_discord_large_image_custom_url,
                    key = DiscordLargeImageCustomUrlKey,
                ),
            )
        }
        add(
            SettingsRow.Choice(
                anchor = "large_text",
                title = Res.string.m6_discord_large_text,
                key = DiscordLargeTextSourceKey,
                default = DiscordSettingsDefaults.LARGE_TEXT,
                options = discordLargeTextOptions,
            ),
        )
        if (preferences[DiscordLargeTextSourceKey, DiscordSettingsDefaults.LARGE_TEXT] == "custom") {
            add(
                SettingsRow.Text(
                    anchor = "custom_large_text",
                    title = Res.string.m6_discord_custom_large_text,
                    key = DiscordLargeTextCustomKey,
                ),
            )
        }
        add(
            SettingsRow.Choice(
                anchor = "small_image",
                title = Res.string.m6_discord_small_image,
                key = DiscordSmallImageTypeKey,
                default = DiscordSettingsDefaults.SMALL_IMAGE,
                options = discordSmallImageOptions,
            ),
        )
        if (preferences[DiscordSmallImageTypeKey, DiscordSettingsDefaults.SMALL_IMAGE] == "custom") {
            add(
                SettingsRow.Text(
                    anchor = "small_image_custom_url",
                    title = Res.string.m6_discord_small_image_custom_url,
                    key = DiscordSmallImageCustomUrlKey,
                ),
            )
        }
    }

    if (logoutOpen) {
        AlertDialog(
            onDismissRequest = { logoutOpen = false },
            title = { Text(stringResource(Res.string.m6_discord_logout_title)) },
            text = { Text(stringResource(Res.string.m6_discord_logout_message)) },
            confirmButton = {
                TextButton(onClick = { confirmSignOut() }) { Text(stringResource(Res.string.m6_action_logout)) }
            },
            dismissButton = {
                TextButton(onClick = { logoutOpen = false }) { Text(stringResource(Res.string.m6_cancel)) }
            },
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
            item {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = stringResource(Res.string.m6_discord_group_account),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    DiscordAccountCard(
                        loggedIn = loggedIn,
                        title = accountTitle,
                        username = profile.username,
                        avatarUrl = profile.avatarUrl.orEmpty(),
                        avatar = avatar,
                        authorization = authorization,
                        expired = expired,
                        savedMessage = savedMessage,
                        onAuthorize = { startAuthorization() },
                        onCancelAuthorization = {
                            authJob?.cancel()
                            authorization = DiscordAuthorizationUiMode.IDLE
                        },
                        onLogout = { logoutOpen = true },
                        onReauthorize = { startAuthorization() },
                    )
                    if (!presenceSupported) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = stringResource(Res.string.m6_discord_presence_unavailable_title),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                text = stringResource(Res.string.m6_discord_presence_unavailable_description),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        },
        leadingItemCount = 1,
        // The host's Rich Presence preview shares the page scroll and renders after the rows.
        extraItems = { item { richPresencePreview() } },
    )
}

@Composable
private fun DiscordAccountCard(
    loggedIn: Boolean,
    title: String,
    username: String,
    avatarUrl: String,
    avatar: @Composable (url: String, description: String, modifier: Modifier) -> Unit,
    authorization: DiscordAuthorizationUiMode,
    expired: Boolean,
    savedMessage: StringResource?,
    onAuthorize: () -> Unit,
    onCancelAuthorization: () -> Unit,
    onLogout: () -> Unit,
    onReauthorize: () -> Unit,
) {
    val summary = when (authorization) {
        DiscordAuthorizationUiMode.WAITING -> Res.string.m6_discord_waiting_for_authorization
        DiscordAuthorizationUiMode.SUCCESS -> Res.string.m6_discord_authorization_success
        DiscordAuthorizationUiMode.FAILURE -> Res.string.m6_discord_authorization_failed
        DiscordAuthorizationUiMode.IDLE ->
            if (loggedIn) Res.string.m6_discord_account_ready else Res.string.m6_discord_login_description
    }
    val panelColor = when (authorization) {
        DiscordAuthorizationUiMode.WAITING -> MaterialTheme.colorScheme.secondaryContainer
        DiscordAuthorizationUiMode.SUCCESS -> MaterialTheme.colorScheme.primaryContainer
        DiscordAuthorizationUiMode.FAILURE -> MaterialTheme.colorScheme.errorContainer
        DiscordAuthorizationUiMode.IDLE -> MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val panelContentColor = when (authorization) {
        DiscordAuthorizationUiMode.WAITING -> MaterialTheme.colorScheme.onSecondaryContainer
        DiscordAuthorizationUiMode.SUCCESS -> MaterialTheme.colorScheme.onPrimaryContainer
        DiscordAuthorizationUiMode.FAILURE -> MaterialTheme.colorScheme.onErrorContainer
        DiscordAuthorizationUiMode.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val shownAvatar = avatarUrl.takeIf { loggedIn && it.isNotBlank() }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.size(88.dp), contentAlignment = Alignment.Center) {
                    if (shownAvatar != null) {
                        avatar(shownAvatar, title, Modifier.fillMaxSize())
                    } else {
                        Icon(
                            painter = painterResource(Res.drawable.m6_icon_discord),
                            contentDescription = null,
                            modifier = Modifier.size(40.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (loggedIn && username.isNotBlank()) {
                        Text(
                            text = "@$username",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            if (authorization != DiscordAuthorizationUiMode.IDLE || loggedIn) {
                Surface(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = panelColor) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (authorization == DiscordAuthorizationUiMode.WAITING) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), color = panelContentColor)
                        }
                        Text(
                            text = stringResource(summary),
                            style = MaterialTheme.typography.bodyMedium,
                            color = panelContentColor,
                        )
                    }
                }
            }

            savedMessage?.let {
                Text(
                    text = stringResource(it),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            when {
                loggedIn -> OutlinedButton(
                    onClick = onLogout,
                    enabled = authorization != DiscordAuthorizationUiMode.WAITING,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                ) { Text(stringResource(Res.string.m6_action_logout)) }
                authorization == DiscordAuthorizationUiMode.WAITING -> OutlinedButton(
                    onClick = onCancelAuthorization,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                ) { Text(stringResource(Res.string.m6_cancel)) }
                else -> Button(
                    onClick = onAuthorize,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                ) { Text(stringResource(Res.string.m6_discord_open_authorization)) }
            }

            if (expired && authorization == DiscordAuthorizationUiMode.IDLE) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.errorContainer,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(Res.string.m6_discord_reauthorize_required_title),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            Text(
                                text = stringResource(Res.string.m6_discord_reauthorize_required_description),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                        TextButton(onClick = onReauthorize) {
                            Text(
                                text = stringResource(Res.string.m6_discord_reauthorize_action),
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun activitySourceLabel(source: DiscordActivitySource): StringResource = when (source) {
    DiscordActivitySource.ARTIST -> Res.string.m6_discord_source_artist
    DiscordActivitySource.ALBUM -> Res.string.m6_discord_source_album
    DiscordActivitySource.SONG -> Res.string.m6_discord_source_song
    DiscordActivitySource.APP -> Res.string.m6_discord_source_app
}

private fun presenceStatusLabel(value: String): StringResource = when (value) {
    "dnd" -> Res.string.m6_discord_status_dnd
    "idle" -> Res.string.m6_discord_status_idle
    "streaming" -> Res.string.m6_discord_status_streaming
    else -> Res.string.m6_discord_status_online
}

private fun platformLabel(value: String): StringResource = when (value) {
    "desktop" -> Res.string.m6_discord_platform_desktop
    "xbox" -> Res.string.m6_discord_platform_xbox
    "samsung" -> Res.string.m6_discord_platform_samsung
    "ios" -> Res.string.m6_discord_platform_ios
    "embedded" -> Res.string.m6_discord_platform_embedded
    "ps4" -> Res.string.m6_discord_platform_ps4
    "ps5" -> Res.string.m6_discord_platform_ps5
    else -> Res.string.m6_discord_platform_android
}

private fun activityTypeLabel(value: String): StringResource = when (value) {
    "PLAYING" -> Res.string.m6_discord_activity_type_playing
    "STREAMING" -> Res.string.m6_discord_activity_type_streaming
    "WATCHING" -> Res.string.m6_discord_activity_type_watching
    "COMPETING" -> Res.string.m6_discord_activity_type_competing
    else -> Res.string.m6_discord_activity_type_listening
}

private fun imageLabel(value: String): StringResource = when (value) {
    "artist" -> Res.string.m6_discord_image_artist_artwork
    "appicon" -> Res.string.m6_app_icon
    "custom" -> Res.string.m6_custom
    "dontshow" -> Res.string.m6_discord_dont_show
    else -> Res.string.m6_discord_image_album_artwork
}

private fun largeTextLabel(value: String): StringResource = when (value) {
    "artist" -> Res.string.m6_discord_source_artist
    "album" -> Res.string.m6_discord_source_album
    "app" -> Res.string.m6_discord_source_app
    "custom" -> Res.string.m6_custom
    "dontshow" -> Res.string.m6_discord_dont_show
    else -> Res.string.m6_discord_source_song
}

// Option lists come from the domain value lists, so the page never offers a value the policy does not know.
private val discordActivitySourceOptions: List<SettingsRow.Option> =
    DiscordActivitySource.entries.map { SettingsRow.Option(it.name, activitySourceLabel(it)) }
private val discordPresenceStatusOptions: List<SettingsRow.Option> =
    DiscordPresenceStatusValues.map { SettingsRow.Option(it, presenceStatusLabel(it)) }
private val discordPlatformOptions: List<SettingsRow.Option> =
    DiscordPlatformValues.map { SettingsRow.Option(it, platformLabel(it)) }
private val discordActivityTypeOptions: List<SettingsRow.Option> =
    DiscordActivityTypeValues.map { SettingsRow.Option(it, activityTypeLabel(it)) }
private val discordLargeImageOptions: List<SettingsRow.Option> =
    DiscordLargeImageValues.map { SettingsRow.Option(it, imageLabel(it)) }
private val discordSmallImageOptions: List<SettingsRow.Option> =
    DiscordSmallImageValues.map { SettingsRow.Option(it, imageLabel(it)) }
private val discordLargeTextOptions: List<SettingsRow.Option> =
    DiscordLargeTextValues.map { SettingsRow.Option(it, largeTextLabel(it)) }
