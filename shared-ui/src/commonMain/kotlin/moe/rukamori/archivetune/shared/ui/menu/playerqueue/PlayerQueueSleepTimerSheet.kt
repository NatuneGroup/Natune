/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package moe.rukamori.archivetune.shared.ui.menu.playerqueue

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import moe.rukamori.archivetune.shared.ui.resources.Res
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs
import kotlin.math.roundToInt

/** The countdown refreshes this often, which keeps the readout smooth without recomposing every millisecond. */
private const val SLEEP_TIMER_POLL_MS = 500L

/** The presets the baseline offered as chips, in minutes. */
private val SLEEP_TIMER_PRESET_MINUTES: List<Int> = listOf(5, 10, 15, 20, 30, 45, 60, 90)

/**
 * The sleep timer body the menu shows in place of its grid, as the baseline's Apple Music sleep sheet did. Preset,
 * end-of-song and slider commits start the timer and then call [onDismiss], which closes the menu. Turning the timer off
 * also closes it. The countdown reads [clock], so the owner controls time and a test can fix it.
 */
@Composable
internal fun PlayerQueueSleepTimerSheet(
    state: PlayerSleepTimerState,
    clock: PlayerQueueClock,
    actions: PlayerQueueMenuActions,
    icon: PlayerQueueMenuIconSlot,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // A sentinel of -1 marks the end-of-song timer, which has no trigger time to count down to.
    var remainingMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(state, clock) {
        while (isActive) {
            remainingMs =
                when {
                    state.pauseWhenSongEnd -> -1L
                    state.triggerAtEpochMs > 0L -> state.triggerAtEpochMs - clock.nowEpochMs()
                    else -> 0L
                }
            delay(SLEEP_TIMER_POLL_MS)
        }
    }

    val isEndOfSong = state.pauseWhenSongEnd
    val isTimed = state.triggerAtEpochMs > 0L && !isEndOfSong
    val timerActive = state.isActive

    // The slider opens on the active timer's remaining minutes, rounded to the nearest minute and kept in 1..120.
    val activeTimerMinutes =
        if (isTimed && remainingMs > 0L) {
            ((remainingMs + 30_000L) / 60_000L).toInt().coerceIn(1, 120)
        } else {
            0
        }
    var sliderMinutes by remember { mutableFloatStateOf(activeTimerMinutes.toFloat()) }
    // The timer can end from another surface, so the slider follows the host rather than keeping a stale value.
    LaunchedEffect(activeTimerMinutes, timerActive) {
        if (timerActive) {
            sliderMinutes = activeTimerMinutes.toFloat()
        } else if (sliderMinutes > 0f) {
            sliderMinutes = 0f
        }
    }

    val nowMs = clock.nowEpochMs()
    val sliderValue = sliderMinutes.coerceIn(0f, 120f)

    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .wrapContentHeight(),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 3.dp,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp)
                    .padding(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.secondaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    icon(
                        PlayerQueueMenuIcon.BEDTIME,
                        MaterialTheme.colorScheme.onSecondaryContainer,
                        Modifier.size(24.dp),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(Res.string.m5pq_sleep_timer),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    val statusText =
                        when {
                            isEndOfSong -> stringResource(Res.string.m5pq_sleep_timer_end_of_song)
                            isTimed && remainingMs > 0L ->
                                stringResource(
                                    Res.string.m5pq_sleep_timer_remaining,
                                    sleepCountdownText(remainingMs),
                                )
                            else -> stringResource(Res.string.m5pq_sleep_timer_pick_a_time)
                        }
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                SLEEP_TIMER_PRESET_MINUTES.forEach { minutes ->
                    // Within 30 seconds counts as the same preset, which absorbs the drift of a running countdown.
                    val selected =
                        isTimed &&
                            abs((state.triggerAtEpochMs - nowMs) - minutes.toLong() * 60_000L) < 30_000L
                    FilterChip(
                        selected = selected,
                        onClick = {
                            actions.startSleepTimer(minutes)
                            onDismiss()
                        },
                        label = { Text(stringResource(Res.string.m5pq_duration_minutes, minutes)) },
                        shape = RoundedCornerShape(20.dp),
                        colors =
                            FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                    )
                }

                FilterChip(
                    selected = isEndOfSong,
                    onClick = {
                        actions.startSleepTimerEndOfSong()
                        onDismiss()
                    },
                    leadingIcon = {
                        icon(PlayerQueueMenuIcon.SKIP_NEXT, LocalContentColor.current, Modifier.size(18.dp))
                    },
                    label = { Text(stringResource(Res.string.m5pq_sleep_timer_end_of_song)) },
                    shape = RoundedCornerShape(20.dp),
                    colors =
                        FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                            selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                )
            }

            Spacer(Modifier.height(20.dp))

            // The slider snaps to whole minutes so the readout always matches the duration a release commits.
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = stringResource(Res.string.m5pq_sleep_timer_custom),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Box(
                        modifier =
                            Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer)
                                .padding(horizontal = 14.dp, vertical = 6.dp),
                    ) {
                        Text(
                            text = sleepMinutesLabel(sliderValue.roundToInt().coerceAtLeast(0)),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Slider(
                    value = sliderValue,
                    onValueChange = { sliderMinutes = it },
                    onValueChangeFinished = {
                        // Reads the live slider: the value captured at composition can lag one step behind a fast release.
                        val minutes = sliderMinutes.coerceIn(0f, 120f).roundToInt()
                        if (minutes > 0) {
                            actions.startSleepTimer(minutes)
                            onDismiss()
                        }
                    },
                    valueRange = 0f..120f,
                    steps = 119,
                    colors =
                        SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary,
                            inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        ),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = stringResource(Res.string.m5pq_duration_minutes, 0),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(Res.string.m5pq_duration_minutes, 120),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            AnimatedVisibility(
                visible = timerActive,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Spacer(Modifier.height(12.dp))
            }
            AnimatedVisibility(visible = timerActive) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(20.dp))
                            .background(MaterialTheme.colorScheme.errorContainer)
                            .clickable {
                                actions.clearSleepTimer()
                                onDismiss()
                            }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    icon(
                        PlayerQueueMenuIcon.CLOSE,
                        MaterialTheme.colorScheme.onErrorContainer,
                        Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(Res.string.m5pq_sleep_timer_cancel_timer),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** The time-pill text: "Off" at zero, then minutes under an hour, then hours with the remainder when it is not whole. */
@Composable
private fun sleepMinutesLabel(minutes: Int): String =
    when {
        minutes <= 0 -> stringResource(Res.string.m5pq_sleep_timer_off)
        minutes < 60 -> stringResource(Res.string.m5pq_duration_minutes, minutes)
        minutes % 60 == 0 -> stringResource(Res.string.m5pq_duration_hours, minutes / 60)
        else -> stringResource(Res.string.m5pq_duration_hours_minutes, minutes / 60, minutes % 60)
    }

/**
 * The countdown text: `1d 2h 3m 4s` while days remain, `2h 3m 4s` while hours remain and `m:ss` below an hour. The unit
 * words come from resources, so a translator can reword or reorder them. The colon form has no unit word and stays digits.
 */
@Composable
private fun sleepCountdownText(remainingMs: Long): String {
    val parts = splitPlayerQueueDuration(remainingMs)
    return when {
        parts.days > 0 ->
            stringResource(
                Res.string.m5pq_duration_days_hours_minutes_seconds,
                parts.days,
                parts.hours,
                parts.minutes,
                parts.seconds,
            )
        parts.hours > 0 ->
            stringResource(
                Res.string.m5pq_duration_hours_minutes_seconds,
                parts.hours,
                parts.minutes,
                parts.seconds,
            )
        else -> "${parts.minutes}:${parts.seconds.toString().padStart(2, '0')}"
    }
}
