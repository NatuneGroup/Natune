
package app.natune.logcat

import app.natune.shared.domain.feedback.DiagnosticRedaction

val diagnosticPreferenceNames = listOf(
    "audioSourceOrder", "audioQuality", "autoChoosePlaybackClient", "playbackClient",
    "tidalEnabled", "tidalAudioQuality", "deezerEnabled", "deezerAudioQuality", "qobuzEnabled", "qobuzAudioQuality",
    "appleMusicSourceEnabled", "appleMusicQuality", "amazonMusicEnabled", "qqMusicEnabled", "qqMusicAudioQuality",
    "networkMetered", "preloadLowDataMode", "audioOffload", "bitPerfectUsbOutput",
    "crossfadeEnabled", "crossfadeDuration", "crossfadeGapless", "automixEnabled", "automixPerformanceMode",
    "skipSilence", "audioNormalization", "wakelock", "playerDesignStyle", "lyricsAnimationStyle",
    "apple_music_experience", "liquidGlassEnabled", "homeSource", "navigationBarStyle", "miniPlayerBackgroundStyle",
    "rejectSuspectedUpscaledAudio",
)

fun diagnosticPreferences(values: Map<String, Any>): String =
    diagnosticPreferenceNames.joinToString("\n") { name ->
        "$name=${DiagnosticRedaction.redact(values[name]?.toString() ?: "default (unset)")}"
    }
