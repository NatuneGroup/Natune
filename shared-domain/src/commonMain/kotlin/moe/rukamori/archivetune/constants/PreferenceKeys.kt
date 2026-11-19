package moe.rukamori.archivetune.constants

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey

val DynamicThemeKey = booleanPreferencesKey("dynamicTheme")
val CustomThemeColorKey = stringPreferencesKey("customThemeColor")
val RandomThemeOnStartupKey = booleanPreferencesKey("randomThemeOnStartup")
val DarkModeKey = stringPreferencesKey("darkMode")
val PureBlackKey = booleanPreferencesKey("pureBlack")
val DisableAnimationsKey = booleanPreferencesKey("disableAnimations")
val BackAnimationStyleKey = stringPreferencesKey("backAnimationStyle")
val ForceHighRefreshRateKey = booleanPreferencesKey("forceHighRefreshRate")
val WallpaperExtractionFailedKey = booleanPreferencesKey("wallpaperExtractionFailed")
val HideStatusBarKey = booleanPreferencesKey("hideStatusBar")
// The particle opening animation drawn over the app on a cold launch. On by default: the
// animation is the feature, so the switch exists to turn it off rather than to opt in. It also
// stands down on its own when [DisableAnimationsKey] is set — see the overlay's own gate.
val SplashOverlayEnabledKey = booleanPreferencesKey("splashOverlayEnabled")
// UI scale (DPI-like) multiplier applied via a LocalDensity override in MainActivity.
// 1.0f = system default. Range clamped to [0.85f, 1.30f] in AppearanceSettings.
// Stored as a float so the slider is continuous (1% steps via 46 discrete positions).
val UiScaleFactorKey = floatPreferencesKey("uiScaleFactor")
// When true, forces the two-pane NavigationRail layout (normally reserved for
// tablet-width windows) on phone-sized windows too. Useful on landscape phones
// and on tablets where the dp breakpoint misclassifies the window.
val TabletModeEnabledKey = booleanPreferencesKey("tabletModeEnabled")
val EnableHapticFeedbackKey = booleanPreferencesKey("enableHapticFeedback")
val UseSystemFontKey = booleanPreferencesKey("useSystemFont")
val FontPreferenceKey = stringPreferencesKey("fontPreference")
val CustomFontUriKey = stringPreferencesKey("customFontUri")
val CustomFontNameKey = stringPreferencesKey("customFontName")
val DefaultOpenTabKey = stringPreferencesKey("defaultOpenTab")
val GridItemsSizeKey = stringPreferencesKey("gridItemSize")
val SliderStyleKey = stringPreferencesKey("sliderStyle")
val SwipeToSongKey = booleanPreferencesKey("SwipeToSong")
val PlayerDesignStyleKey = stringPreferencesKey("playerDesignStyle")
/** How the library tab presents itself: the fork's stock layout or the Apple Music take. */
val LibraryStyleKey = stringPreferencesKey("libraryStyle")
/**
 * Whether the Apple Music player style plays moving artwork — a Spotify Canvas loop, or the music
 * video when the song has one — in place of the still cover. Default true: that is how the style
 * has always behaved, and the setting exists to be able to turn it off, not to change the default.
 */
val AppleMusicAnimatedArtworkKey = booleanPreferencesKey("appleMusicAnimatedArtwork")
/**
 * Whether the Apple Music player style drifts and zooms its blurred artwork backdrop while lyrics
 * are open. Default true: that is how the style has always behaved, and the setting exists to be
 * able to turn it off, not to change the default.
 */
val AppleMusicBackdropWanderKey = booleanPreferencesKey("appleMusicBackdropWander")
/**
 * Whether the Apple Music player style shrinks the still cover slightly while paused. Default
 * true: that is how the style has always behaved, and the setting exists to be able to turn it
 * off, not to change the default.
 */
val AppleMusicPauseScaleKey = booleanPreferencesKey("appleMusicPauseScale")
/**
 * Whether the Apple Music player style scrolls overlong titles/artists as a marquee. Default
 * true: that is how the style has always behaved, and the setting exists to be able to turn it
 * off, not to change the default.
 */
val AppleMusicTitleMarqueeKey = booleanPreferencesKey("appleMusicTitleMarquee")
/**
 * Whether the Apple Music player style's seek bar and sliders swell while dragged. Default true:
 * that is how the style has always behaved, and the setting exists to be able to turn it off,
 * not to change the default.
 */
val AppleMusicSeekGrowKey = booleanPreferencesKey("appleMusicSeekGrow")
val ShowPlayerVolumeBarKey = booleanPreferencesKey("showPlayerVolumeBar")
val HidePlayerThumbnailKey = booleanPreferencesKey("hidePlayerThumbnail")
val ArchiveTuneCanvasKey = booleanPreferencesKey("archiveTuneCanvas")
val SpotifyCanvasKey = booleanPreferencesKey("spotifyCanvas")
/**
 * Whether animated artwork (a Canvas loop or a music video) gives way to the still cover while the device is on
 * cellular data. Default off: moving artwork plays on mobile data unless this is turned on.
 */
val DisableAnimatedArtworkOnMobileDataKey = booleanPreferencesKey("disableAnimatedArtworkOnMobileData")
/** Which provider the Canvas playback layer resolves artwork from. */
val CanvasSourceKey = stringPreferencesKey("canvasSource")
/** Whether Canvas lookups and downloads wait for an unmetered Wi-Fi connection. */
val CanvasWifiOnlyKey = booleanPreferencesKey("canvasWifiOnly")
/** Whether an album page plays the album's looping motion artwork behind its header. */
val AlbumCanvasEnabledKey = booleanPreferencesKey("albumCanvasEnabled")
/**
 * Newline-separated list of extra Spotify Canvas resolver endpoints, tried in order after
 * Spotify's own Canvas endpoint. Empty (the default) means only the built-in resolver is
 * used. See [moe.rukamori.archivetune.utils.CanvasResolverEndpoints] for why this is user
 * supplied rather than a shipped list.
 */
val CanvasResolverEndpointsKey = stringPreferencesKey("canvasResolverEndpoints")
/** Android Auto: content, metered-data and playback-control preferences shown while driving. */
val AndroidAutoOnlineRecommendationsKey = booleanPreferencesKey("androidAutoOnlineRecommendations")
val AndroidAutoOnlineVoiceSearchKey = booleanPreferencesKey("androidAutoOnlineVoiceSearch")
val AndroidAutoLocalSongsKey = booleanPreferencesKey("androidAutoLocalSongs")
val AndroidAutoMeteredPlaybackKey = booleanPreferencesKey("androidAutoMeteredPlayback")
val AndroidAutoMeteredArtworkKey = booleanPreferencesKey("androidAutoMeteredArtwork")
val AndroidAutoPrimaryActionKey = stringPreferencesKey("androidAutoPrimaryAction")
val AndroidAutoSecondaryActionKey = stringPreferencesKey("androidAutoSecondaryAction")
val ThumbnailCornerRadiusKey = floatPreferencesKey("thumbnailCornerRadius")
val CropThumbnailToSquareKey = booleanPreferencesKey("cropThumbnailToSquare")
val AodThumbnailShapeKey = stringPreferencesKey("aodThumbnailShape")
val AodThumbnailSizeKey = floatPreferencesKey("aodThumbnailSize")
val AodThumbnailShapeRotationKey = intPreferencesKey("aodThumbnailShapeRotation")
val AodShowThumbnailKey = booleanPreferencesKey("aodShowThumbnail")
val AodShowArtistKey = booleanPreferencesKey("aodShowArtist")
val AodShowAlbumKey = booleanPreferencesKey("aodShowAlbum")
val AodShowProgressKey = booleanPreferencesKey("aodShowProgress")
val AodShowTimeLabelsKey = booleanPreferencesKey("aodShowTimeLabels")
val AodShowControlsKey = booleanPreferencesKey("aodShowControls")
val AodShowExitButtonKey = booleanPreferencesKey("aodShowExitButton")
val AodArtworkGlowKey = booleanPreferencesKey("aodArtworkGlow")
val AodBackgroundStyleKey = stringPreferencesKey("aodBackgroundStyle")
val AodAccentStyleKey = stringPreferencesKey("aodAccentStyle")
val AodContentPositionKey = stringPreferencesKey("aodContentPosition")
val AodTextAlignmentKey = stringPreferencesKey("aodTextAlignment")
val AodControlStyleKey = stringPreferencesKey("aodControlStyle")
val AodControlSizeKey = floatPreferencesKey("aodControlSize")
/**
 * Slider style for the AOD progress bar. Reuses the [SliderStyle] enum so the
 * same five styles (Standard / Wavy / Thick / Circular / Simple) are available
 * as for the main player. Stored separately from [SliderStyleKey] so users can
 * pick a different style for AOD without affecting the main player.
 */
val AodSliderStyleKey = stringPreferencesKey("aodSliderStyle")
val AodHorizontalPaddingKey = floatPreferencesKey("aodHorizontalPadding")
val AodVerticalSpacingKey = floatPreferencesKey("aodVerticalSpacing")
val AodTitleMaxLinesKey = intPreferencesKey("aodTitleMaxLines")
val AodAmbientIntensityKey = floatPreferencesKey("aodAmbientIntensity")
// Show a compact, dimmed lyrics line on the AOD screen while music plays.
val AodShowLyricsKey = booleanPreferencesKey("aodShowLyrics")
// When > 0, automatically enter AOD mode after this many seconds of the player sheet being
// collapsed (i.e. the user is no longer actively interacting with the player). 0 disables.
val AodAutoTimerSecondsKey = intPreferencesKey("aodAutoTimerSeconds")
// When true, AOD mode auto-triggers when the screen is about to turn off due to inactivity
// (driven via the system lock intent). Disabled by default to avoid surprises.
val AodAutoOnScreenDimKey = booleanPreferencesKey("aodAutoOnScreenDim")
// Experimental native Musixmatch provider (token.get + macro.subtitles + richsync→TTML).
// Off by default; toggle surfaces in Lyrics settings under "Experimental".
val EnableMusixmatchExperimentalKey = booleanPreferencesKey("enableMusixmatchExperimental")
val SeekExtraSeconds = booleanPreferencesKey("seekExtraSeconds")
val DisableBlurKey = booleanPreferencesKey("disableBlur")
val BlurRadiusKey = floatPreferencesKey("blurRadius")
// Backdrop blur for detail pages
val BackdropEnabledKey = booleanPreferencesKey("backdropEnabled")
val BackdropBlurAmountKey = intPreferencesKey("backdropBlurAmount")
val MiniPlayerLastAnchorKey = intPreferencesKey("miniPlayerLastAnchor")
val MiniPlayerBackgroundStyleKey = stringPreferencesKey("miniPlayerBackgroundStyle")
val CompactMiniPlayerKey = booleanPreferencesKey("compactMiniPlayer")
// Master toggle: when off, all Liquid Glass surfaces (header pills on detail
// pages, the Liquid Glass mini player background, and the Liquid Glass nav bar
// style) are unavailable / hidden / forced to their non-glass fallback.
//
// Sub-toggles:
//  - LiquidGlassNavBarEnabledKey: opt-in Liquid Glass style for the bottom
//    navigation bar (uses kyant-backdrop LayerBackdrop). Independent of the
//    existing frostedBlur / tintFrostedBlur booleans, which use a different
//    RenderEffect-based recipe.
val LiquidGlassEnabledKey = booleanPreferencesKey("liquidGlassEnabled")
val LiquidGlassNavBarEnabledKey = booleanPreferencesKey("liquidGlassNavBarEnabled")
val AppLanguageKey = stringPreferencesKey("appLanguage")
val ContentLanguageKey = stringPreferencesKey("contentLanguage")
val ContentCountryKey = stringPreferencesKey("contentCountry")
// Region spoofer for YouTube Music — overrides `YouTube.locale.gl` independently of
// `ContentCountryKey`. Set from Internet Settings so users can keep their content
// language/culture but pretend to YouTube Music that they are connecting from a
// different country (e.g. to play region-locked songs). Applied AFTER
// `ContentCountryKey` in `App.initializeDeferredAsync()` so the more specific
// Internet/region setting wins. `SYSTEM_DEFAULT` means "fall back to device locale
// or `ContentCountryKey`".
val YouTubeMusicRegionKey = stringPreferencesKey("youtubeMusicRegion")
val PlaylistSuggestionSourceKey = stringPreferencesKey("playlistSuggestionSource")
val EnableKugouKey = booleanPreferencesKey("enableKugou")
val EnableLrcLibKey = booleanPreferencesKey("enableLrclib")
val EnableBetterLyricsKey = booleanPreferencesKey("enableBetterLyrics")
val EnableBetterLyricsPortatoKey = booleanPreferencesKey("enableBetterLyricsPortato")
val EnableYouLyPlusLyricsKey = booleanPreferencesKey("enableYouLyPlusLyrics")
val EnableSimpMusicLyricsKey = booleanPreferencesKey("enableSimpMusicLyrics")
val EnableMegalobizLyricsKey = booleanPreferencesKey("enableMegalobizLyrics")
val EnablePaxsenixLyricsKey = booleanPreferencesKey("enablePaxsenixLyrics")
val EnablePaxsenixAppleMusicLyricsKey = booleanPreferencesKey("enablePaxsenixAppleMusicLyrics")
val EnablePaxsenixNeteaseLyricsKey = booleanPreferencesKey("enablePaxsenixNeteaseLyrics")
val EnablePaxsenixSpotifyLyricsKey = booleanPreferencesKey("enablePaxsenixSpotifyLyrics")
val EnablePaxsenixMusixmatchLyricsKey = booleanPreferencesKey("enablePaxsenixMusixmatchLyrics")
val EnablePaxsenixYouTubeLyricsKey = booleanPreferencesKey("enablePaxsenixYouTubeLyrics")
// User-configurable PaxSenix API key. When blank, PaxsenixLyrics falls
// back to the default (no-auth) behavior. When set, it's sent as an
// Authorization: Bearer header on every Paxsenix API request.
val PaxsenixApiKeyKey = stringPreferencesKey("paxsenixApiKey")
// User-configurable PaxSenix endpoint override. When blank, the default
// endpoint is used. When set, all Paxsenix API requests go to this URL.
val PaxsenixEndpointKey = stringPreferencesKey("paxsenixEndpoint")
val EnableUnisonLyricsKey = booleanPreferencesKey("enableUnisonLyrics")
// The bundled Bundled lyrics server (the service the Metrolist developers run). On by default, like
// Metrolist; its endpoint comes only from the build (BuildConfig.BUNDLED_*) and is never a preference.
val EnableBundledLyricsKey = booleanPreferencesKey("enableBundledLyrics")
val EnableTidalLyricsKey = booleanPreferencesKey("enableTidalLyrics")
val EnableDeezerLyricsKey = booleanPreferencesKey("enableDeezerLyrics")
// When ON, lyrics lookup first queries the four word-sync-capable providers
// (BetterLyrics, BetterLyrics Portato, YouLyPlus, Unison) in parallel and uses
// whichever returns word-synced lyrics (QRC/YRC/TTML with word timings). If none
// of those four return word-synced lyrics, the lookup falls back to the normal
// priority flow across all enabled providers.
val PrioritizeWordSyncedLyricsKey = booleanPreferencesKey("prioritizeWordSyncedLyrics")
val HideExplicitKey = booleanPreferencesKey("hideExplicit")
val HideVideoKey = booleanPreferencesKey("hideVideo")
// When ON, music videos render an inline video surface in the player. Default OFF so songs
// play as plain audio (album artwork shown, no video stream is loaded) unless the user opts in.
// Distinct from HideVideoKey which filters videos out of the library/queue entirely.
val EnableVideoPlaybackKey = booleanPreferencesKey("enableVideoPlayback")
// When ON (default OFF), leaving the app while a music video is playing enters Picture-in-
// Picture mode so the video keeps playing in a small floating window. Requires video playback
// to be enabled (EnableVideoPlaybackKey) — if video playback is off, this setting has no
// effect because there is no video surface to float.
val EnablePipModeKey = booleanPreferencesKey("enablePipMode")
val AllowAgeRestrictedKey = booleanPreferencesKey("allowAgeRestricted")
val DownloadSourceKey = stringPreferencesKey("downloadSource")
// CSV of DownloadSource names in the user's chosen priority order. Empty/null falls back to
// DownloadSourceConfig.DEFAULT_ORDER (Qobuz, Tidal, Deezer, YouTube Music). Consumed by
// DownloadUtil to drive the per-song download source chain — replacing the old single-pick
// DownloadSourceKey. The old key is kept for migration / backup compatibility.
val DownloadSourceOrderKey = stringPreferencesKey("downloadSourceOrder")
val AiContentFilterEnabledKey = booleanPreferencesKey("aiContentFilterEnabled")
val AiContentFilterIncludeModerateKey = booleanPreferencesKey("aiContentFilterIncludeModerate")
val AiContentFilterLastUpdatedKey = longPreferencesKey("aiContentFilterLastUpdated")
val AiMixLastGeneratedAtKey = longPreferencesKey("aiMixLastGeneratedAt")
val ProxyEnabledKey = booleanPreferencesKey("proxyEnabled")
val ProxyHostKey = stringPreferencesKey("proxyHost")
val ProxyPortKey = intPreferencesKey("proxyPort")
val ProxyUsernameKey = stringPreferencesKey("proxyUsername")
val ProxyPasswordKey = stringPreferencesKey("proxyPassword")
val ProxyTypeKey = stringPreferencesKey("proxyType")
val EnableDnsOverHttpsKey = booleanPreferencesKey("enableDnsOverHttps")
val DnsOverHttpsProviderKey = stringPreferencesKey("dnsOverHttpsProvider")
val StreamBypassProxyKey = booleanPreferencesKey("streamBypassProxy")
val IpRotationEnabledKey = booleanPreferencesKey("ipRotationEnabled")
val YtmSyncKey = booleanPreferencesKey("ytmSync")
val ForceSyncOnAccountSwitchKey = booleanPreferencesKey("forceSyncOnAccountSwitch")
val SelectedYtmPlaylistsKey = stringPreferencesKey("ytm_selected_playlists")
val LocalSongsMinDurationSecondsKey = intPreferencesKey("local_songs_min_duration_seconds")
val LocalSongsIncludedFoldersKey = stringSetPreferencesKey("local_songs_included_folders")
val LocalSongsExcludedFoldersKey = stringSetPreferencesKey("local_songs_excluded_folders")
val LocalSongsSortTypeKey = stringPreferencesKey("local_songs_sort_type")
val LocalSongsSortDescendingKey = booleanPreferencesKey("local_songs_sort_descending")
// The extraction version the local library was last fully read with. A scan whose stored value is
// below LocalSongScanner's current one re-reads every file once instead of trusting its
// unchanged-file skip, so a library read by an older build still gains what that build never read.
val LocalScanExtractionVersionKey = intPreferencesKey("local_scan_extraction_version")
val ListenBrainzEnabledKey = booleanPreferencesKey("listenbrainz_enabled")
val ListenBrainzTokenKey = stringPreferencesKey("listenbrainz_token")
val AiProviderKey = stringPreferencesKey("ai_provider")
val AiCustomEndpointKey = stringPreferencesKey("ai_custom_endpoint")
val AiApiKeyKey = stringPreferencesKey("ai_api_key")
val AiApiValidationStatusKey = stringPreferencesKey("ai_api_validation_status")
val AiSelectedModelKey = stringPreferencesKey("ai_selected_model")
val AiCustomModelKey = stringPreferencesKey("ai_custom_model")
// --- DeepL / OpenRouter / Mistral translation providers (ported from vivi-music) ---
// DeepL: API key with `:fx` suffix routes through api-free.deepl.com; otherwise api.deepl.com.
val DeeplApiKeyKey = stringPreferencesKey("deeplApiKey")
// "default" / "more" / "less" — controls DeepL's formality parameter.
val DeeplFormalityKey = stringPreferencesKey("deeplFormality")
// OpenRouter: chat-completions style translation. User can override base URL and model.
val OpenRouterApiKeyKey = stringPreferencesKey("openRouterApiKey")
val OpenRouterBaseUrlKey = stringPreferencesKey("openRouterBaseUrl")
val OpenRouterModelKey = stringPreferencesKey("openRouterModel")
// Translation mode for OpenRouter: "translate" (full translation) or "romanize" (transliteration).
val TranslateModeKey = stringPreferencesKey("translateMode")
// Target language code for translation (e.g. "en", "zh-CN", "ja").
val TranslateLanguageKey = stringPreferencesKey("translateLanguage")
// Optional source language hint (null = auto-detect).
val TranslateSourceLanguageKey = stringPreferencesKey("translateSourceLanguage")
// Hides the AI-generated "Top mixes" section in the library Mix tab (and stops auto-generation).
val HideAiMixKey = booleanPreferencesKey("hide_ai_mix")
// Whether the user pressed the pause button on the Debug Logs screen. Persisted so the pause
// state survives screen navigation AND process restarts — previously the flag lived in a
// HiltViewModel's MutableStateFlow, which was destroyed on screen exit, so the user's pause
// was silently dropped every time they navigated away.
val LogcatPausedKey = booleanPreferencesKey("logcatPaused")
// When on, any foreign-language lyrics that have an AI provider configured will be translated
// to the user's preferred language automatically on lyrics load — no manual tap through the
// translate dialog required. The "Translation saved" toast is also suppressed in this mode so
// background translations don't fire a notification every time a new song starts.
val AutoTranslateLyricsKey = booleanPreferencesKey("autoTranslateLyrics")
// Set of uppercase language codes (e.g. "JAPANESE", "KOREAN", "CHINESE_SIMPLIFIED") that should NOT
// be auto-translated even when [AutoTranslateLyricsKey] is on. The user picks them via the
// multi-select dialog in AiIntegrationSettings. Codes match `TranslatorLang.code` in
// assets/translator_languages.json — note that there is no plain "CHINESE" there, which is why the
// comparison goes through `LyricsUtils.matchesExcludedLanguage` rather than a direct set lookup.
val AutoTranslateExcludedLanguagesKey = stringSetPreferencesKey("autoTranslateExcludedLanguages")
// ── AI romanisation ──
// Master switch. When on, the configured AI provider supplies the romanisation shown above each
// lyric line, and the built-in engines (Kuromoji for Japanese, the hand-written Korean/Hindi tables,
// ICU for everything else) stop running entirely — see `LyricsRomanizationPreferences.aiHandled`.
// Two engines at once would mix romanisation schemes within one song, and the whole reason to reach
// for a model is that its output is better than the tables'.
val AiRomanizeLyricsKey = booleanPreferencesKey("aiRomanizeLyrics")
// Fetch the romanisation as soon as lyrics are shown, rather than waiting for the user to ask via
// Lyrics menu → Romanise with AI. Separate from [AiRomanizeLyricsKey] because these are billed
// network calls: switching the feature on should not commit the user to one request per track.
val AutoAiRomanizeLyricsKey = booleanPreferencesKey("autoAiRomanizeLyrics")
// Uppercase language codes (e.g. "JAPANESE", "KOREAN") to leave alone even when AI romanisation is
// on — for scripts the user already reads. Same code space and same multi-select dialog as
// [AutoTranslateExcludedLanguagesKey]; codes match `TranslatorLang.code` in
// assets/translator_languages.json.
val AiRomanizeExcludedLanguagesKey = stringSetPreferencesKey("aiRomanizeExcludedLanguages")
// Set by the "Never show again" pill on the startup update popup. Stores the
// "<versionName>|<versionCode>" of the version the user suppressed the popup for, so we
// can detect when they've upgraded and re-enable the popup for the next new release.
// Empty string means "not suppressed".
val NeverShowUpdatePopupKey = stringPreferencesKey("neverShowUpdatePopupVersion")
// The release the update sheet was last shown for. Keyed on the OFFERED release rather than the
// installed one, so dismissing the sheet settles it for that release instead of letting it return
// on the next launch. A genuinely newer release still gets one showing.
val LastShownUpdatePopupVersionKey = stringPreferencesKey("lastShownUpdatePopupVersion")
// Off: the update sheet appears once per release. On: it reappears every launch while an update is
// outstanding, for readers who want the reminder rather than a single notice.
val PersistentUpdatePopupKey = booleanPreferencesKey("persistentUpdatePopup")
val HideLikedSongsCardKey = booleanPreferencesKey("hide_liked_songs_card")
val HideOfflineCardKey = booleanPreferencesKey("hide_offline_card")
val HideCachedCardKey = booleanPreferencesKey("hide_cached_card")
val HideLocalFilesCardKey = booleanPreferencesKey("hide_local_files_card")
val HideTop50CardKey = booleanPreferencesKey("hide_top50_card")
// Last.fm scrobbling
val LastFMSessionKey = stringPreferencesKey("lastfmSession")
val LastFMUsernameKey = stringPreferencesKey("lastfmUsername")
val LastFMProviderKey = stringPreferencesKey("lastfmProvider")
val LastFMCustomEndpointKey = stringPreferencesKey("lastfmCustomEndpoint")
val LastFMApiKeyOverrideKey = stringPreferencesKey("lastfmApiKeyOverride")
val LastFMSecretOverrideKey = stringPreferencesKey("lastfmSecretOverride")
val LibreFMApiKeyOverrideKey = stringPreferencesKey("librefmApiKeyOverride")
val LibreFMSecretOverrideKey = stringPreferencesKey("librefmSecretOverride")
val CustomScrobbleApiKeyOverrideKey = stringPreferencesKey("customScrobbleApiKeyOverride")
val CustomScrobbleSecretOverrideKey = stringPreferencesKey("customScrobbleSecretOverride")
val LastFMCredentialsMigratedKey = booleanPreferencesKey("lastfmCredentialsMigrated")
val EnableLastFMScrobblingKey = booleanPreferencesKey("lastfmScrobblingEnable")
val LastFMUseNowPlaying = booleanPreferencesKey("lastfmUseNowPlaying")
val ScrobbleDelayPercentKey = floatPreferencesKey("scrobbleDelayPercent")
val ScrobbleMinSongDurationKey = intPreferencesKey("scrobbleMinSongDuration")
val ScrobbleDelaySecondsKey = intPreferencesKey("scrobbleDelaySeconds")
val AudioQualityKey = stringPreferencesKey("audioQuality")
val NetworkMeteredKey = booleanPreferencesKey("networkMetered")
// Preload's own data-saver flag. The preload settings repo reads/writes only
// this key, so enabling preload never flips the player-wide metered flag.
val PreloadLowDataModeKey = booleanPreferencesKey("preloadLowDataMode")
val PreloadNextSongKey = booleanPreferencesKey("preloadNextSong")
val PlayerStreamClientKey = stringPreferencesKey("playerStreamClient")
val AutoChoosePlaybackClientKey = booleanPreferencesKey("autoChoosePlaybackClient")
val PersistentQueueKey = booleanPreferencesKey("persistentQueue")
val PermanentShuffleKey = booleanPreferencesKey("permanentShuffle")
val SkipSilenceKey = booleanPreferencesKey("skipSilence")
val AudioNormalizationKey = booleanPreferencesKey("audioNormalization")
val AudioOffload = booleanPreferencesKey("audioOffload")
// Android 14+ bit-perfect output to USB DACs. Mutually exclusive with offload, crossfade and
// automix (they need the processing chain or a second player); read once per player build.
val BitPerfectUsbOutputKey = booleanPreferencesKey("bitPerfectUsbOutput")
val CrossfadeEnabledKey = booleanPreferencesKey("crossfadeEnabled")
val CrossfadeDurationKey = floatPreferencesKey("crossfadeDuration")
val CrossfadeGaplessKey = booleanPreferencesKey("crossfadeGapless")
val AutomixEnabledKey = booleanPreferencesKey("automixEnabled")
val AutomixPerformanceModeKey = stringPreferencesKey("automixPerformanceMode")
val AutoLoadMoreKey = booleanPreferencesKey("autoLoadMore")
val AutoDownloadOnLikeKey = booleanPreferencesKey("autoDownloadOnLike")
val AutoSkipNextOnErrorKey = booleanPreferencesKey("autoSkipNextOnError")
val PauseOnDeviceMuteKey = booleanPreferencesKey("pauseOnDeviceMute")
val DeviceMutePlaybackRecoveryVolumeKey = intPreferencesKey("deviceMutePlaybackRecoveryVolume")
val AutoStartOnBluetoothKey = booleanPreferencesKey("autoStartOnBluetooth")
val StopMusicOnTaskClearKey = booleanPreferencesKey("stopMusicOnTaskClear")
val WakelockKey = booleanPreferencesKey("wakelock")
val ArtistSeparatorsKey = stringPreferencesKey("artistSeparators")
val ExternalDownloaderEnabledKey = booleanPreferencesKey("externalDownloaderEnabled")
val ExternalDownloaderPackageKey = stringPreferencesKey("externalDownloaderPackage")
val PlaylistTagsFilterKey = stringPreferencesKey("playlistTagsFilter")
val ShowHomeCategoryChipsKey = booleanPreferencesKey("showHomeCategoryChips")
val ShowTagsInLibraryKey = booleanPreferencesKey("showTagsInLibrary")
/**
 * When `true`, the Home feed collapses to a focused subset:
 *   - Jump-back-in hero (always rendered, regardless of this toggle)
 *   - Recently Played
 *   - Keep Listening
 *   - Live Performances (the "Live performance"-titled remote shelf)
 *
 * All other home sections (category chips, remote/local quick picks,
 * speed dial, account playlists, forgotten favorites, similar
 * recommendations, and the rest of the remote homePage sections such
 * as "Fresh finds" / "Old favourites") are hidden.
 *
 * Default is `false` so a fresh install shows the full home feed
 * (matches upstream rukamori/ArchiveTune).
 */
val MinimalHomeModeKey = booleanPreferencesKey("minimalHomeMode")
val EqualizerEnabledKey = booleanPreferencesKey("equalizerEnabled")
val EqualizerControlModeKey = stringPreferencesKey("equalizerControlMode")
val EqualizerBandLevelsMbKey = stringPreferencesKey("equalizerBandLevelsMb")
val EqualizerAutoHeadroomEnabledKey = booleanPreferencesKey("equalizerAutoHeadroomEnabled")
val EqualizerOutputGainEnabledKey = booleanPreferencesKey("equalizerOutputGainEnabled")
val EqualizerOutputGainMbKey = intPreferencesKey("equalizerOutputGainMb")
val EqualizerBassBoostEnabledKey = booleanPreferencesKey("equalizerBassBoostEnabled")
val EqualizerBassBoostStrengthKey = intPreferencesKey("equalizerBassBoostStrength")
val EqualizerVirtualizerEnabledKey = booleanPreferencesKey("equalizerVirtualizerEnabled")
val EqualizerVirtualizerStrengthKey = intPreferencesKey("equalizerVirtualizerStrength")
val EqualizerSelectedProfileIdKey = stringPreferencesKey("equalizerSelectedProfileId")
val EqualizerCustomProfilesJsonKey = stringPreferencesKey("equalizerCustomProfilesJson")
val MaxImageCacheSizeKey = intPreferencesKey("maxImageCacheSize")
val SmartTrimmerKey = booleanPreferencesKey("smartTrimmer")
val MaxSongCacheSizeKey = intPreferencesKey("maxSongCacheSize")
val MaxCanvasCacheSizeKey = intPreferencesKey("maxCanvasCacheSize")
val StorageFolderIdKey = stringPreferencesKey("storageFolderId")
val StorageFolderTreeUriKey = stringPreferencesKey("storageFolderTreeUri")
val StorageFolderPathKey = stringPreferencesKey("storageFolderPath")
val StorageFolderDisplayNameKey = stringPreferencesKey("storageFolderDisplayName")
val PauseListenHistoryKey = booleanPreferencesKey("pauseListenHistory")
val PauseSearchHistoryKey = booleanPreferencesKey("pauseSearchHistory")
// When true (default), plays are reported to the logged-in YouTube account's listen history even
// when the audio was streamed from another source (Tidal/Qobuz). History is keyed off the YouTube
// video id, so it stays source-independent; this toggle lets the user opt out of the remote report
// without affecting the local play-count/history DB (governed by PauseListenHistoryKey).
val SyncPlaybackToYouTubeHistoryKey = booleanPreferencesKey("syncPlaybackToYouTubeHistory")
val DisableScreenshotKey = booleanPreferencesKey("disableScreenshot")
// Last.fm dashboard: prefer YouTube hq720 thumbnails over the Last.fm image
// array. When enabled, the dashboard skips bestArtwork(track.image) (which
// can return non-square / brown-matted images from Last.fm's catalogue) and
// goes straight to resolveCatalogueCover, which starts with YouTube hq720
// (clean 16:9, no baked-in bars). Useful for users whose Last.fm catalogue
// has many low-quality or padded artwork images.
val LastFmPreferYtThumbnailsKey = booleanPreferencesKey("lastfmPreferYtThumbnails")
val DiscordTokenKey = stringPreferencesKey("discordToken")
val DiscordRefreshTokenKey = stringPreferencesKey("discordRefreshToken")
val DiscordTokenExpiresAtKey = longPreferencesKey("discordTokenExpiresAt")
val DiscordUsernameKey = stringPreferencesKey("discordUsername")
val DiscordNameKey = stringPreferencesKey("discordName")
val DiscordAvatarUrlKey = stringPreferencesKey("discordAvatarUrl")
val EnableDiscordRPCKey = booleanPreferencesKey("discordRPCEnable")
// Discord activity customization keys
val DiscordActivityNameKey = stringPreferencesKey("discordActivityName")
val DiscordActivityDetailsKey = stringPreferencesKey("discordActivityDetails")
val DiscordActivityStateKey = stringPreferencesKey("discordActivityState")
// Custom button labels and urls for Discord activity buttons
val DiscordActivityButton1LabelKey = stringPreferencesKey("discordActivityButton1Label")
val DiscordActivityButton1UrlSourceKey = stringPreferencesKey("discordActivityButton1UrlSource")
val DiscordActivityButton1CustomUrlKey = stringPreferencesKey("discordActivityButton1CustomUrl")
val DiscordActivityButton2LabelKey = stringPreferencesKey("discordActivityButton2Label")
val DiscordActivityButton2UrlSourceKey = stringPreferencesKey("discordActivityButton2UrlSource")
val DiscordActivityButton2CustomUrlKey = stringPreferencesKey("discordActivityButton2CustomUrl")
val DiscordActivityButton1EnabledKey = booleanPreferencesKey("discordActivityButton1Enabled")
val DiscordActivityButton2EnabledKey = booleanPreferencesKey("discordActivityButton2Enabled")
val DiscordShowWhenPausedKey = booleanPreferencesKey("discordShowWhenPaused")
// Activity type for Discord presence (PLAYING, STREAMING, LISTENING, WATCHING, COMPETING)
val DiscordActivityTypeKey = stringPreferencesKey("discordActivityType")
val DiscordPresenceStatusKey = stringPreferencesKey("discordPresenceStatus") // "ONLINE", "IDLE", "DND", "INVISIBLE"
// Discord image selection keys
// Values for type keys: "thumbnail", "artist", "appicon", "custom"
val DiscordLargeImageTypeKey = stringPreferencesKey("discordLargeImageType")
val DiscordLargeTextSourceKey = stringPreferencesKey("discordLargeTextSource")
val DiscordLargeTextCustomKey = stringPreferencesKey("discordLargeTextCustom")
val DiscordLargeImageCustomUrlKey = stringPreferencesKey("discordLargeImageCustomUrl")
val DiscordSmallImageTypeKey = stringPreferencesKey("discordSmallImageType")
val DiscordSmallImageCustomUrlKey = stringPreferencesKey("discordSmallImageCustomUrl")
// Activity platform (discord client platform) selection
val DiscordActivityPlatformKey = stringPreferencesKey("discordActivityPlatform")
val TranslatorContextsKey = stringPreferencesKey("translatorContexts")
val TranslatorTargetLangKey = stringPreferencesKey("translatorTargetLang")
val EnableTranslatorKey = booleanPreferencesKey("enableTranslator")
val ChipSortTypeKey = stringPreferencesKey("chipSortType")
val SongSortTypeKey = stringPreferencesKey("songSortType")
val SongSortDescendingKey = booleanPreferencesKey("songSortDescending")
val AutoPlaylistSongSortTypeKey = stringPreferencesKey("autoPlaylistSongSortType")
val AutoPlaylistSongSortDescendingKey = booleanPreferencesKey("autoPlaylistSongSortDescending")
val ArtistSortTypeKey = stringPreferencesKey("artistSortType")
val ArtistSortDescendingKey = booleanPreferencesKey("artistSortDescending")
val AlbumSortTypeKey = stringPreferencesKey("albumSortType")
val AlbumSortDescendingKey = booleanPreferencesKey("albumSortDescending")
val PlaylistSortTypeKey = stringPreferencesKey("playlistSortType")
val PlaylistSortDescendingKey = booleanPreferencesKey("playlistSortDescending")
val ArtistSongSortTypeKey = stringPreferencesKey("artistSongSortType")
val ArtistSongSortDescendingKey = booleanPreferencesKey("artistSongSortDescending")
val PlaylistPickerSortTypeKey = stringPreferencesKey("playlistPickerSortType")
val SongFilterKey = stringPreferencesKey("songFilter")
val ArtistFilterKey = stringPreferencesKey("artistFilter")
val AlbumFilterKey = stringPreferencesKey("albumFilter")
val PlaylistViewTypeKey = stringPreferencesKey("playlistViewType")
val PlaylistEditLockKey = booleanPreferencesKey("playlistEditLock")
val QuickPicksKey = stringPreferencesKey("discover")
val NewsLastReadTimestampKey = longPreferencesKey("news_last_read_timestamp")
val SpeedDialSongIdsKey = stringPreferencesKey("speedDialSongIds")
val LyricsProviderOrderKey = stringPreferencesKey("lyricsProviderOrder")
val ArtworkProviderOrderKey = stringPreferencesKey("artworkProviderOrder")
val QueueEditLockKey = booleanPreferencesKey("queueEditLock")
// Whether an upward swipe on the player opens the queue. Default on (existing behaviour); off leaves
// the queue button as the only way in, for users who trigger it by accident while seeking (#171).
val SwipeUpToOpenQueueKey = booleanPreferencesKey("swipeUpToOpenQueue")
// "Play next" and "Add to queue" append to a run of user-added songs right after the current one,
// ahead of radio/autoplay songs, so A then B then C play in that order (#172). Off restores the
// old behaviour (Play next jumps to position 2, Add to queue goes to the very end).
val QueueAddAfterManualKey = booleanPreferencesKey("queueAddAfterManual")
// Player HUD: show the resolved codec/bitrate line. Bound by the Developer Options toggle
// (DebugSettings) and read by the player + queue overlays — a shared constant keeps the three
// call sites from drifting on the raw string.
val ShowCodecOnPlayerKey = booleanPreferencesKey("show_codec_on_player")
val QuickPicksDisplayModeKey = stringPreferencesKey("quickPicksDisplayMode")
val HomeScreenStyleKey = stringPreferencesKey("homeScreenStyle")
val HomeSourceKey = stringPreferencesKey("homeSource")
/**
 * The Home pages the user has made available, as a CSV of [HomeSource] names. Empty means "not
 * configured": the switcher then offers YouTube alone (plus Spotify once a session exists, and QQ
 * Music once an account is signed in), which is exactly the behaviour this key was introduced to
 * preserve. YouTube is always part of the resolved set — it is the app's own home and the only one
 * that needs no session.
 */
val ActiveHomeSourcesKey = stringPreferencesKey("activeHomeSources")
