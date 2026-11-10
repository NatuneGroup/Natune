package moe.rukamori.archivetune.ui.screens.settings

import android.content.Context
import androidx.compose.material3.ColorScheme
import androidx.navigation.NavController
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.SpotifyCanvasKey
import moe.rukamori.archivetune.constants.AUDIO_PROCESSING_ENABLED_DEFAULT
import moe.rukamori.archivetune.constants.AudioNormalizationKey
import moe.rukamori.archivetune.constants.AudioOffload
import moe.rukamori.archivetune.constants.AudioProcessingEnabledKey
import moe.rukamori.archivetune.constants.AutoSkipNextOnErrorKey
import moe.rukamori.archivetune.constants.AutoLoadMoreKey
import moe.rukamori.archivetune.constants.AutoStartOnBluetoothKey
import moe.rukamori.archivetune.constants.CrossfadeEnabledKey
import moe.rukamori.archivetune.constants.CrossfadeGaplessKey
import moe.rukamori.archivetune.constants.EnableVideoPlaybackKey
import moe.rukamori.archivetune.constants.EnablePipModeKey
import moe.rukamori.archivetune.constants.LyricsClickKey
import moe.rukamori.archivetune.constants.LyricsScrollKey
import moe.rukamori.archivetune.constants.PauseOnDeviceMuteKey
import moe.rukamori.archivetune.constants.PermanentShuffleKey
import moe.rukamori.archivetune.constants.PersistentQueueKey
import moe.rukamori.archivetune.constants.SeekExtraSeconds
import moe.rukamori.archivetune.constants.ShowLyricsKey
import moe.rukamori.archivetune.constants.SkipSilenceKey
import moe.rukamori.archivetune.constants.StopMusicOnTaskClearKey
import moe.rukamori.archivetune.constants.WakelockKey

internal fun playbackSettingsItem(
    navController: NavController,
    context: Context,
    colorScheme: ColorScheme,
): SettingsItem =
    SettingsItem(
        key = "playback",
        icon = R.drawable.music_note,
        title = context.getString(R.string.settings_playback_title),
        subtitle = context.getString(R.string.settings_playback_subtitle),
        accentColor = colorScheme.tertiary,
        keywords = listOf("playback", "player", "audio", "quality", "equalizer", "eq", "volume", "crossfade", "gapless", "flac", "lossless", "hi-res", "sample rate", "bitrate", "video", "music video", "video playback", "pip", "picture in picture", "floating", "minimize"),
        onClick = { navController.navigate("settings/player") },
        children = listOf(
            SettingsChild("Enable video playback", "enable_video_playback", listOf("video", "music video", "mv", "video playback", "captions", "subtitles")) { SearchResultSwitch(EnableVideoPlaybackKey, false) },
            SettingsChild("Enable PiP mode", "enable_pip_mode", listOf("pip", "picture in picture", "floating video", "minimize", "pop out", "overlay")) { SearchResultSwitch(EnablePipModeKey, false) },
            SettingsChild("History duration", "history_duration", listOf("history", "duration", "recent", "queue length")),
            SettingsChild("Crossfade", "crossfade", listOf("crossfade", "fade", "transition", "mix", "blend")) { SearchResultSwitch(CrossfadeEnabledKey, false) },
            SettingsChild("Crossfade gapless", "crossfade_gapless", listOf("crossfade gapless", "gapless crossfade", "seamless crossfade")) { SearchResultSwitch(CrossfadeGaplessKey, true) },
            SettingsChild("Skip silence", "skip_silence", listOf("silence", "skip silence", "blank", "quiet")) { SearchResultSwitch(SkipSilenceKey, false) },
            SettingsChild("Audio normalization", "audio_normalization", listOf("normalization", "loudness", "normalize", "volume level")) { SearchResultSwitch(AudioNormalizationKey, true) },
            SettingsChild("Audio offload", "audio_offload", listOf("offload", "audio offload", "hardware decoder")) { SearchResultSwitch(AudioOffload, false) },
            SettingsChild(context.getString(R.string.bit_perfect_usb_title), "bit_perfect_usb", listOf("usb", "dac", "bit perfect")),
            SettingsChild(context.getString(R.string.direct_usb_driver_title), "direct_usb_driver", listOf("usb", "dac", "direct", "experimental", "driver")) { SearchResultSwitch(moe.rukamori.archivetune.constants.DirectUsbDriverKey, false) },
            SettingsChild("USB exclusive output", "usb_exclusive_audio", listOf("usb", "exclusive", "usb exclusive", "dac", "usb dac", "aaudio", "direct", "bypass mixer", "experimental audio")),
            SettingsChild("Audio processing", "audio_processing",
                listOf("audio processing", "dsp", "dsp engine", "audio engine", "equalizer", "equaliser",
                    "offload", "audio offload", "normalization")) { SearchResultSwitch(AudioProcessingEnabledKey, AUDIO_PROCESSING_ENABLED_DEFAULT) },
            SettingsChild("Seek seconds add-up", "seek_seconds", listOf("seek", "skip", "forward", "rewind", "seconds")) { SearchResultSwitch(SeekExtraSeconds, false) },
            SettingsChild("Pause on device mute", "pause_mute", listOf("mute", "pause mute", "headphone", "silence detect")) { SearchResultSwitch(PauseOnDeviceMuteKey, false) },
            SettingsChild("Device mute recovery volume", "device_mute_recovery_volume", listOf("recovery volume", "mute recovery", "volume restore")),
            SettingsChild("Auto start on Bluetooth", "bluetooth_auto_start", listOf("bluetooth", "auto start", "auto play", "connect")) { SearchResultSwitch(AutoStartOnBluetoothKey, false) },
            SettingsChild("ArchiveTune Canvas", "archive_tune_canvas", listOf("canvas", "animated artwork", "motion artwork", "live artwork", "canvas settings")),
            SettingsChild("Spotify Canvas", "spotify_canvas", listOf("spotify", "canvas", "spotify canvas", "looping video", "music video", "video artwork")) { SearchResultSwitch(SpotifyCanvasKey, false) },
            SettingsChild("Canvas resolvers", "canvas_resolvers", listOf("canvas resolver", "canvas resolvers", "canvas endpoint", "canvas fallback", "spotify canvas resolver")),
            SettingsChild("Persistent queue", "persistent_queue", listOf("queue", "persistent", "save queue", "resume")) { SearchResultSwitch(PersistentQueueKey, true) },
            SettingsChild(context.getString(R.string.queue_infinite), "infinite_queue", listOf("queue", "infinite", "autoplay", "endless", "radio", "continuation")) { SearchResultSwitch(AutoLoadMoreKey, true) },
            SettingsChild("Permanent shuffle", "permanent_shuffle", listOf("shuffle", "random", "permanent")) { SearchResultSwitch(PermanentShuffleKey, false) },
            SettingsChild("Auto skip on error", "auto_skip_error", listOf("skip", "error", "auto skip", "failed")) { SearchResultSwitch(AutoSkipNextOnErrorKey, false) },
            SettingsChild("Stop music on task clear", "stop_task_clear", listOf("stop", "task clear", "background", "close app")) { SearchResultSwitch(StopMusicOnTaskClearKey, false) },
            SettingsChild("Wakelock", "wakelock", listOf("wakelock", "wake lock", "keep awake", "cpu")) { SearchResultSwitch(WakelockKey, false) },
            SettingsChild("Artist separators", "artist_separators", listOf("artist", "separator", "split", "featuring")),
            SettingsChild("Manage playlist tags", "manage_playlist_tags", listOf("playlist tags", "tag management", "organize playlists")),
            SettingsChild("Display codec on player", "show_codec_on_player", listOf("display codec", "show codec", "codec on player", "bitrate on player")),
            SettingsChild("Audio quality", "audio_quality", listOf("audio quality", "quality", "bitrate", "sound quality", "streaming quality", "high quality")),
            SettingsChild("Artwork priority", "artwork_priority", listOf("artwork priority", "artwork order", "cover priority", "artwork provider order", "artwork source order")),
            SettingsChild("Preferred sources", "preferred_sources", listOf("preferred sources", "source priority", "source order", "audio source order", "which source first")),
            SettingsChild("Auto choose playback client", "auto_choose_playback_client", listOf("auto choose client", "playback client auto", "automatic client", "client selection")),
            SettingsChild("Playback client", "player_stream_client", listOf("playback client", "stream client", "player client", "innertube client", "android vr", "ios client", "web client")),
            SettingsChild("Skip gapless albums", "crossfade_gapless_title", listOf("skip gapless albums", "gapless album", "gapless")),
            SettingsChild("Progressive seek", "seek_seconds_addup", listOf("progressive seek", "seek add up", "seek accumulate", "double tap seek")),
            SettingsChild("Enable swipe to change song", "enable_swipe_thumbnail", listOf("swipe thumbnail", "swipe to change song", "swipe artwork", "swipe track")),
            SettingsChild("Mini player swipe sensitivity", "swipe_sensitivity", listOf("swipe sensitivity", "mini player swipe", "gesture sensitivity")),
            SettingsChild("Check source", "check_source", listOf("check source", "source health", "test source", "source diagnostics", "verify source", "source status")),
            SettingsChild("Spotify catalog", "spotify_catalog_source", listOf("spotify catalog", "spotify metadata", "spotify source")),
            SettingsChild("Enable Tidal source", "tidal_enable", listOf("tidal", "enable tidal", "tidal source", "lossless", "hifi")),
            SettingsChild("Use my Tidal account first", "tidal_account_first", listOf("tidal account first", "my tidal account", "prefer my account")),
            SettingsChild("Tidal audio quality", "tidal_audio_quality", listOf("tidal quality", "tidal audio quality", "tidal hifi", "tidal max", "mqa")),
            SettingsChild("Manage Tidal instances", "tidal_manage_instances", listOf("tidal instances", "tidal server", "tidal endpoint", "manage instances")),
            SettingsChild("Enable Qobuz source", "qobuz_enable", listOf("qobuz", "enable qobuz", "qobuz source", "hi-res", "flac")),
            SettingsChild("Amazon Music", "amazon_enable", listOf("amazon", "amazon music", "enable amazon", "amazon source")),
            SettingsChild("Enable QQ Music", "qq_music_enable", listOf("qq", "qq music", "enable qq music", "qq source")),
            SettingsChild("Enable Spotify streaming", "spotify_stream_enable", listOf("spotify streaming", "spotify source", "enable spotify streaming")),
            SettingsChild("Enable Qobuz backup server", "qobuz_backup_enable", listOf("qobuz backup", "backup server", "qobuz backup server", "lossless backup", "fallback server", "kouzu")),
            SettingsChild("Manage Qobuz instances", "qobuz_manage_instances", listOf("qobuz instances", "qobuz server", "qobuz endpoint", "manage instances")),
            SettingsChild("Enable Deezer source", "deezer_enable", listOf("deezer", "enable deezer", "deezer source", "flac")),
            SettingsChild("Deezer audio quality", "deezer_audio_quality", listOf("deezer quality", "deezer audio quality", "deezer flac")),
            SettingsChild("Enable JioSaavn source", "jiosaavn_enable", listOf("jiosaavn", "jio saavn", "saavn", "enable jiosaavn", "indian music")),
            SettingsChild("yt-dlp runtime", "ytdlp", listOf("yt-dlp", "ytdlp", "youtube-dl", "extractor", "downloader runtime", "yt dlp version")),
        ),
    )

internal fun canvasSettingsItem(
    navController: NavController,
    context: Context,
    colorScheme: ColorScheme,
): SettingsItem =
    SettingsItem(
        key = "canvas",
        icon = R.drawable.motion_photos_on,
        title = context.getString(R.string.archivetune_canvas),
        subtitle = context.getString(R.string.canvas_settings_subtitle),
        accentColor = colorScheme.tertiary,
        keywords = listOf("canvas", "animated artwork", "motion artwork", "live artwork", "video artwork", "wifi", "metered", "cache"),
        onClick = { navController.navigate("settings/canvas") },
    )

// Sources → JioSaavn sub-page.
internal fun jioSaavnSettingsItem(
    navController: NavController,
    colorScheme: ColorScheme,
): SettingsItem =
    SettingsItem(
        key = "jiosaavn",
        icon = R.drawable.provider_tidal,
        title = "JioSaavn",
        subtitle = "JioSaavn audio source",
        accentColor = colorScheme.tertiary,
        keywords = listOf("jiosaavn", "jio saavn", "saavn", "indian music", "bollywood", "vivimusic"),
        onClick = { navController.navigate("settings/jiosaavn") },
        hidden = true,
        children = listOf(
            SettingsChild("JioSaavn audio quality", "jiosaavn_audio_quality", listOf("jiosaavn quality", "saavn audio quality", "jiosaavn bitrate")),
            SettingsChild("JioSaavn credit", "jiosaavn_credit", listOf("jiosaavn credit", "vivimusic", "jiosaavn about")),
        ),
    )

// Sources → Amazon Music sub-page.
internal fun amazonSettingsItem(
    navController: NavController,
    colorScheme: ColorScheme,
): SettingsItem =
    SettingsItem(
        key = "amazon",
        icon = R.drawable.login,
        title = "Amazon Music",
        subtitle = "Amazon Music account and audio source",
        accentColor = colorScheme.tertiary,
        keywords = listOf("amazon", "amazon music", "amazon login", "amazon hd", "amazon ultra hd"),
        onClick = { navController.navigate("settings/amazon") },
        hidden = true,
        children = listOf(
            SettingsChild("Amazon instances", "amazon_instances", listOf("amazon instance", "amazon url", "amazon server")),
            SettingsChild("Authorize with Turnstile", "amazon_authorize", listOf("amazon authorize", "amazon turnstile", "amazon login")),
            SettingsChild("Amazon bypass token", "amazon_bypass_token", listOf("amazon bypass", "amazon token")),
            SettingsChild("Amazon audio quality", "amazon_audio_quality", listOf("amazon quality", "amazon hd", "amazon ultra hd")),
        ),
    )

// Sources → QQ Music sub-page. The screen carries the QR sign-in as well as the source's own
// switches, so the account row is indexed too: searching for a sign-in should reach it.
internal fun qqMusicSettingsItem(
    navController: NavController,
    colorScheme: ColorScheme,
): SettingsItem =
    SettingsItem(
        key = "qqmusic",
        icon = R.drawable.ic_music,
        title = "QQ Music",
        subtitle = "QQ Music account and audio source",
        accentColor = colorScheme.tertiary,
        keywords = listOf("qq", "qq music", "qqmusic", "tencent", "tme", "qr login", "sign in"),
        onClick = { navController.navigate("settings/qqmusic") },
        hidden = true,
        children = listOf(
            SettingsChild("QQ Music account", "qq_music_account", listOf("qq music login", "qq music sign in", "qr code", "scan code")),
            SettingsChild("QQ Music quality", "qq_music_quality", listOf("qq quality", "qq lossless", "qq flac")),
        ),
    )

// Sources → Spotify streaming sub-page. A second, streaming-only Spotify account; the catalog
// sign-in lives under Integration and is indexed separately.
internal fun spotifyStreamingSettingsItem(
    navController: NavController,
    colorScheme: ColorScheme,
): SettingsItem =
    SettingsItem(
        key = "spotify_streaming",
        icon = R.drawable.spotify_icon,
        title = "Spotify streaming",
        subtitle = "Experimental audio source on a separate Spotify account",
        accentColor = colorScheme.tertiary,
        keywords = listOf("spotify", "spotify streaming", "spotify audio", "stopify", "echo", "streaming account", "sign in"),
        onClick = { navController.navigate("settings/spotify_streaming") },
        hidden = true,
        children = listOf(
            SettingsChild("Spotify streaming risks", "spotify_stream_warning", listOf("spotify warning", "spotify ban", "spotify terms")),
            SettingsChild("Spotify streaming account", "spotify_stream_account", listOf("spotify streaming login", "spotify second account", "spotify sign out")),
            SettingsChild("Spotify resolve method", "spotify_stream_method", listOf("spotify method", "stopify", "echo", "spotify flac", "spotify vorbis")),
        ),
    )

// Sources → Deezer sub-page.
internal fun deezerSettingsItem(
    navController: NavController,
    colorScheme: ColorScheme,
): SettingsItem =
    SettingsItem(
        key = "deezer",
        icon = R.drawable.provider_tidal,
        title = "Deezer",
        subtitle = "Deezer account and audio source",
        accentColor = colorScheme.tertiary,
        keywords = listOf("deezer", "deezer account", "deezer login", "arl", "deezer premium", "flac"),
        onClick = { navController.navigate("settings/deezer") },
        hidden = true,
        children = listOf(
            SettingsChild("Sign in to Deezer", "deezer_login", listOf("deezer login", "deezer sign in", "connect deezer", "deezer arl")),
            SettingsChild("Sign out of Deezer", "deezer_sign_out", listOf("deezer logout", "deezer sign out", "disconnect deezer")),
            SettingsChild("Enable Deezer source", "deezer_enable", listOf("enable deezer", "deezer source", "turn on deezer")),
            SettingsChild("Deezer audio quality", "deezer_audio_quality", listOf("deezer quality", "deezer audio quality", "deezer flac")),
        ),
    )

internal fun sourcesSettingsItem(
    navController: NavController,
    context: Context,
    colorScheme: ColorScheme,
): SettingsItem =
    SettingsItem(
        key = "sources",
        icon = R.drawable.ic_music,
        title = context.getString(R.string.source_settings),
        subtitle = context.getString(R.string.source_settings_subtitle),
        accentColor = colorScheme.tertiary,
        keywords = listOf("source", "music source", "youtube music", "spotify", "metadata source", "search source", "tidal", "qobuz", "provider", "streaming", "telegram", "telegram channel", "flac", "lossless", "private channel"),
        onClick = { navController.navigate("settings/sources") },
        // Playback sub-page anchor, kept in the search index so existing search shortcuts
        // still work.
        hidden = true,
        children = listOf(
            SettingsChild("YouTube Music", "youtube_music", listOf("youtube", "youtube music", "yt music")),
            SettingsChild("Default metadata source", "default_metadata_source", listOf("metadata", "spotify", "catalog", "artwork")),
            SettingsChild("Default search source", "default_search_source", listOf("search", "spotify", "youtube", "provider")),
            SettingsChild("Qobuz", "qobuz", listOf("qobuz", "hires", "hi-res", "flac", "lossless", "cd quality")),
            SettingsChild("Tidal", "tidal", listOf("tidal", "lossless", "hifi", "master", "mq")),
        ),
    )

internal fun lyricsSettingsItem(
    navController: NavController,
    context: Context,
    colorScheme: ColorScheme,
): SettingsItem =
    SettingsItem(
        key = "lyrics",
        icon = R.drawable.ic_lyrics,
        title = context.getString(R.string.lyrics),
        subtitle = context.getString(R.string.settings_lyrics_subtitle),
        accentColor = colorScheme.secondary,
        keywords = listOf("lyrics", "lyric", "subtitle", "text", "sing along", "lrc", "translation", "romanize", "karaoke"),
        onClick = { navController.navigate("settings/lyrics") },
        // Playback sub-page anchor, kept in the search index so existing search shortcuts
        // still work.
        hidden = true,
        children = listOf(
            SettingsChild("Lyrics provider", "lyrics_provider", listOf("lyrics provider", "source", "lrclib", "kugou", "netease", "musixmatch", "paxsenix", "betterlyrics", "portato", "youlyplus", "unison", "simpmusic", "megalobiz")),
            SettingsChild("Lyrics mode", "lyrics_mode", listOf("lyrics mode", "lyrics style", "lyrics display mode", "karaoke mode")),
            SettingsChild("Show lyrics", "show_lyrics", listOf("show lyrics", "display lyrics", "lyrics toggle", "lyrics show")) { SearchResultSwitch(ShowLyricsKey, false) },
            SettingsChild("Use lyrics V2", "use_lyrics_v2", listOf("lyrics v2", "new lyrics", "lyrics engine")),
            SettingsChild("Lyrics font size", "lyrics_font_size", listOf("font size", "lyrics size", "text size", "lyrics text size")),
            SettingsChild("Lyrics line spacing", "lyrics_line_spacing", listOf("line spacing", "lyrics spacing", "lyrics line gap", "lyrics padding")),
            SettingsChild("Lyrics animations", "lyrics_animations", listOf("animation", "animated lyrics", "lyrics effect")),
            SettingsChild("Lyrics animation style", "lyrics_animation_style", listOf("animation style", "lyrics animation", "lyrics motion", "lyrics transition")),
            SettingsChild("Lyrics line blur", "lyrics_line_blur", listOf("lyrics blur", "line blur", "focus blur")),
            SettingsChild("Lyrics romanize Japanese", "lyrics_romanize_japanese", listOf("romanize", "japanese", "romaji", "furigana")),
            SettingsChild("Lyrics romanize Korean", "lyrics_romanize_korean", listOf("romanize", "korean", "romanization")),
            SettingsChild("Lyrics romanize Chinese", "lyrics_romanize_chinese", listOf("romanize", "chinese", "pinyin")),
            SettingsChild("Lyrics romanize Hindi", "lyrics_romanize_hindi", listOf("romanize", "hindi", "devanagari")),
            SettingsChild("Lyrics romanize other languages", "lyrics_romanize_other", listOf("romanize", "other languages", "arabic", "thai", "cyrillic")),
            SettingsChild("Lyrics click to seek", "lyrics_click", listOf("click lyrics", "tap lyrics", "seek lyrics")) { SearchResultSwitch(LyricsClickKey, false) },
            SettingsChild("Lyrics auto-scroll", "lyrics_scroll", listOf("scroll", "auto scroll", "lyrics scroll")) { SearchResultSwitch(LyricsScrollKey, true) },
            SettingsChild("Preload queue lyrics", "preload_queue_lyrics", listOf("preload", "preload lyrics", "queue lyrics", "preload count", "queue lyrics count", "preload amount", "preload size")),
            SettingsChild("Lyrics background style", "lyrics_background_style", listOf("lyrics background", "lyrics bg")),
            SettingsChild("BetterLyrics", "betterlyrics", listOf("betterlyrics", "better lyrics", "better lyrics provider")),
            SettingsChild("BetterLyrics Portato", "betterlyrics_portato", listOf("portato", "betterlyrics portato", "portato lyrics")),
            SettingsChild("YouLyPlus Lyrics", "youlyplus_lyrics", listOf("youlyplus", "youlyplus lyrics", "youly plus")),
            SettingsChild("LRCLIB", "lrclib", listOf("lrclib", "lrclib lyrics", "lrclib provider")),
            SettingsChild("Kugou Lyrics", "kugou", listOf("kugou", "kugou lyrics", "kugou provider", "kugou music")),
            SettingsChild("Unison Lyrics", "unison_lyrics", listOf("unison", "unison lyrics", "unison provider")),
            SettingsChild("Simpmusic Lyrics", "simpmusic_lyrics", listOf("simpmusic", "simpmusic lyrics", "simpmusic provider")),
            SettingsChild("Megalobiz Lyrics", "megalobiz_lyrics", listOf("megalobiz", "megalobiz lyrics", "megalobiz provider")),
            SettingsChild("Paxsenix Lyrics", "paxsenix_lyrics", listOf("paxsenix", "paxsenix lyrics", "paxsenix provider")),
            SettingsChild("Paxsenix Stats", "paxsenix_stats", listOf("paxsenix stats", "paxsenix statistics", "paxsenix analytics")),
            SettingsChild("First lyrics provider", "first_lyrics_provider", listOf("first lyrics", "lyrics priority", "primary lyrics provider", "lyrics order")),
            SettingsChild("Preferred lyrics provider", "set_first_lyrics_provider", listOf("preferred lyrics provider", "default lyrics provider", "lyrics priority")),
            SettingsChild("Prioritize word synced lyrics", "prioritize_word_synced_lyrics", listOf("word synced", "word by word", "karaoke lyrics", "prioritize word synced")),
            SettingsChild("Providers", "providers", listOf("lyrics providers", "providers", "lyrics sources", "which lyrics provider")),
            SettingsChild("Romanization", "romanization", listOf("romanization", "romanisation", "romanize", "romaji", "transliteration")),
            SettingsChild("Additional downloads", "language_packs", listOf("additional downloads", "language pack", "language packs", "romanization data", "dictionary", "fonts", "spatialflow font", "addons")),
            SettingsChild("Enable Tidal lyrics", "enable_tidal_lyrics", listOf("tidal lyrics", "enable tidal lyrics", "tidal lyric provider")),
            SettingsChild("Enable Deezer lyrics", "enable_deezer_lyrics", listOf("deezer lyrics", "enable deezer lyrics", "deezer lyric provider")),
            SettingsChild("Musixmatch (experimental)", "enable_musixmatch_experimental", listOf("musixmatch", "musixmatch experimental", "musixmatch lyrics")),
            SettingsChild("Paxsenix API key", "paxsenix_api_key", listOf("paxsenix api key", "paxsenix key", "paxsenix token")),
            SettingsChild("Paxsenix endpoint", "paxsenix_endpoint", listOf("paxsenix endpoint", "paxsenix url", "paxsenix server")),
            SettingsChild("Lyrics text size", "lyrics_text_size", listOf("lyrics text size", "lyrics font size", "lyrics size", "bigger lyrics")),
        ),
    )

// Lyrics → Providers sub-page.
internal fun lyricsProvidersSettingsItem(
    navController: NavController,
    colorScheme: ColorScheme,
): SettingsItem =
    SettingsItem(
        key = "lyrics_providers",
        icon = R.drawable.lyrics,
        title = "Lyrics providers",
        subtitle = "Enable and prioritise lyrics sources",
        accentColor = colorScheme.secondary,
        keywords = listOf("lyrics provider", "lyrics providers", "lyrics source", "lrclib", "kugou", "musixmatch", "betterlyrics", "paxsenix", "youlyplus", "unison", "simpmusic", "megalobiz", "lyprio"),
        onClick = { navController.navigate("settings/lyrics/providers") },
        hidden = true,
        children = listOf(
            SettingsChild("Prioritize word synced lyrics", "prioritize_word_synced_lyrics", listOf("word synced", "word by word", "karaoke lyrics")),
            SettingsChild("Enable Lyprio lyrics", "enable_lyprio_lyrics", listOf("lyprio", "lyrics server")),
            SettingsChild("Enable BetterLyrics", "enable_betterlyrics", listOf("betterlyrics", "better lyrics", "enable betterlyrics")),
            SettingsChild("Enable BetterLyrics Portato", "enable_betterlyrics_portato", listOf("portato", "betterlyrics portato", "portato lyrics")),
            SettingsChild("Enable YouLyPlus lyrics", "enable_youlyplus_lyrics", listOf("youlyplus", "youly plus", "youlyplus lyrics")),
            SettingsChild("Enable LrcLib lyrics provider", "enable_lrclib", listOf("lrclib", "lrc lib", "lrclib lyrics")),
            SettingsChild("Enable KuGou lyrics provider", "enable_kugou", listOf("kugou", "kugou lyrics", "chinese lyrics")),
            SettingsChild("Enable Unison lyrics", "enable_unison_lyrics", listOf("unison", "unison lyrics")),
            SettingsChild("Enable SimpMusic lyrics", "enable_simpmusic_lyrics", listOf("simpmusic", "simp music", "simpmusic lyrics")),
            SettingsChild("Enable Megalobiz lyrics", "enable_megalobiz_lyrics", listOf("megalobiz", "megalobiz lyrics")),
            SettingsChild("Enable Paxsenix lyrics", "enable_paxsenix_lyrics", listOf("paxsenix", "paxsenix lyrics")),
            SettingsChild("Enable Tidal lyrics", "enable_tidal_lyrics", listOf("tidal lyrics", "enable tidal lyrics")),
            SettingsChild("Enable Deezer lyrics", "enable_deezer_lyrics", listOf("deezer lyrics", "enable deezer lyrics")),
            SettingsChild("Musixmatch (experimental)", "enable_musixmatch_experimental", listOf("musixmatch", "musixmatch experimental")),
            SettingsChild("Paxsenix API stats", "paxsenix_stats", listOf("paxsenix stats", "paxsenix usage", "paxsenix quota")),
            SettingsChild("Paxsenix API key", "paxsenix_api_key", listOf("paxsenix api key", "paxsenix key")),
            SettingsChild("Paxsenix endpoint", "paxsenix_endpoint", listOf("paxsenix endpoint", "paxsenix url")),
            SettingsChild("Check Paxsenix endpoints", "paxsenix_check_endpoints", listOf("check paxsenix", "paxsenix endpoints", "test paxsenix", "paxsenix status", "endpoint check")),
            SettingsChild("Preferred lyrics provider", "set_first_lyrics_provider", listOf("preferred lyrics provider", "first lyrics provider", "lyrics priority")),
        ),
    )

// Lyrics → Romanisation sub-page.
internal fun lyricsRomanisationSettingsItem(
    navController: NavController,
    colorScheme: ColorScheme,
): SettingsItem =
    SettingsItem(
        key = "lyrics_romanisation",
        icon = R.drawable.translate,
        title = "Lyrics romanization",
        subtitle = "Transliterate non-Latin lyrics",
        accentColor = colorScheme.secondary,
        keywords = listOf("romanization", "romanisation", "romanize", "romaji", "pinyin", "transliteration", "furigana", "hangul"),
        onClick = { navController.navigate("settings/lyrics/romanisation") },
        hidden = true,
        children = listOf(
            SettingsChild("Romanize japanese lyrics", "lyrics_romanize_japanese", listOf("romanize japanese", "romaji", "furigana", "japanese lyrics")),
            SettingsChild("Romanize korean lyrics", "lyrics_romanize_korean", listOf("romanize korean", "hangul", "korean lyrics")),
            SettingsChild("Romanize chinese lyrics", "lyrics_romanize_chinese", listOf("romanize chinese", "pinyin", "chinese lyrics")),
            SettingsChild("Romanize hindi lyrics", "lyrics_romanize_hindi", listOf("romanize hindi", "devanagari", "hindi lyrics")),
            SettingsChild("Romanize other non-latin lyrics", "lyrics_romanize_other_languages", listOf("romanize other", "arabic", "thai", "cyrillic", "other languages")),
        ),
    )
