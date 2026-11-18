package moe.rukamori.archivetune.playback.smart

import kotlinx.coroutines.flow.MutableStateFlow
import moe.rukamori.archivetune.constants.AutomixPerformanceMode

/**
 * Process-wide settings surface for the Automix pipeline.
 *
 * The analysis back end ([BeatTracker], [VocalTracker]) needs the inference
 * thread budget at session-construction time, which happens deep inside the
 * analyzer's worker — far from any composable or DataStore scope. The
 * MusicService settings observer keeps [performanceMode] current; the model
 * sessions re-read it the next time they are (re)created.
 */
object SmartFadeSettings {
    val performanceMode = MutableStateFlow(AutomixPerformanceMode.BALANCED)
}
