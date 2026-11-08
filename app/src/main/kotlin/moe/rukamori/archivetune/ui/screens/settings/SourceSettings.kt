/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * Top-level "Sources" settings screen. Hosts independent catalog-source selectors (metadata and
 * search) plus the streaming-source preference groups (priority, toggles and quality). Manual
 * account/instance sign-in still lives in Integration (gated).
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import moe.rukamori.archivetune.ui.component.SettingsTopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import android.widget.Toast
import androidx.navigation.NavController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.LocalPlayerAwareWindowInsets
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.tidal.TidalInstanceHealthManager
import moe.rukamori.archivetune.ui.component.IconButton
import moe.rukamori.archivetune.ui.component.PreferenceEntry
import moe.rukamori.archivetune.ui.component.PreferenceGroup
import moe.rukamori.archivetune.ui.component.TextFieldDialog
import moe.rukamori.archivetune.ui.utils.backToMain
import moe.rukamori.archivetune.constants.UsePoolAccountsKey
import moe.rukamori.archivetune.utils.PoolAccountManager
import moe.rukamori.archivetune.utils.rememberPreference
import androidx.compose.foundation.layout.asPaddingValues
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceSettings(navController: NavController, scrollTo: String? = null) {
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            SettingsTopAppBar(
                title = { Text(stringResource(R.string.source_settings)) },
                navigationIcon = {
                    IconButton(
                        onClick = navController::navigateUp,
                        onLongClick = navController::backToMain,
                    ) {
                        Icon(
                            painterResource(R.drawable.arrow_back),
                            contentDescription = null,
                        )
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
        val topPadding = innerPadding.calculateTopPadding()
        val scrollState = rememberScrollState()
        val positions = rememberPreferencePositions()

        LaunchedEffect(scrollTo) { positions.scrollToKey(scrollTo, scrollState) }

        Column(
            Modifier
                .padding(top = topPadding)
                .windowInsetsPadding(
                    LocalPlayerAwareWindowInsets.current.only(
                        WindowInsetsSides.Horizontal,
                    ),
                )
                // Chained before verticalScroll so it measures the viewport, not the scrolling content.
                .then(positions.containerModifier())
                .verticalScroll(scrollState)
                .padding(bottom = playerAwareBottomPadding + SettingsDimensions.ScreenBottomPadding),
        ) {
            // Manual "refresh from pool" — pulls the latest shared accounts and instances on demand
            // (the app also does this automatically on startup). Only shown when a source pool is
            // configured at build time.
            PoolRefreshSection(positions)

            // Preferred-source picker, per-source enable toggles and quality. Account/instance
            // management remains in Integration (behind the manual-source-login toggle).
            PlaybackSourceSections(
                navController = navController,
                positions = positions,
            )
        }
    }
}

/**
 * Top-of-screen action that force-refreshes the shared source pool: re-fetches contributed
 * accounts (via [PoolAccountManager]) and re-discovers verified Tidal instances (via
 * [TidalInstanceHealthManager]), bypassing the normal throttle. Hidden when no source pool URL is
 * baked in, or when the user has switched the pool off in Integration: either way there is
 * nothing to refresh, and refresh() would decline and report "up to date".
 */
@Composable
private fun PoolRefreshSection(positions: PreferencePositions) {
    val (usePoolAccounts) = rememberPreference(UsePoolAccountsKey, true)
    if (!PoolAccountManager.isEnabled || !usePoolAccounts) return

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var requesting by remember { mutableStateOf(false) }
    var requestError by remember { mutableStateOf<String?>(null) }
    val status by PoolAccountManager.feedStatus.collectAsStateWithLifecycle()
    val refreshing = requesting || status.refreshing
    val error = requestError ?: status.error
    val counts = stringResource(R.string.pool_cached_counts, status.tidal, status.qobuz, status.deezer, status.appleMusic, status.amazon)
    val lastRefresh = if (status.lastAttemptAtMillis > 0L) {
        stringResource(R.string.pool_last_refresh_attempt, DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            .format(Date(status.lastAttemptAtMillis)))
    } else stringResource(R.string.pool_never_refreshed)

    LaunchedEffect(context) {
        try { PoolAccountManager.loadCached(context) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { requestError = context.getString(R.string.pool_refresh_failed) }
    }

    PreferenceGroup(
        modifier = positions.modifierFor("youtube_music"),
    ) {
        item {
            PreferenceEntry(
                title = {
                    Text(
                        if (refreshing) {
                            stringResource(R.string.pool_refreshing)
                        } else {
                            stringResource(R.string.pool_refresh_title)
                        },
                    )
                },
                description = counts,
                content = {
                    Column {
                        Text(stringResource(when {
                            refreshing -> R.string.pool_refreshing
                            error != null -> R.string.pool_status_failed
                            status.lastAttemptAtMillis > 0L -> R.string.pool_status_ready
                            else -> R.string.pool_never_refreshed
                        }))
                        if (status.lastAttemptAtMillis > 0L) Text(lastRefresh)
                        error?.let { Text(it) }
                    }
                },
                icon = { Icon(painterResource(R.drawable.sync), null) },
                trailingContent =
                    if (refreshing) {
                        { CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp) }
                    } else {
                        null
                    },
                isEnabled = !refreshing,
                onClick = {
                    if (refreshing) return@PreferenceEntry
                    requesting = true
                    requestError = null
                    scope.launch {
                        try {
                            val before = PoolAccountManager.lastRefreshAtMillis
                            withContext(Dispatchers.IO) {
                                PoolAccountManager.refresh(context, force = true)
                                if (PoolAccountManager.lastFeedError == null && PoolAccountManager.lastRefreshAtMillis != before) {
                                    TidalInstanceHealthManager.refresh(
                                        context,
                                        includeDiscovery = true,
                                        staggered = false,
                                    )
                                }
                            }
                            val updated = PoolAccountManager.feedStatus.value
                            val message = when {
                                updated.error != null -> context.getString(R.string.pool_refresh_failed) + "\n" + updated.error
                                updated.lastAttemptAtMillis == before -> context.getString(R.string.pool_refresh_up_to_date)
                                else -> context.getString(R.string.pool_refresh_done, updated.tidal, updated.qobuz, updated.deezer)
                            }
                            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            requestError = context.getString(R.string.pool_refresh_failed)
                            Toast.makeText(context, requestError, Toast.LENGTH_LONG).show()
                        } finally {
                            requesting = false
                        }
                    }
                },
            )
        }

    }
}
