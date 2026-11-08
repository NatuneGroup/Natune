package moe.rukamori.archivetune.viewmodels

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import moe.rukamori.archivetune.lyrics.PreparedLyrics
import javax.inject.Inject

sealed interface LyricsRenderScreenState {
    data object Loading : LyricsRenderScreenState

    @Immutable
    data class Success(val lyrics: PreparedLyrics) : LyricsRenderScreenState

    data object Empty : LyricsRenderScreenState

    @Immutable
    data class Error(val reason: LyricsRenderError) : LyricsRenderScreenState
}

enum class LyricsRenderError {
    INVALID_LYRICS,
}

/** Upstream's playback clock; Natune supplies prepared lyrics through its existing provider flow. */
@HiltViewModel
class LyricsRenderViewModel @Inject constructor() : ViewModel() {
    private val _playbackPositionMs = MutableStateFlow(0L)
    val playbackPositionMs: StateFlow<Long> = _playbackPositionMs.asStateFlow()
    private val playbackClock = LyricsPlaybackClock()

    fun onPlaybackPositionChanged(positionMs: Long) {
        _playbackPositionMs.value = playbackClock.reset(positionMs)
    }

    fun onPlaybackFrame(
        positionMs: Long,
        sampleTimeNanos: Long,
        playbackSpeed: Float,
        isAdvancing: Boolean,
        durationMs: Long,
    ) {
        _playbackPositionMs.value =
            playbackClock.update(positionMs, sampleTimeNanos, playbackSpeed, isAdvancing, durationMs)
    }
}
