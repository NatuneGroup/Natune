/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * Technique adapted from amll-dev/applemusic-like-lyrics (AGPL-3.0-only),
 * technique-source: packages/core/src/lyric-player/base/interlude-dots.ts
 * (`InterludeDotsBase` — a three-dot interlude performance: container
 * breathes on a ~4s cycle while the dots light up in staggered sequence with
 * an eased enter, then the whole thing exits with a quick swell-and-fade).
 * IDEA PORT ONLY — no upstream code is copied here; the mapping below is our
 * own Kotlin/Compose implementation of the idea: a breathing container scale
 * plus three dots pulsing in staggered sequence next to the instrumental note.
 */

package moe.rukamori.archivetune.ui.component

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.LocalAnimationsDisabled

/**
 * Three breathing dots for an instrumental break.
 *
 * The dots are driven by the LYRICS TIMELINE, not by a `rememberInfiniteTransition` that is
 * composed only while a break is active. That transition restarted at phase 0 on every break, so
 * the travelling pulse snapped back to its first dot each time the break began — a visible jump.
 * `EnhancedInterludeMotion` (the same object the Enhanced dots read) derives breath and per-dot
 * light from an absolute position with seamless cosine cycles, so the phase at the end of one break
 * is exactly the phase at the start of the next, and a seek or repeat lands on the right frame.
 *
 * A frame-clock projection advances that absolute position smoothly at display refresh between the
 * renderer's coarse position polls. Every value is read ONLY inside `graphicsLayer` lambdas (draw
 * phase), so frames update render-node properties without recomposing the row and no object is
 * allocated per frame.
 */
@Composable
fun InterludeDots(
    textColor: Color,
    currentPosition: () -> Long,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = LocalAnimationsDisabled.current
    val anchorFeedMs = remember { mutableLongStateOf(0L) }
    val anchorWallMs = remember { mutableLongStateOf(0L) }
    val smoothMs = remember { mutableLongStateOf(0L) }
    // A new report (a poll tick, seek or repeat) re-anchors the projection; frames never do.
    val reported = currentPosition()
    LaunchedEffect(reported) {
        anchorFeedMs.longValue = reported
        anchorWallMs.longValue = SystemClock.uptimeMillis()
        smoothMs.longValue = reported
    }
    LaunchedEffect(reduceMotion) {
        if (reduceMotion) return@LaunchedEffect
        while (true) {
            withFrameNanos {
                // Advance the continuous phases at display refresh between coarse reports.
                smoothMs.longValue =
                    anchorFeedMs.longValue + (SystemClock.uptimeMillis() - anchorWallMs.longValue)
            }
        }
    }
    Row(
        modifier =
            modifier.graphicsLayer {
                val scale =
                    if (reduceMotion) 1f else EnhancedInterludeMotion.breath(smoothMs.longValue)
                scaleX = scale
                scaleY = scale
            },
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(3) { index ->
            Box(
                modifier =
                    Modifier.size(8.dp)
                        .graphicsLayer {
                            alpha =
                                EnhancedInterludeMotion.dotLight(
                                    smoothMs.longValue,
                                    index,
                                    reduceMotion,
                                )
                        }
                        .background(color = textColor, shape = CircleShape),
            )
        }
    }
}
