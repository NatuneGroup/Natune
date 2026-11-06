package moe.rukamori.archivetune.ui.screens.settings

import android.content.Context
import androidx.compose.material3.ColorScheme
import androidx.navigation.NavController
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.EnableDiscordRPCKey
import moe.rukamori.archivetune.constants.EnableLastFMScrobblingKey
import moe.rukamori.archivetune.constants.EnableTranslatorKey
import moe.rukamori.archivetune.constants.ListenBrainzEnabledKey
import moe.rukamori.archivetune.constants.ListenTogetherSyncVolumeKey
import moe.rukamori.archivetune.constants.ShowSpotifyPlaylistsKey
import moe.rukamori.archivetune.constants.TelegramLosslessOnlyKey
import moe.rukamori.archivetune.constants.TidalEnabledKey
import moe.rukamori.archivetune.constants.UsePoolAccountsKey

internal fun integrationSettingsItem(
    navController: NavController,
    context: Context,
    colorScheme: ColorScheme,
): SettingsItem =
    SettingsItem(
        key = "integration",
        icon = R.drawable.auto_awesome,
        title = context.getString(R.string.integration),
        subtitle = context.getString(R.string.settings_integration_subtitle),
        accentColor = colorScheme.secondary,
        keywords = listOf("integration", "lastfm", "last.fm", "libre.fm", "scrobble", "scrobbling", "discord", "listenbrainz", "spotify", "apple music"),
        onClick = { navController.navigate("settings/integration") },
        children = listOf(
            SettingsChild("Apple Music login", "applemusic", listOf("apple music", "applemusic", "itunes", "music kit", "apple login", "apple music login")),
            SettingsChild("Last.fm scrobbling", "lastfm_scrobbling", listOf("lastfm", "last.fm", "libre.fm", "scrobble", "scrobbling", "listens")) { SearchResultSwitch(EnableLastFMScrobblingKey, false) },
            SettingsChild("Last.fm account", "lastfm_account", listOf("lastfm account", "lastfm login", "lastfm session", "lastfm username")),
            SettingsChild("Last.fm options", "lastfm_options", listOf("lastfm options", "lastfm settings", "scrobble toggle", "now playing")),
            SettingsChild("Last.fm scrobbling configuration", "lastfm_scrobbling_config", listOf("scrobble config", "scrobble configuration", "scrobble threshold", "scrobble percentage")),
            SettingsChild("Discord rich presence", "discord_presence", listOf("discord", "rich presence", "rpc", "status", "now playing")) { SearchResultSwitch(EnableDiscordRPCKey, false) },
            SettingsChild("Discord account", "discord_account", listOf("discord account", "discord login", "discord token", "discord authorization")),
            SettingsChild("Discord options", "discord_options", listOf("discord options", "discord refresh", "refresh discord")),
            SettingsChild("Discord connection settings", "discord_connection", listOf("discord connection", "activity status", "platform status", "discord platform")),
            SettingsChild("Discord activity content", "discord_activity", listOf("discord activity", "activity name", "activity details", "activity state", "activity type", "discord show when paused")),
            SettingsChild("Discord image options", "discord_images", listOf("discord image", "large image", "large text", "discord artwork", "discord cover")),
            SettingsChild("ListenBrainz", "listenbrainz", listOf("listenbrainz", "listen brainz", "scrobble")) { SearchResultSwitch(ListenBrainzEnabledKey, false) },
            SettingsChild("ListenBrainz token", "listenbrainz_token", listOf("listenbrainz token", "listenbrainz api key", "listenbrainz credential")),
            SettingsChild("Listen Together", "listen_together", listOf("listen together", "listen together settings", "room", "shared playback", "sync playback", "listen party", "group listening", "chat", "together")) { SearchResultSwitch(ListenTogetherSyncVolumeKey, false) },
            SettingsChild("Listen Together screen", "listen_together_screen", listOf("listen together screen", "create room", "join room", "room code", "share room", "invite friends")),
            SettingsChild("Spotify", "spotify", listOf("spotify", "spotify connect", "spotify playlists")) { SearchResultSwitch(ShowSpotifyPlaylistsKey, false) },
            SettingsChild("Use pool accounts", "use_pool_accounts", listOf("pool", "source pool", "pool accounts", "shared accounts", "community accounts")) { SearchResultSwitch(UsePoolAccountsKey, true) },
            SettingsChild("Tidal", "tidal", listOf("tidal", "hifi", "master", "mqa", "lossless", "flac")) { SearchResultSwitch(TidalEnabledKey, false) },
            SettingsChild("Tidal account", "tidal_account", listOf("tidal account", "tidal login", "tidal token", "tidal session")),
            SettingsChild("Tidal instances", "tidal_instances", listOf("tidal instance", "tidal server", "tidal url", "tidal endpoint")),
            SettingsChild("Qobuz", "qobuz", listOf("qobuz", "hires", "hi-res", "flac", "lossless", "cd quality")),
            SettingsChild("Qobuz account", "qobuz_account", listOf("qobuz account", "qobuz login", "qobuz email", "qobuz session")),
            SettingsChild("Qobuz tokens", "qobuz_tokens", listOf("qobuz token", "qobuz app secret", "qobuz credential")),
            SettingsChild("Qobuz instances", "qobuz_instances", listOf("qobuz instance", "qobuz server", "qobuz url", "qobuz endpoint")),
            SettingsChild("Deezer", "deezer", listOf("deezer", "deezer login", "deezer premium", "deezer account", "deezer session")),
            SettingsChild("Telegram", "telegram", listOf("telegram", "telegram channel", "channel sync", "telegram music", "telegram bot")),
            SettingsChild("Telegram login", "telegram_login", listOf("telegram login", "telegram session", "telegram account", "sign in telegram")),
            SettingsChild("Telegram browse channels", "telegram_browse_channels", listOf("browse channels", "channels", "telegram channels", "music channels")),
            SettingsChild("Telegram lossless only", "telegram_lossless_only", listOf("lossless", "flac", "lossless only", "high quality")) { SearchResultSwitch(TelegramLosslessOnlyKey, false) },
            SettingsChild("Telegram logout", "telegram_logout", listOf("logout", "log out", "sign out", "disconnect telegram")),
            SettingsChild("Import playlist from another service", "cross_service_import", listOf("import", "import playlist", "cross service", "youtube music import", "apple music import", "amazon music import", "tidal import", "deezer import", "playlist url", "import url", "import from url", "playlist from url")),
            SettingsChild("Enable scrobbling", "enable_scrobbling", listOf("enable scrobbling", "scrobble", "scrobbler", "lastfm scrobble")) { SearchResultSwitch(EnableLastFMScrobblingKey, false) },
            SettingsChild("Now playing", "lastfm_now_playing", listOf("now playing", "lastfm now playing", "scrobble now playing", "update now playing")),
            SettingsChild("Prefer YouTube thumbnails", "lastfm_prefer_yt_thumbnails", listOf("prefer youtube thumbnails", "lastfm thumbnails", "scrobble artwork")),
            SettingsChild("Minimum track duration", "scrobble_min_track_duration", listOf("minimum track duration", "scrobble minimum", "min duration", "scrobble threshold")),
            SettingsChild("Scrobble delay percent", "scrobble_delay_percent", listOf("scrobble delay percent", "scrobble percentage", "scrobble after percent")),
            SettingsChild("Scrobble delay (seconds)", "scrobble_delay_minutes", listOf("scrobble delay", "scrobble seconds", "scrobble delay minutes")),
            SettingsChild("Connect Last.fm", "lastfm_connect_button", listOf("connect lastfm", "lastfm login", "lastfm sign in")),
            SettingsChild("Connect Libre.fm", "lastfm_connect_librefm_button", listOf("librefm", "libre.fm", "connect librefm", "librefm login")),
            SettingsChild("Connect custom GNU FM server", "lastfm_connect_custom_button", listOf("custom scrobble server", "gnu fm", "custom lastfm server", "self hosted scrobbler")),
            SettingsChild("Activity status", "activity_status", listOf("discord activity status", "activity status", "online status")),
            SettingsChild("Platform", "platform_status", listOf("discord platform", "platform status", "desktop mobile status")),
            SettingsChild("Activity name", "discord_activity_name", listOf("discord activity name", "activity name", "rpc name")),
            SettingsChild("Activity details", "discord_activity_details", listOf("discord activity details", "activity details", "rpc details")),
            SettingsChild("Activity state", "discord_activity_state", listOf("discord activity state", "activity state", "rpc state")),
            SettingsChild("Activity type", "discord_activity_type", listOf("discord activity type", "activity type", "listening playing")),
            SettingsChild("Show RPC when paused", "discord_show_when_paused", listOf("show when paused", "discord paused", "rpc paused")),
            SettingsChild("Large image", "large_image", listOf("discord large image", "large image", "rpc large image")),
            SettingsChild("Large text", "large_text", listOf("discord large text", "large text", "rpc large text")),
            SettingsChild("Small image", "small_image", listOf("discord small image", "small image", "rpc small image")),
            SettingsChild("Discord experimental options", "discord_experimental", listOf("discord experimental", "discord buttons", "rpc buttons", "discord translator")),
            SettingsChild("Telegram bots", "telegram_bots_title", listOf("telegram bots", "telegram bot", "bot token", "music bot")),
        ),
    )

// Integration → Discord experimental sub-page.
internal fun discordExperimentalSettingsItem(
    navController: NavController,
    colorScheme: ColorScheme,
): SettingsItem =
    SettingsItem(
        key = "discord_experimental",
        icon = R.drawable.auto_awesome,
        title = "Discord experimental",
        subtitle = "Rich presence buttons and translation",
        accentColor = colorScheme.secondary,
        keywords = listOf("discord experimental", "discord buttons", "rich presence buttons", "rpc buttons", "discord translator"),
        onClick = { navController.navigate("settings/discord/experimental") },
        hidden = true,
        children = listOf(
            SettingsChild("Show button 1", "discord_show_button_1", listOf("discord button", "show button", "rpc button 1")),
            SettingsChild("Button 1 URL source", "discord_activity_button_1_url", listOf("button 1 url", "discord button url", "rpc button link")),
            SettingsChild("Show button 2", "discord_show_button_2", listOf("discord button 2", "show second button", "rpc button 2")),
            SettingsChild("Button 2 URL source", "discord_activity_button_2_url", listOf("button 2 url", "discord second button url", "rpc button 2 link")),
        ),
    )

// Integration → Tidal account + instance management sub-page.
internal fun tidalDetailSettingsItem(
    navController: NavController,
    colorScheme: ColorScheme,
): SettingsItem =
    SettingsItem(
        key = "tidal",
        icon = R.drawable.provider_tidal,
        title = "Tidal",
        subtitle = "Tidal account and instances",
        accentColor = colorScheme.tertiary,
        keywords = listOf("tidal", "tidal account", "tidal instances", "hifi", "mqa", "lossless", "flac", "tidal login"),
        onClick = { navController.navigate("settings/tidal") },
        hidden = true,
        children = listOf(
            SettingsChild("Tidal animated covers", "tidal_animated_covers", listOf("tidal animated covers", "tidal canvas", "tidal video cover", "animated cover")),
            SettingsChild("Tidal artwork fallback", "tidal_artwork_fallback", listOf("tidal artwork", "artwork fallback", "tidal cover", "hi-res artwork")),
            SettingsChild("Tidal account", "tidal_account_connected", listOf("tidal account", "connected account", "tidal user")),
            SettingsChild("Sign in with Tidal (web)", "tidal_login_web", listOf("tidal login", "tidal sign in", "tidal web login", "connect tidal")),
            SettingsChild("Reconnect Tidal account", "tidal_reconnect", listOf("reconnect tidal", "refresh tidal", "tidal reconnect")),
            SettingsChild("Disconnect Tidal account", "tidal_disconnect", listOf("disconnect tidal", "tidal logout", "tidal sign out")),
            SettingsChild("Manage instances", "source_manage_instances", listOf("manage instances", "tidal instances", "tidal servers")),
            SettingsChild("Add instance", "tidal_add_instance", listOf("add instance", "add tidal instance", "new tidal server")),
            SettingsChild("Add many (paste)", "source_bulk_add", listOf("bulk add", "add many", "paste instances")),
            SettingsChild("Copy online", "source_copy_online", listOf("copy online", "copy working instances", "share instances")),
            SettingsChild("Remove dead", "source_remove_dead", listOf("remove dead", "remove dead instances", "clean instances")),
            SettingsChild("Remove deprecated", "source_remove_deprecated", listOf("remove deprecated", "remove old instances")),
            SettingsChild("Clear all instances", "tidal_reset_instances", listOf("clear instances", "reset instances", "delete all instances")),
        ),
    )

// Integration → Qobuz account, tokens and instance management sub-page.
internal fun qobuzDetailSettingsItem(
    navController: NavController,
    colorScheme: ColorScheme,
): SettingsItem =
    SettingsItem(
        key = "qobuz",
        icon = R.drawable.provider_tidal,
        title = "Qobuz",
        subtitle = "Qobuz account, tokens and instances",
        accentColor = colorScheme.tertiary,
        keywords = listOf("qobuz", "qobuz account", "qobuz tokens", "qobuz instances", "hi-res", "flac", "cd quality", "24 bit", "qobuz login"),
        onClick = { navController.navigate("settings/qobuz") },
        hidden = true,
        children = listOf(
            SettingsChild("Qobuz audio quality", "qobuz_audio_quality", listOf("qobuz quality", "hi-res", "flac", "cd quality", "24 bit")),
            SettingsChild("Sign in with Qobuz (web)", "qobuz_login_web", listOf("qobuz login", "qobuz sign in", "qobuz web login", "connect qobuz")),
            SettingsChild("Add tokens (paste)", "qobuz_add_tokens", listOf("add qobuz tokens", "qobuz token", "app secret", "app id", "paste tokens")),
            SettingsChild("Manage accounts", "qobuz_manage_accounts", listOf("manage qobuz accounts", "qobuz accounts", "account pool")),
            SettingsChild("Clear all tokens", "qobuz_reset_tokens", listOf("clear qobuz tokens", "reset tokens", "delete tokens")),
            SettingsChild("Manage instances", "source_manage_instances", listOf("manage instances", "qobuz instances", "qobuz servers")),
            SettingsChild("Add instance", "qobuz_add_instance", listOf("add instance", "add qobuz instance", "new qobuz server")),
            SettingsChild("Add many (paste)", "source_bulk_add", listOf("bulk add", "add many", "paste instances")),
            SettingsChild("Copy online", "source_copy_online", listOf("copy online", "copy working instances")),
            SettingsChild("Remove dead", "source_remove_dead", listOf("remove dead", "remove dead instances")),
            SettingsChild("Remove deprecated", "source_remove_deprecated", listOf("remove deprecated", "remove old instances")),
            SettingsChild("Clear all instances", "qobuz_reset_instances", listOf("clear instances", "reset instances", "delete all instances")),
        ),
    )

// Integration → Telegram sub-page.
internal fun telegramDetailSettingsItem(
    navController: NavController,
    colorScheme: ColorScheme,
): SettingsItem =
    SettingsItem(
        key = "telegram",
        icon = R.drawable.ic_telegram,
        title = "Telegram",
        subtitle = "Telegram account and channels",
        accentColor = colorScheme.tertiary,
        keywords = listOf("telegram", "telegram channel", "telegram login", "telegram music", "telegram bots", "channel sync"),
        onClick = { navController.navigate("settings/telegram") },
        hidden = true,
        children = listOf(
            SettingsChild("Signed in as", "telegram_logged_in_as", listOf("telegram account", "signed in as", "telegram user")),
            SettingsChild("Sign in with Telegram", "telegram_login", listOf("telegram login", "telegram sign in", "connect telegram", "phone code")),
            SettingsChild("Sign out", "telegram_logout", listOf("telegram logout", "telegram sign out", "disconnect telegram")),
            SettingsChild("Browse channels", "telegram_browse_channels", listOf("browse channels", "telegram channels", "music channels", "add channel")),
            SettingsChild("Lossless files only", "telegram_lossless_only", listOf("lossless only", "telegram lossless", "flac only", "high quality only")) { SearchResultSwitch(TelegramLosslessOnlyKey, false) },
            SettingsChild("Telegram bots", "telegram_bots_title", listOf("telegram bots", "bot token", "music bot")),
        ),
    )

internal fun androidAutoSettingsItem(
    navController: NavController,
    context: Context,
    colorScheme: ColorScheme,
): SettingsItem =
    SettingsItem(
        key = "android_auto",
        icon = R.drawable.directions_car,
        title = context.getString(R.string.android_auto),
        subtitle = context.getString(R.string.android_auto_settings_subtitle),
        accentColor = colorScheme.primary,
        keywords =
            listOf(
                "android auto", "car", "driving", "automotive", "projection", "car screen",
                "metered", "mobile data", "device songs", "voice search", "playback controls",
            ),
        onClick = { navController.navigate("settings/android_auto") },
    )

internal fun aiIntegrationSettingsItem(
    navController: NavController,
    context: Context,
    colorScheme: ColorScheme,
): SettingsItem =
    SettingsItem(
        key = "ai_integration",
        icon = R.drawable.ai,
        title = context.getString(R.string.ai_integration),
        subtitle = context.getString(R.string.ai_integration_desc),
        accentColor = colorScheme.secondary,
        keywords = listOf("ai", "artificial intelligence", "chatgpt", "openai", "gemini", "llm", "ai integration", "mix", "smart mix"),
        onClick = { navController.navigate("settings/ai_integration") },
        // Integration sub-page anchor, kept in the search index so existing search shortcuts
        // still work.
        hidden = true,
        children = listOf(
            SettingsChild("AI provider", "ai_provider", listOf("ai provider", "provider", "openai", "gemini", "claude", "anthropic", "model provider")),
            SettingsChild("Custom endpoint", "ai_custom_endpoint", listOf("custom endpoint", "endpoint", "base url", "api url", "custom api")),
            SettingsChild("AI API key", "ai_api_key", listOf("api key", "key", "secret", "ai key", "token")),
            SettingsChild("AI model", "ai_model", listOf("model", "ai model", "gpt", "gemini model", "claude model")),
            SettingsChild("Test API", "ai_test_api", listOf("test", "test api", "verify", "test connection", "ai test")),
            SettingsChild("Enable translator", "enable_translator", listOf("translator", "translation engine", "lyrics translator")) { SearchResultSwitch(EnableTranslatorKey, false) },
            SettingsChild("Translation contexts", "translator_contexts", listOf("translation contexts", "translator contexts", "presence fields")),
            SettingsChild("Target language", "target_language", listOf("target language", "translate to", "translation language")),
            SettingsChild("Automatic translation", "auto_translate_lyrics", listOf("automatic translation", "auto translate", "auto translate lyrics", "translate automatically")),
            SettingsChild("Don't auto translate these languages", "auto_translate_excluded_languages", listOf("excluded languages", "skip translation", "do not translate", "translation exclusions")),
            SettingsChild("Translation mode", "translate_mode", listOf("translation mode", "translate mode", "translation style")),
            SettingsChild("DeepL API key", "deepl_api_key", listOf("deepl", "deepl api key", "deepl key", "deepl token")),
            SettingsChild("DeepL formality", "deepl_formality", listOf("deepl formality", "formality", "formal informal")),
            SettingsChild("OpenRouter API key", "openrouter_api_key", listOf("openrouter", "openrouter api key", "openrouter key")),
            SettingsChild("Mistral API key", "mistral_api_key", listOf("mistral", "mistral api key", "mistral key")),
            SettingsChild("Model", "ai_model", listOf("ai model", "model", "gpt", "gemini model", "claude model")),
        ),
    )
