/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * Portions © 4nx3b — github.com/4nx3b
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * Settings for the experimental Spotify streaming source. The screen exists separately from the
 * catalog's Spotify settings because it is a separate account: what is signed in here only ever
 * streams, and the catalog sign-in is neither shown nor touched.
 */

package moe.rukamori.archivetune.ui.screens.settings

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.LocalPlayerAwareWindowInsets
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.SpotifySpDcKey
import moe.rukamori.archivetune.constants.SpotifyStreamAccountNameKey
import moe.rukamori.archivetune.constants.SpotifyStreamEnabledKey
import moe.rukamori.archivetune.constants.SpotifyStreamResolveMethod
import moe.rukamori.archivetune.constants.SpotifyStreamResolveMethodKey
import moe.rukamori.archivetune.constants.SpotifyStreamSpDcKey
import moe.rukamori.archivetune.constants.SpotifyStreamUserIdKey
import moe.rukamori.archivetune.shared.domain.source.SpotifyPremiumPolicy
import moe.rukamori.archivetune.shared.domain.source.SpotifyProduct
import moe.rukamori.archivetune.shared.domain.source.SpotifyStreamEngine
import moe.rukamori.archivetune.spotify.SpotifyLibrespotAccount
import moe.rukamori.archivetune.spotify.SpotifyLibrespotAuthCoordinator
import moe.rukamori.archivetune.spotify.SpotifyLibrespotAccountNameKey
import moe.rukamori.archivetune.spotify.SpotifyLibrespotRefreshTokenKey
import moe.rukamori.archivetune.spotify.SpotifyProductKey
import moe.rukamori.archivetune.spotify.SpotifyStreamEngineKey
import moe.rukamori.archivetune.spotify.SpotifyStreamingAccount
import moe.rukamori.archivetune.ui.component.DefaultDialog
import moe.rukamori.archivetune.ui.component.EnumListPreference
import moe.rukamori.archivetune.ui.component.IconButton
import moe.rukamori.archivetune.ui.component.PreferenceEntry
import moe.rukamori.archivetune.ui.component.PreferenceGroup
import moe.rukamori.archivetune.ui.component.SettingsTopAppBar
import moe.rukamori.archivetune.ui.component.SwitchPreference
import moe.rukamori.archivetune.ui.utils.backToMain
import moe.rukamori.archivetune.utils.rememberEnumPreference
import moe.rukamori.archivetune.utils.rememberPreference

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpotifyStreamingSettings(
    navController: NavController,
    scrollTo: String? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val (enabledRaw, onEnabledChangeRaw) = rememberPreference(SpotifyStreamEnabledKey, false)
    val (spDc, _) = rememberPreference(SpotifyStreamSpDcKey, "")
    val (accountName, _) = rememberPreference(SpotifyStreamAccountNameKey, "")
    val (userId, _) = rememberPreference(SpotifyStreamUserIdKey, "")
    val (method, onMethodChange) =
        rememberEnumPreference(SpotifyStreamResolveMethodKey, SpotifyStreamResolveMethod.Default)
    val (engine, onEngineChange) =
        rememberEnumPreference(SpotifyStreamEngineKey, SpotifyStreamEngine.Default)
    val (librespotRefresh, _) = rememberPreference(SpotifyLibrespotRefreshTokenKey, "")
    val (librespotAccountName, _) = rememberPreference(SpotifyLibrespotAccountNameKey, "")
    val (catalogSpDc, _) = rememberPreference(SpotifySpDcKey, "")
    val (libraryProduct, _) = rememberPreference(SpotifyProductKey, "")

    val catalogSignedIn = catalogSpDc.isNotBlank()
    val librespotSignedIn = librespotRefresh.isNotBlank()
    val effectiveEngine = SpotifyPremiumPolicy.effectiveEngine(engine, SpotifyProduct.of(libraryProduct))
    val signedIn =
        when (engine) {
            SpotifyStreamEngine.UNOFFICIAL -> spDc.isNotBlank()
            SpotifyStreamEngine.LIBRESPOT -> librespotSignedIn
        }
    val enabled = enabledRaw && signedIn
    var showLogin by remember { mutableStateOf(false) }
    var verifying by remember { mutableStateOf(false) }
    var librespotVerifying by remember { mutableStateOf(false) }
    var signInError by remember { mutableStateOf<String?>(null) }

    // The OAuth redirect arrives through SpotifyLibrespotCallbackActivity as long as the screen is
    // alive; the pending grant itself lives in the account object, so a rotated screen still completes
    // the flow. A redirect whose state is not the pending one is ignored, not completed.
    LaunchedEffect(Unit) {
        // Sequential collect, not collectLatest: a second redirect must wait for the in-flight
        // completion rather than cancelling it halfway through its datastore commit.
        SpotifyLibrespotAuthCoordinator.redirects.collect { redirect ->
            if (redirect.getQueryParameter("state") != SpotifyLibrespotAccount.pendingAuthorizationState) {
                return@collect
            }
            librespotVerifying = true
            try {
                val result = SpotifyLibrespotAccount.completeAuthorization(context, redirect)
                result.exceptionOrNull()?.let { error ->
                    signInError =
                        when (error) {
                            is SpotifyLibrespotAccount.SignInError.NotPremium ->
                                context.getString(R.string.spotify_stream_error_not_premium)
                            is SpotifyLibrespotAccount.SignInError.DifferentFromCatalog ->
                                context.getString(R.string.spotify_stream_error_different_account)
                            is SpotifyLibrespotAccount.SignInError.CatalogUnverifiable ->
                                context.getString(R.string.spotify_stream_librespot_needs_catalog)
                            is SpotifyLibrespotAccount.SignInError.NotConfigured ->
                                context.getString(R.string.spotify_stream_error_not_configured)
                            else -> context.getString(R.string.spotify_stream_error_oauth)
                        }
                }
            } finally {
                librespotVerifying = false
            }
        }
    }

    // The toggle flips only its own flag. Spotify sits in the source order like every other
    // source now; the user positions it in "Preferred sources", and while signed out the
    // resolver treats the source as disabled anyway.
    val onEnabledChange: (Boolean) -> Unit = { next ->
        if (!next || signedIn) {
            onEnabledChangeRaw(next)
        }
    }

    if (showLogin) {
        SpotifyLoginSheet(
            onDismiss = { showLogin = false },
            title = stringResource(R.string.spotify_stream_login_title),
            isolatedProfile = SpotifyStreamingAccount.WEBVIEW_PROFILE,
            onCookiesCaptured = { capturedSpDc, capturedSpKey ->
                showLogin = false
                verifying = true
                scope.launch {
                    val result = SpotifyStreamingAccount.signIn(context, capturedSpDc, capturedSpKey)
                    verifying = false
                    result.exceptionOrNull()?.let { error ->
                        signInError =
                            when (error) {
                                is SpotifyStreamingAccount.SignInError.SameAsCatalog ->
                                    context.getString(R.string.spotify_stream_error_same_account)
                                is SpotifyStreamingAccount.SignInError.CatalogUnverifiable ->
                                    context.getString(R.string.spotify_stream_error_catalog_unverifiable)
                                is SpotifyStreamingAccount.SignInError.InvalidSession ->
                                    context.getString(R.string.spotify_stream_error_session)
                                else -> context.getString(R.string.spotify_stream_error_profile)
                            }
                    }
                }
            },
        )
    }

    signInError?.let { message ->
        DefaultDialog(
            onDismiss = { signInError = null },
            title = { Text(stringResource(R.string.spotify_stream_sign_in_failed)) },
            buttons = {
                TextButton(onClick = { signInError = null }, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(android.R.string.ok))
                }
            },
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            SettingsTopAppBar(
                title = { Text(stringResource(R.string.source_spotify_stream)) },
                navigationIcon = {
                    IconButton(
                        onClick = navController::navigateUp,
                        onLongClick = navController::backToMain,
                    ) {
                        Icon(painterResource(R.drawable.arrow_back), contentDescription = null)
                    }
                },
            )
        },
    ) { innerPadding ->
        val playerAwareBottomPadding =
            LocalPlayerAwareWindowInsets.current
                .only(WindowInsetsSides.Bottom)
                .asPaddingValues()
                .calculateBottomPadding()
        val scrollState = rememberScrollState()
        val positions = rememberPreferencePositions()
        LaunchedEffect(scrollTo) { positions.scrollToKey(scrollTo, scrollState) }

        Column(
            Modifier
                .padding(top = innerPadding.calculateTopPadding())
                .windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal))
                .then(positions.containerModifier())
                .verticalScroll(scrollState)
                .padding(bottom = playerAwareBottomPadding + 16.dp),
        ) {
            SpotifyStreamCard(
                modifier =
                    Modifier
                        .then(positions.modifierFor("spotify_stream_warning"))
                        .padding(horizontal = SettingsDimensions.ScreenHorizontalPadding)
                        .padding(top = 12.dp, bottom = 4.dp),
                container = MaterialTheme.colorScheme.errorContainer,
                icon = R.drawable.solar_danger_circle_linear,
                iconTint = MaterialTheme.colorScheme.onErrorContainer,
            ) {
                Text(
                    text = stringResource(R.string.spotify_stream_warning_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    text = stringResource(R.string.spotify_stream_warning_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }

            PreferenceGroup(title = stringResource(R.string.spotify_stream_account)) {
                if (engine == SpotifyStreamEngine.LIBRESPOT) {
                    if (librespotSignedIn) {
                        item {
                            PreferenceEntry(
                                modifier = positions.modifierFor("spotify_stream_account"),
                                title = {
                                    Text(
                                        stringResource(
                                            R.string.spotify_stream_librespot_signed_in,
                                            librespotAccountName.ifBlank { "?" },
                                        ),
                                    )
                                },
                                description = stringResource(R.string.spotify_stream_librespot_signed_in_desc),
                                icon = { Icon(painterResource(R.drawable.spotify_icon), null) },
                                isEnabled = false,
                            )
                        }
                        item {
                            PreferenceEntry(
                                title = { Text(stringResource(R.string.spotify_stream_librespot_sign_out)) },
                                icon = { Icon(painterResource(R.drawable.logout), null) },
                                onClick = {
                                    scope.launch { SpotifyLibrespotAccount.signOut(context) }
                                },
                            )
                        }
                    } else {
                        item {
                            PreferenceEntry(
                                modifier = positions.modifierFor("spotify_stream_account"),
                                title = { Text(stringResource(R.string.spotify_stream_librespot_sign_in)) },
                                description =
                                    when {
                                        !SpotifyLibrespotAccount.isConfigured ->
                                            stringResource(R.string.spotify_stream_error_not_configured)
                                        !catalogSignedIn ->
                                            stringResource(R.string.spotify_stream_librespot_needs_catalog)
                                        else -> stringResource(R.string.spotify_stream_librespot_sign_in_desc)
                                    },
                                icon = { Icon(painterResource(R.drawable.login), null) },
                                trailingContent =
                                    if (librespotVerifying) {
                                        { CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp) }
                                    } else {
                                        null
                                    },
                                isEnabled =
                                    SpotifyLibrespotAccount.isConfigured && catalogSignedIn &&
                                        !librespotVerifying,
                                onClick = {
                                    scope.launch {
                                        SpotifyLibrespotAccount.beginAuthorization()?.let { session ->
                                            context.startActivity(
                                                Intent(Intent.ACTION_VIEW, session.authorizationUri),
                                            )
                                        }
                                    }
                                },
                            )
                        }
                    }
                } else if (signedIn) {
                    item {
                        PreferenceEntry(
                            modifier = positions.modifierFor("spotify_stream_account"),
                            title = {
                                Text(
                                    stringResource(
                                        R.string.spotify_stream_signed_in,
                                        accountName.ifBlank { userId.ifBlank { "?" } },
                                    ),
                                )
                            },
                            description = stringResource(R.string.spotify_stream_signed_in_desc),
                            icon = { Icon(painterResource(R.drawable.spotify_icon), null) },
                            isEnabled = false,
                        )
                    }
                    item {
                        PreferenceEntry(
                            title = { Text(stringResource(R.string.spotify_stream_sign_out)) },
                            icon = { Icon(painterResource(R.drawable.logout), null) },
                            onClick = {
                                scope.launch { SpotifyStreamingAccount.signOut(context) }
                            },
                        )
                    }
                } else {
                    item {
                        PreferenceEntry(
                            modifier = positions.modifierFor("spotify_stream_account"),
                            title = { Text(stringResource(R.string.spotify_stream_sign_in)) },
                            description = stringResource(R.string.spotify_stream_sign_in_desc),
                            icon = { Icon(painterResource(R.drawable.login), null) },
                            trailingContent =
                                if (verifying) {
                                    { CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp) }
                                } else {
                                    null
                                },
                            isEnabled = !verifying,
                            onClick = { showLogin = true },
                        )
                    }
                }
            }

            PreferenceGroup(title = stringResource(R.string.source_spotify_stream)) {
                item {
                    val librespotSelectable =
                        catalogSignedIn &&
                            SpotifyPremiumPolicy.librespotAllowed(SpotifyProduct.of(libraryProduct))
                    EnumListPreference(
                        modifier = positions.modifierFor("spotify_stream_engine"),
                        title = { Text(stringResource(R.string.spotify_stream_engine)) },
                        icon = { Icon(painterResource(R.drawable.spotify_icon), null) },
                        selectedValue = engine,
                        valueText = {
                            when (it) {
                                SpotifyStreamEngine.UNOFFICIAL ->
                                    stringResource(R.string.spotify_stream_engine_unofficial)
                                SpotifyStreamEngine.LIBRESPOT ->
                                    stringResource(R.string.spotify_stream_engine_librespot)
                            }
                        },
                        valueDescription = {
                            when (it) {
                                SpotifyStreamEngine.UNOFFICIAL ->
                                    stringResource(R.string.spotify_stream_engine_unofficial_desc)
                                SpotifyStreamEngine.LIBRESPOT ->
                                    stringResource(
                                        if (librespotSelectable) {
                                            R.string.spotify_stream_engine_librespot_desc
                                        } else {
                                            R.string.spotify_stream_engine_librespot_needs_premium
                                        },
                                    )
                            }
                        },
                        isValueEnabled = { it != SpotifyStreamEngine.LIBRESPOT || librespotSelectable },
                        onValueSelected = onEngineChange,
                    )
                }

                item {
                    SwitchPreference(
                        modifier = positions.modifierFor("spotify_stream_enabled"),
                        title = { Text(stringResource(R.string.spotify_stream_enabled)) },
                        description =
                            when {
                                signedIn -> stringResource(R.string.spotify_stream_enabled_desc)
                                engine == SpotifyStreamEngine.LIBRESPOT ->
                                    stringResource(R.string.spotify_stream_needs_librespot)
                                else -> stringResource(R.string.spotify_stream_needs_account)
                            },
                        icon = { Icon(painterResource(R.drawable.spotify_icon), null) },
                        checked = enabled,
                        onCheckedChange = onEnabledChange,
                        isEnabled = signedIn || enabledRaw,
                    )
                }

                item {
                    PreferenceEntry(
                        title = { Text(stringResource(R.string.spotify_stream_binding)) },
                        description =
                            stringResource(
                                if (engine == SpotifyStreamEngine.LIBRESPOT) {
                                    R.string.spotify_stream_binding_library
                                } else {
                                    R.string.spotify_stream_binding_dedicated
                                },
                            ),
                        icon = { Icon(painterResource(R.drawable.info), null) },
                        isEnabled = false,
                    )
                }

                item {
                    PreferenceEntry(
                        title = { Text(stringResource(R.string.spotify_stream_plan)) },
                        description =
                            stringResource(
                                when (SpotifyProduct.of(libraryProduct)) {
                                    SpotifyProduct.PREMIUM -> R.string.spotify_stream_plan_premium
                                    SpotifyProduct.FREE -> R.string.spotify_stream_plan_free
                                    SpotifyProduct.UNKNOWN -> R.string.spotify_stream_plan_unknown
                                },
                            ),
                        icon = { Icon(painterResource(R.drawable.info), null) },
                        isEnabled = false,
                    )
                }

                if (engine == SpotifyStreamEngine.LIBRESPOT && effectiveEngine != engine) {
                    item {
                        PreferenceEntry(
                            title = { Text(stringResource(R.string.spotify_stream_engine_degraded_title)) },
                            description = stringResource(R.string.spotify_stream_engine_degraded),
                            icon = { Icon(painterResource(R.drawable.info), null) },
                            isEnabled = false,
                        )
                    }
                }

                if (engine == SpotifyStreamEngine.UNOFFICIAL) {
                    item {
                        EnumListPreference(
                            modifier = positions.modifierFor("spotify_stream_method"),
                            title = { Text(stringResource(R.string.spotify_stream_method)) },
                            icon = { Icon(painterResource(R.drawable.equalizer), null) },
                            selectedValue = method,
                            valueText = {
                                when (it) {
                                    SpotifyStreamResolveMethod.STOPIFY -> stringResource(R.string.spotify_stream_method_stopify)
                                    SpotifyStreamResolveMethod.ECHO -> stringResource(R.string.spotify_stream_method_echo)
                                }
                            },
                            onValueSelected = onMethodChange,
                        )
                    }

                    item {
                        PreferenceEntry(
                            title = { Text(stringResource(R.string.spotify_stream_method_note_title)) },
                            description = stringResource(R.string.spotify_stream_method_note),
                            icon = { Icon(painterResource(R.drawable.info), null) },
                            isEnabled = false,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SpotifyStreamCard(
    container: Color,
    icon: Int,
    iconTint: Color,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(SettingsDimensions.BannerCardCornerRadius),
        colors = CardDefaults.cardColors(containerColor = container),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(SettingsDimensions.BannerIconSize)
                        .clip(CircleShape)
                        .background(iconTint.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(icon),
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(SettingsDimensions.BannerIconInnerSize),
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                content()
            }
        }
    }
}
