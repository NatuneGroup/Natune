/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * Portions © 4nx3b — github.com/4nx3b
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

@file:OptIn(
    ExperimentalFoundationApi::class,
    ExperimentalLayoutApi::class,
    ExperimentalMaterial3ExpressiveApi::class,
)

package moe.rukamori.archivetune.shared.ui.screens.mediainfo

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import moe.rukamori.archivetune.shared.ui.resources.Res
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_close
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_copy
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_please_wait
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_share
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_solar_close_circle_linear
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_solar_copy
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_solar_database
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_solar_hertz
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_solar_music_note
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_solar_share_linear
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_solar_speed
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_solar_users
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_solar_wave
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_title
import moe.rukamori.archivetune.shared.ui.resources.m9_media_info_unknown
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

private val MediaInfoExpressiveSpring =
    spring<Float>(
        dampingRatio = Spring.DampingRatioLowBouncy,
        stiffness = Spring.StiffnessMediumLow,
    )

/**
 * Media-info sheet body: hero, quick facts, the Information, Details and Numbers tabs. A blank
 * [MediaInfoUi.mediaId] renders nothing. The host supplies [artwork] (it owns image loading) and
 * [liveAudioChain] (the audio-chain pill, shown on the Details tab for the playing track only);
 * clipboard, toast and share intents go through [actions].
 */
@Composable
fun MediaInfoScreen(
    state: MediaInfoUi,
    actions: MediaInfoActions,
    modifier: Modifier = Modifier,
    artwork: (@Composable (url: String, modifier: Modifier) -> Unit)? = null,
    liveAudioChain: (@Composable () -> Unit)? = null,
) {
    val mediaId = state.mediaId
    if (mediaId.isBlank()) return

    var selectedTabIndex by rememberSaveable(mediaId) { mutableStateOf(MediaInfoTab.Information.ordinal) }
    val selectedTab = MediaInfoTab.entries[selectedTabIndex]
    var entered by remember(mediaId) { mutableStateOf(false) }
    LaunchedEffect(mediaId) {
        entered = false
        delay(80)
        entered = true
    }

    val unknownText = stringResource(Res.string.m9_media_info_unknown)
    val pleaseWaitText = stringResource(Res.string.m9_media_info_please_wait)
    val copyText = stringResource(Res.string.m9_media_info_copy)
    val shareText = stringResource(Res.string.m9_media_info_share)
    val closeText = stringResource(Res.string.m9_media_info_close)
    val trackInfoLabel = stringResource(Res.string.m9_media_info_title)

    val song = state.song
    val video = state.video
    val mediaUrl = "https://music.youtube.com/watch?v=$mediaId"
    val heroTitle = song?.title ?: video?.title ?: mediaId
    val heroSubtitle =
        song?.artists?.takeIf { it.isNotEmpty() }?.joinToString() ?: video?.author ?: unknownText
    val quickFacts = mediaInfoQuickFacts(state)

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(contentType = "Hero") {
            MediaInfoExpressiveEntrance(entered = entered, index = 0) {
                MediaInfoHero(
                    title = heroTitle,
                    subtitle = heroSubtitle,
                    artworkUrl = song?.thumbnailUrl ?: video?.authorThumbnailUrl,
                    artwork = artwork,
                    isLoading = video == null,
                    loadingText = pleaseWaitText,
                    trackInfoLabel = trackInfoLabel,
                    closeText = closeText,
                    copyText = copyText,
                    shareText = shareText,
                    onCopy = { actions.onCopy(mediaId) },
                    onShare = { actions.onShare(mediaUrl) },
                    onClose = actions.onClose,
                )
            }
        }

        if (quickFacts.isNotEmpty()) {
            item(contentType = "QuickFacts") {
                MediaInfoExpressiveEntrance(entered = entered, index = 1) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        quickFacts.forEach { fact ->
                            MediaInfoQuickPill(
                                icon = fact.icon,
                                text = fact.text,
                                onClick = { actions.onCopy(fact.text) },
                            )
                        }
                    }
                }
            }
        }

        item(contentType = "Tabs") {
            MediaInfoExpressiveEntrance(entered = entered, index = 2) {
                MediaInfoExpressiveTabs(
                    selectedTab = selectedTab,
                    onSelect = { selectedTabIndex = it.ordinal },
                )
            }
        }

        item(contentType = "SelectedContent") {
            MediaInfoExpressiveEntrance(entered = entered, index = 3) {
                AnimatedContent(
                    targetState = selectedTab,
                    transitionSpec = {
                        (slideInVertically(
                            animationSpec = tween(220),
                            initialOffsetY = { it / 6 },
                        ) + fadeIn(tween(220))) togetherWith
                            (slideOutVertically(
                                animationSpec = tween(160),
                                targetOffsetY = { -it / 8 },
                            ) + fadeOut(tween(160)))
                    },
                    label = "mediaInfoTab",
                ) { tab ->
                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        when (tab) {
                            MediaInfoTab.Information ->
                                MediaInfoInformationTab(
                                    state = state,
                                    actions = actions,
                                )

                            MediaInfoTab.Details ->
                                MediaInfoDetailsTab(
                                    state = state,
                                    actions = actions,
                                    liveAudioChain = liveAudioChain,
                                )

                            MediaInfoTab.Numbers -> MediaInfoNumbersTab(state = state)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Header-strip facts, in baseline order. Each pill copies its text. Values stay raw so the
 * formatting matches the baseline without a locale-dependent formatter.
 */
private fun mediaInfoQuickFacts(state: MediaInfoUi): List<MediaInfoQuickFact> {
    val format = state.format
    val fileSizeText = mediaInfoFileSizeText(state)
    return buildList<MediaInfoQuickFact> {
        format
            ?.mimeType
            ?.substringBefore(';')
            ?.takeIf { it.isNotBlank() }
            ?.let { add(MediaInfoQuickFact(icon = Res.drawable.m9_media_info_solar_wave, text = it)) }
        format
            ?.bitrateBps
            ?.takeIf { it > 0 }
            ?.let { add(MediaInfoQuickFact(icon = Res.drawable.m9_media_info_solar_speed, text = "${it / 1000} Kbps")) }
        format
            ?.sampleRateHz
            ?.takeIf { it > 0 }
            ?.let { add(MediaInfoQuickFact(icon = Res.drawable.m9_media_info_solar_hertz, text = "${it / 1000.0} kHz")) }
        fileSizeText?.let {
            add(MediaInfoQuickFact(icon = Res.drawable.m9_media_info_solar_database, text = it))
        }
        state.video
            ?.subscribers
            ?.takeIf { it.isNotBlank() }
            ?.let { add(MediaInfoQuickFact(icon = Res.drawable.m9_media_info_solar_users, text = it)) }
    }
}

@Composable
private fun MediaInfoExpressiveEntrance(
    entered: Boolean,
    index: Int,
    content: @Composable () -> Unit,
) {
    val progress by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec =
            spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMediumLow,
                visibilityThreshold = 0.01f,
            ),
        label = "entrance_$index",
    )
    Box(
        modifier =
            Modifier.graphicsLayer {
                val p = progress.coerceIn(0f, 1f)
                alpha = p
                scaleX = 0.92f + 0.08f * p
                scaleY = 0.92f + 0.08f * p
                translationY = (1f - p) * 18f
            },
    ) {
        content()
    }
}

@Composable
private fun MediaInfoHero(
    title: String,
    subtitle: String,
    artworkUrl: String?,
    artwork: (@Composable (url: String, modifier: Modifier) -> Unit)?,
    isLoading: Boolean,
    loadingText: String,
    trackInfoLabel: String,
    closeText: String,
    copyText: String,
    shareText: String,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onClose: () -> Unit,
) {
    val artworkShape = RoundedCornerShape(24.dp)
    Column(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Surface(
                shape = artworkShape,
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.size(96.dp),
            ) {
                val url = artworkUrl?.takeIf { it.isNotBlank() }
                if (url != null && artwork != null) {
                    artwork(url, Modifier.fillMaxSize().clip(artworkShape))
                } else {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        Icon(
                            painter = painterResource(Res.drawable.m9_media_info_solar_music_note),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(36.dp),
                        )
                    }
                }
            }

            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.weight(1f),
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Text(
                        text = trackInfoLabel.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.basicMarquee(),
                )
                if (isLoading) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        LoadingIndicator(modifier = Modifier.size(18.dp))
                        Text(
                            text = loadingText,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            FilledTonalIconButton(onClick = onClose) {
                Icon(
                    painter = painterResource(Res.drawable.m9_media_info_solar_close_circle_linear),
                    contentDescription = closeText,
                )
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            FilledTonalButton(
                onClick = onCopy,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    painter = painterResource(Res.drawable.m9_media_info_solar_copy),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(text = copyText)
            }

            OutlinedButton(
                onClick = onShare,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    painter = painterResource(Res.drawable.m9_media_info_solar_share_linear),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(text = shareText)
            }
        }
    }
}

/**
 * Tappable header pill. The surface is clipped by its own shape before the click is attached,
 * so the ripple stays inside the rounded outline.
 */
@Composable
private fun MediaInfoQuickPill(
    icon: DrawableResource,
    text: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun MediaInfoExpressiveTabs(
    selectedTab: MediaInfoTab,
    onSelect: (MediaInfoTab) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(4.dp),
        ) {
            MediaInfoTab.entries.forEach { tab ->
                val selected = tab == selectedTab
                val selectionScale by animateFloatAsState(
                    targetValue = if (selected) 1f else 0.94f,
                    animationSpec = MediaInfoExpressiveSpring,
                    label = "tabScale_${tab.name}",
                )
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color =
                        if (selected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            Color.Transparent
                        },
                    modifier =
                        Modifier
                            .weight(1f)
                            .graphicsLayer {
                                scaleX = selectionScale
                                scaleY = selectionScale
                            },
                ) {
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier =
                            Modifier
                                .clickable { onSelect(tab) }
                                .padding(vertical = 10.dp),
                    ) {
                        AnimatedVisibility(visible = selected) {
                            Row {
                                Icon(
                                    painter = painterResource(tab.icon),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                            }
                        }
                        Text(
                            text = stringResource(tab.label),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            color =
                                if (selected) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
