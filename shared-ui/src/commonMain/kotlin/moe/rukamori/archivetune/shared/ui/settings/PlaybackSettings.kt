package moe.rukamori.archivetune.shared.ui.settings

import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import moe.rukamori.archivetune.shared.domain.settings.*
import moe.rukamori.archivetune.shared.ui.resources.*
import kotlin.math.roundToInt
import androidx.compose.runtime.getValue

/** Capabilities are measured by the native owner, not inferred from stored enable switches. */
@Composable
fun PlaybackSettings(
    controller: SettingsController,
    bitPerfectSupported: Boolean,
    bitPerfectSinkActive: Boolean,
    usbExclusiveSupported: Boolean,
    releaseBitPerfectSink: suspend () -> Unit,
    navigate: (String) -> Unit,
    arrangeArtworkProviders: () -> Unit,
    managePlaylistTags: () -> Unit,
    onFailure: () -> Unit,
    scrollTo: String? = null,
    modifier: Modifier = Modifier,
) {
    val snapshot by controller.store.snapshots.collectAsState()
    val preferences = snapshot
    if (preferences == null) { CircularProgressIndicator(); return }
    SettingsPage(playbackSettingsRows(preferences, bitPerfectSupported, bitPerfectSinkActive,
        usbExclusiveSupported, navigate, arrangeArtworkProviders, managePlaylistTags),
        preferences, controller, scrollTo, onFailure = onFailure, modifier = modifier,
        onCommitted = { if (bitPerfectSinkActive && it.needsBitPerfectSinkRelease()) releaseBitPerfectSink() })
}

fun playbackSettingsRows(
    preferences: PreferenceSnapshot,
    bitPerfectSupported: Boolean,
    bitPerfectSinkActive: Boolean,
    usbExclusiveSupported: Boolean,
    navigate: (String) -> Unit,
    arrangeArtworkProviders: () -> Unit,
    managePlaylistTags: () -> Unit,
): List<SettingsRow> = buildList {
    val crossfade = preferences[CrossfadeEnabledKey, false]
    val offload = preferences[AudioOffload, false]
    val blocked = preferences.bitPerfectOutputSelected() || bitPerfectSinkActive
    val bypassReason = Res.string.m6_processing_bypassed_bit_perfect.takeIf { blocked }
    add(SettingsRow.Action("preferred_sources", Res.string.m6_source_settings, { navigate("settings/sources") }))
    add(SettingsRow.Action("lyrics", Res.string.m6_lyrics_settings, { navigate("settings/lyrics") }))
    add(SettingsRow.Toggle("enable_video_playback", Res.string.m6_video_playback, EnableVideoPlaybackKey, false))
    add(SettingsRow.Toggle("enable_pip_mode", Res.string.m6_pip, EnablePipModeKey, false,
        enabled = preferences[EnableVideoPlaybackKey, false],
        description = Res.string.m6_video_required.takeUnless { preferences[EnableVideoPlaybackKey, false] }))
    add(SettingsRow.Number("history_duration", Res.string.m6_history_duration, HistoryDuration, 30, 1..60,
        read = { (it[HistoryDuration] ?: it[HISTORY_DURATION_LEGACY_FLOAT_KEY]?.takeIf(Float::isFinite)?.roundToInt() ?: 30).coerceIn(1, 60) },
        write = { this[HistoryDuration] = it; this[HISTORY_DURATION_LEGACY_FLOAT_KEY] = null }))
    add(SettingsRow.Toggle("crossfade", Res.string.m6_crossfade, CrossfadeEnabledKey, false,
        description = Res.string.m6_transition_exclusions,
        write = { setPlaybackOutputPreference(CrossfadeEnabledKey, it) }))
    add(SettingsRow.Toggle("automix", Res.string.m6_automix, AutomixEnabledKey, false,
        description = Res.string.m6_transition_exclusions,
        write = { setPlaybackOutputPreference(AutomixEnabledKey, it) }))
    if (preferences[AutomixEnabledKey, false])
        add(SettingsRow.Choice("automix_performance", Res.string.m6_automix_performance, AutomixPerformanceModeKey, "BALANCED",
            listOf(SettingsRow.Option("EFFICIENT", Res.string.m6_efficient), SettingsRow.Option("BALANCED", Res.string.m6_balanced),
                SettingsRow.Option("PERFORMANCE", Res.string.m6_performance))))
    add(SettingsRow.Decimal("crossfade_duration", Res.string.m6_crossfade_duration, CrossfadeDurationKey, 5f, 0f..10f,
        enabled = crossfade))
    add(SettingsRow.Toggle("crossfade_gapless", Res.string.m6_crossfade_gapless, CrossfadeGaplessKey, true, enabled = crossfade,
        description = Res.string.m6_crossfade_required.takeUnless { crossfade }))
    add(SettingsRow.Toggle("skip_silence", Res.string.m6_skip_silence, SkipSilenceKey, false, enabled = !offload && !blocked,
        description = bypassReason ?: Res.string.m6_processing_unavailable_offload.takeIf { offload },
        write = { setPcmProcessingPreference(SkipSilenceKey, it) }))
    add(SettingsRow.Toggle("audio_normalization", Res.string.m6_audio_normalization, AudioNormalizationKey, true,
        enabled = !blocked, description = bypassReason, write = { setPcmProcessingPreference(AudioNormalizationKey, it) }))
    add(SettingsRow.Toggle("sponsor_block", Res.string.m6_sponsorblock, SponsorBlockEnabledKey, false))
    if (preferences[SponsorBlockEnabledKey, false]) {
        val categories = listOf("sponsor" to Res.string.m6_sponsor, "selfpromo" to Res.string.m6_selfpromo,
            "interaction" to Res.string.m6_interaction, "intro" to Res.string.m6_intro, "outro" to Res.string.m6_outro,
            "preview" to Res.string.m6_preview, "music_offtopic" to Res.string.m6_music_offtopic, "filler" to Res.string.m6_filler)
        categories.forEach { (id, label) ->
            add(SettingsRow.Toggle(if (id == "sponsor") "sponsor_block_categories" else "sponsor_block_categories:$id", label, SponsorBlockEnabledKey, false,
                read = { id in it[SponsorBlockCategoriesKey, setOf("music_offtopic")] },
                write = { selected ->
                    val current = this[SponsorBlockCategoriesKey, setOf("music_offtopic")]
                    this[SponsorBlockCategoriesKey] = (if (selected) current + id else current - id)
                        .intersect(categories.map { it.first }.toSet())
                }))
        }
    }
    add(SettingsRow.Choice("audio_quality", Res.string.m6_audio_quality, AudioQualityKey, "AUTO", listOf(
        SettingsRow.Option("HIGHEST", Res.string.m6_highest), SettingsRow.Option("HIGH", Res.string.m6_high),
        SettingsRow.Option("AUTO", Res.string.m6_quality_auto), SettingsRow.Option("LOW", Res.string.m6_low))))
    add(SettingsRow.Toggle("reject_suspected_upscaled_audio", Res.string.m6_reject_upscaled, RejectSuspectedUpscaledAudioKey, false,
        enabled = !blocked, description = bypassReason, write = { setAuthenticityRejection(it) }))
    add(SettingsRow.Toggle("audio_offload", Res.string.m6_audio_offload, AudioOffload, false,
        enabled = !preferences[RejectSuspectedUpscaledAudioKey, false],
        description = if (preferences[RejectSuspectedUpscaledAudioKey, false])
            Res.string.m6_offload_unavailable_authenticity else Res.string.m6_offload_exclusions,
        write = { setPlaybackOutputPreference(AudioOffload, it) }))
    add(SettingsRow.Toggle("bit_perfect_usb", Res.string.m6_bit_perfect_usb, BitPerfectUsbOutputKey, false,
        description = Res.string.m6_usb_exclusions,
        enabled = bitPerfectSupported, read = { bitPerfectSupported && it[BitPerfectUsbOutputKey, false] },
        write = { setPlaybackOutputPreference(BitPerfectUsbOutputKey, it) }))
    add(SettingsRow.Toggle("usb_exclusive_audio", Res.string.m6_usb_exclusive, UsbExclusiveAudioKey, false,
        description = Res.string.m6_usb_exclusions,
        enabled = usbExclusiveSupported, read = { usbExclusiveSupported && it[UsbExclusiveAudioKey, false] },
        write = { setPlaybackOutputPreference(UsbExclusiveAudioKey, it) }))
    add(SettingsRow.Toggle("tryptify_engine", Res.string.m6_tryptify, TryptifyAudioProcessingKey, false,
        enabled = !blocked, description = bypassReason, write = { setAudioProcessingPreference(TryptifyAudioProcessingKey, it) }))
    add(SettingsRow.Toggle("lastwave_engine", Res.string.m6_lastwave, LastwaveAudioProcessingKey, false,
        enabled = !blocked, description = bypassReason,
        read = { !it[TryptifyAudioProcessingKey, false] && it[LastwaveAudioProcessingKey, false] },
        write = { setAudioProcessingPreference(LastwaveAudioProcessingKey, it) }))
    add(SettingsRow.Toggle("float_dsp", Res.string.m6_float_dsp, FloatDspEnabledKey, false,
        enabled = !blocked, description = bypassReason, write = { setAudioProcessingPreference(FloatDspEnabledKey, it) }))
    add(SettingsRow.Toggle("seek_seconds_addup", Res.string.m6_seek_extra, SeekExtraSeconds, false))
    add(SettingsRow.Toggle("pause_mute", Res.string.m6_pause_mute, PauseOnDeviceMuteKey, false))
    if (preferences[PauseOnDeviceMuteKey, false])
        add(SettingsRow.Number("device_mute_recovery_volume", Res.string.m6_mute_recovery, DeviceMutePlaybackRecoveryVolumeKey, 0, 0..100))
    add(SettingsRow.Toggle("bluetooth_auto_start", Res.string.m6_bluetooth_start, AutoStartOnBluetoothKey, false))
    add(SettingsRow.Action("archive_tune_canvas", Res.string.m6_canvas_settings, { navigate("settings/canvas") }))
    add(SettingsRow.Toggle("spotify_canvas", Res.string.m6_spotify_canvas, SpotifyCanvasKey, false))
    if (preferences[SpotifyCanvasKey, false]) add(SettingsRow.Text("canvas_resolvers", Res.string.m6_canvas_resolvers,
        CanvasResolverEndpointsKey, valid = { raw -> raw.lineSequence().filter(String::isNotBlank).all(::validLyricsEndpoint) },
        singleLine = false))
    add(SettingsRow.Toggle("tidal_artwork_fallback", Res.string.m6_tidal_artwork, TidalArtworkFallbackEnabledKey, false,
        enabled = preferences[TidalEnabledKey, true]))
    add(SettingsRow.Action("artwork_priority", Res.string.m6_artwork_priority, arrangeArtworkProviders))
    add(SettingsRow.Toggle("persistent_queue", Res.string.m6_persistent_queue, PersistentQueueKey, true))
    add(SettingsRow.Toggle("permanent_shuffle", Res.string.m6_permanent_shuffle, PermanentShuffleKey, false))
    add(SettingsRow.Toggle("swipe_up_queue", Res.string.m6_swipe_queue, SwipeUpToOpenQueueKey, true))
    add(SettingsRow.Toggle("queue_after_manual", Res.string.m6_queue_manual, QueueAddAfterManualKey, true))
    add(SettingsRow.Toggle("swipe_to_song", Res.string.m6_swipe_song, SwipeToSongKey, true))
    add(SettingsRow.Toggle("enable_swipe_thumbnail", Res.string.m6_swipe_thumbnail, SwipeThumbnailKey, true))
    add(SettingsRow.Decimal("swipe_sensitivity", Res.string.m6_swipe_sensitivity, SwipeSensitivityKey, .73f, 0f..1f))
    add(SettingsRow.Toggle("auto_skip_error", Res.string.m6_skip_error, AutoSkipNextOnErrorKey, false))
    add(SettingsRow.Toggle("stop_task_clear", Res.string.m6_stop_task, StopMusicOnTaskClearKey, false))
    add(SettingsRow.Toggle("wakelock", Res.string.m6_wakelock, WakelockKey, false))
    add(SettingsRow.Text("artist_separators", Res.string.m6_artist_separators, ArtistSeparatorsKey, default = ",;/&"))
    add(SettingsRow.Action("manage_playlist_tags", Res.string.m6_playlist_tags, managePlaylistTags))
}
