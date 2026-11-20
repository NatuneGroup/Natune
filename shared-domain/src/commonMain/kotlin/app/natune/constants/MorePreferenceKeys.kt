package app.natune.constants

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey

// The rest of the persisted preference keys, kept apart from PreferenceKeys.kt to stay within the file-size budget.

/**
 * Whether the home's quick picks follow the active catalogue (Spotify/QQ) instead of YouTube.
 * Off by default, which is what 4nx3b's home does: its shelves come from YouTube until the reader
 * turns this on.
 */
val HomeCatalogueSwitchKey = booleanPreferencesKey("homeCatalogueSwitch")
val SpotifyHistorySyncEnabledKey = booleanPreferencesKey("spotifyHistorySyncEnabled")
val NavigationBarStyleKey = stringPreferencesKey("navigationBarStyle")
val NavigationBarCompactBehaviorKey = stringPreferencesKey("navigationBarCompactBehavior")
val CompactBottomPillsKey = booleanPreferencesKey("compactBottomPills")
// The round library and search buttons that replace the tab bar while it is compacted. Off follows
// the Liquid Glass master switch (glass when it is on); on keeps them in solid theme colours.
val CompactControlsStockColorKey = booleanPreferencesKey("compactControlsStockColor")
val SearchBarPositionKey = stringPreferencesKey("searchBarPosition")
val HideSearchChromeWhileScrollingKey = booleanPreferencesKey("hideSearchChromeWhileScrolling")
// Draws a frosted (blurred app content) backdrop behind the navigation bar. True backdrop blur on
// Android 12+; a translucent surface fallback below that.
val NavigationBarFrostedBlurKey = booleanPreferencesKey("navigationBarFrostedBlur")
// Tinted variant of frosted blur: the same backdrop blur as [NavigationBarFrostedBlurKey], but the
// bar surface is tinted with the accent (primary) color instead of the neutral surface container.
// Mutually exclusive with [NavigationBarFrostedBlurKey] — turning one on turns the other off.
val NavigationBarTintFrostedBlurKey = booleanPreferencesKey("navigationBarTintFrostedBlur")
// Opt-in BitChord pill bar (icons, stretch-squash selection pill, drag-to-switch).
// Mutually exclusive with the frosted, tinted and liquid-glass nav treatments.
val HideNavigationBarLabelsKey = booleanPreferencesKey("hideNavigationBarLabels")
val NavigationBarHideOnScrollKey = booleanPreferencesKey("navigationBarHideOnScroll")
// Advanced tuning knobs for the FLOATING nav bar style (and corner radius for
// DEFAULT). Defaults preserve the pre-existing look.
val NavigationBarWidthKey = floatPreferencesKey("navigationBarWidth")
val NavigationBarHeightKey = floatPreferencesKey("navigationBarHeight")
val NavigationBarOpacityKey = floatPreferencesKey("navigationBarOpacity")
val NavigationBarTransparencyKey = floatPreferencesKey("navigationBarTransparency")
val NavigationBarLabelSpacingKey = floatPreferencesKey("navigationBarLabelSpacing")
val NavigationBarCornerRadiusKey = floatPreferencesKey("navigationBarCornerRadius")
// App-wide scrollbar toggle. When false, all LazyColumn / LazyGrid /
// ScrollState scrollbars in the app are suppressed. (Slider thumb tracks and
// player position bars are not affected — those are not "scrollbars".)
val HideScrollbarKey = booleanPreferencesKey("hideScrollbar")
// Keys for customized background
val PlayerCustomImageUriKey = stringPreferencesKey("playerCustomImageUri")
val PlayerCustomBlurKey = floatPreferencesKey("playerCustomBlur")
val PlayerCustomContrastKey = floatPreferencesKey("playerCustomContrast")
val PlayerCustomBrightnessKey = floatPreferencesKey("playerCustomBrightness")
val LyricsAnimationStyleKey = stringPreferencesKey("lyricsAnimationStyle")
val LyricsTextSizeKey = floatPreferencesKey("lyricsTextSize")
val LyricsLineSpacingKey = floatPreferencesKey("lyricsLineSpacing")
val LyricsLineBlurKey = booleanPreferencesKey("lyricsLineBlur")
val TopSize = stringPreferencesKey("topSize")
val HISTORY_DURATION_LEGACY_FLOAT_KEY = floatPreferencesKey("historyDuration")
val HistoryDuration = intPreferencesKey("historyDuration")
val PlayerButtonsStyleKey = stringPreferencesKey("player_buttons_style")
val PlayerBackgroundStyleKey = stringPreferencesKey("playerBackgroundStyle")
val LyricsBackgroundStyleKey = stringPreferencesKey("lyricsBackgroundStyle")
val ShowLyricsKey = booleanPreferencesKey("showLyrics")
val LyricsTextPositionKey = stringPreferencesKey("lyricsTextPosition")
val LyricsClickKey = booleanPreferencesKey("lyricsClick")
val LyricsScrollKey = booleanPreferencesKey("lyricsScrollKey")
val LyricsRomanizeJapaneseKey = booleanPreferencesKey("lyricsRomanizeJapanese")
val LyricsRomanizeKoreanKey = booleanPreferencesKey("lyricsRomanizeKorean")
val LyricsRomanizeChineseKey = booleanPreferencesKey("lyricsRomanizeChinese")
val LyricsRomanizeHindiKey = booleanPreferencesKey("lyricsRomanizeHindi")
val LyricsRomanizeOtherLanguagesKey = booleanPreferencesKey("lyricsRomanizeOtherLanguages")
val TranslateLyricsKey = booleanPreferencesKey("translateLyrics")
val UseLyricsV2Key = booleanPreferencesKey("useLyricsV2")
val LyricsModeKey = stringPreferencesKey("lyricsMode")
val LyricsV2BounceFactorKey = floatPreferencesKey("lyricsV2BounceFactor")
val LyricsV2GlowFactorKey = floatPreferencesKey("lyricsV2GlowFactor")
val LyricsV2FillTransitionWidthKey = floatPreferencesKey("lyricsV2FillTransitionWidth")
val LyricsV2LrcBounceEnabledKey = booleanPreferencesKey("lyricsV2LrcBounceEnabled")
val LyricsV2AdaptiveScrollSpringKey = booleanPreferencesKey("lyricsV2AdaptiveScrollSpring")
val LyricsV2SoftWordRevealKey = booleanPreferencesKey("lyricsV2SoftWordReveal")
val LyricsV2EmphasizeLastWordKey = booleanPreferencesKey("lyricsV2EmphasizeLastWord")
val LyricsV2InterludeDotsKey = booleanPreferencesKey("lyricsV2InterludeDots")
// Queue lyrics pre-load settings
val PreloadQueueLyricsEnabledKey = booleanPreferencesKey("preload_queue_lyrics_enabled")
val QueueLyricsPreloadCountKey = intPreferencesKey("queue_lyrics_preload_count")
val PlayerVolumeKey = floatPreferencesKey("playerVolume")
val RepeatModeKey = intPreferencesKey("repeatMode")
val SearchSourceKey = stringPreferencesKey("searchSource")
val SwipeThumbnailKey = booleanPreferencesKey("swipeThumbnail")
val SwipeSensitivityKey = floatPreferencesKey("swipeSensitivity")
// Catalog providers are independent from the local-vs-online search scope above. Spotify is a
// metadata/search provider here; that is a different account and a different surface from the
// experimental AudioSourceType.SPOTIFY streaming source, which has its own keys below.
val DefaultMetadataSourceKey = stringPreferencesKey("defaultMetadataSource")
val DefaultSearchSourceKey = stringPreferencesKey("defaultSearchSource")
val VisitorDataKey = stringPreferencesKey("visitorData")
val DataSyncIdKey = stringPreferencesKey("dataSyncId")
val InnerTubeCookieKey = stringPreferencesKey("innerTubeCookie")
/**
 * OAuth2 credentials for the YouTube VR device-code flow, the signed-in path that does not use a
 * cookie. The access token is short-lived (~1h) and is what reaches InnerTube as a Bearer; the
 * refresh token is long-lived and never leaves the app.
 */
val InnerTubeOAuthTokenKey = stringPreferencesKey("innerTubeOAuthToken")
val InnerTubeOAuthRefreshTokenKey = stringPreferencesKey("innerTubeOAuthRefreshToken")
/** Epoch millis at which [InnerTubeOAuthTokenKey] expires, so a refresh happens before a 401. */
val InnerTubeOAuthExpiresAtKey = longPreferencesKey("innerTubeOAuthExpiresAt")
val PoTokenKey = stringPreferencesKey("poToken")
val AccountNameKey = stringPreferencesKey("accountName")
val AccountEmailKey = stringPreferencesKey("accountEmail")
val AccountChannelHandleKey = stringPreferencesKey("accountChannelHandle")
// Avatar that goes with AccountNameKey. Persisted for the same reason the name is: the visible
// account identity has to survive a cold start (including the one the region picker triggers)
// without waiting on, or depending on, a live accountMenu call.
val AccountImageUrlKey = stringPreferencesKey("accountImageUrl")
val SavedAccountsKey = stringPreferencesKey("savedAccounts")
val UseLoginForBrowse = booleanPreferencesKey("useLoginForBrowse")
val SpotifySpDcKey = stringPreferencesKey("spotify_sp_dc")
val SpotifySpKeyKey = stringPreferencesKey("spotify_sp_key")
val SpotifyAccessTokenKey = stringPreferencesKey("spotify_access_token")
val SpotifyAccessTokenExpiresAtKey = longPreferencesKey("spotify_access_token_expires_at")
val SpotifyAccountNameKey = stringPreferencesKey("spotify_account_name")
val SpotifyAccountAvatarUrlKey = stringPreferencesKey("spotify_account_avatar_url")
val ShowSpotifyPlaylistsKey = booleanPreferencesKey("show_spotify_playlists")
/** Which service the Library's sections read from — see [app.natune.constants.LibrarySource]. */
val LibrarySourceKey = stringPreferencesKey("library_source")
/**
 * Last explicitly applied preset, retained for legacy backups. Surface preferences own rendering;
 * this flag must never force individual settings after their one-time migration.
 */
val AppleMusicExperienceKey = booleanPreferencesKey("apple_music_experience")
/**
 * SponsorBlock: skip the stretches of a video the community has marked. Off by default -- it is a
 * third-party lookup, so it is the user's call whether to make it.
 */
val SponsorBlockEnabledKey = booleanPreferencesKey("sponsor_block_enabled")
/** Which segment kinds to skip, as SponsorBlockCategory.apiName values. */
val SponsorBlockCategoriesKey = stringSetPreferencesKey("sponsor_block_categories")
/** Origin of the SponsorBlock instance to ask; blank means the public one. */
val SponsorBlockApiUrlKey = stringPreferencesKey("sponsor_block_api_url")
/** Also skip marked segments in podcast episodes. Has effect only while [SponsorBlockEnabledKey] is on. */
val SponsorBlockPodcastsEnabledKey = booleanPreferencesKey("sponsor_block_podcasts_enabled")
/** The player design style [AppleMusicExperienceKey] displaced, so switching it off can restore it. */
val StyleBeforeAppleMusicKey = stringPreferencesKey("style_before_apple_music")
val SpotifyLibraryPlaylistsCacheKey = stringPreferencesKey("spotify_library_playlists_cache")
val SpotifyRecentlyPlayedCacheKey = stringPreferencesKey("spotify_recently_played_cache")
/**
 * When [SpotifyRecentlyPlayedCacheKey] was written, as epoch millis.
 *
 * Kept beside the list so the expiry survives a process restart. Without it a cold start has no way
 * to tell a minute-old history from a month-old one, treats every cached history as stale, and
 * re-reads the window — which is what keeps the endpoint's rate limit in force. Absent (an older
 * install) reads as 0, i.e. stale, so the first refresh after upgrading is a delta read.
 */
val SpotifyRecentlyPlayedCacheFetchedAtKey = longPreferencesKey("spotify_recently_played_cache_fetched_at")
/**
 * Set of item IDs (song/album/artist) that the user has hidden from the
 * "Keep Listening" section on the home page. When the user long-presses an
 * item in Keep Listening and selects "Hide from home," the item's ID is
 * added here and filtered out of the keepListening flow.
 */
val HiddenHomeItemsKey = stringSetPreferencesKey("hidden_home_items")
// Tidal music source integration (ported from MetroFuse)
val TidalEnabledKey = booleanPreferencesKey("tidalEnabled")
val TidalAudioQualityKey = stringPreferencesKey("tidalAudioQuality")
val TidalArtworkFallbackEnabledKey = booleanPreferencesKey("tidalArtworkFallbackEnabled")
val TidalAnimatedCoversEnabledKey = booleanPreferencesKey("tidalAnimatedCoversEnabled")
val TidalAccountNameKey = stringPreferencesKey("tidal_account_name")

// When ON (default), synced lyrics render in place of the player artwork (BitChord-style
// inline lyrics on the player screen). The lyrics button still opens the full lyrics page.
// Newline-separated list of user-configured HiFi/QQDL instance base URLs. Empty = use defaults.
val TidalInstancesKey = stringPreferencesKey("tidalInstances")
// JSON cache of the last instance health scan: [{"url","status","latencyMs","checkedAt"}, ...].
// Persisted so working instances are remembered across launches instead of re-probed every time.
val TidalVerifiedInstancesKey = stringPreferencesKey("tidalVerifiedInstances")
// The last Tidal track id that resolved successfully. Used as a "probe" track for health checks so
// we can tell a fully-working instance apart from a reachable-but-preview-only (unsubscribed) one.
val TidalLastProbeTrackKey = stringPreferencesKey("tidalLastProbeTrack")
// Tidal account login (device/OAuth) + subscription state.
val TidalAccessTokenKey = stringPreferencesKey("tidalAccessToken")
val TidalRefreshTokenKey = stringPreferencesKey("tidalRefreshToken")
val TidalTokenExpiryKey = longPreferencesKey("tidalTokenExpiry")
val TidalSubscriptionKey = stringPreferencesKey("tidalSubscription")
// Which auth flow produced the stored session: "oauth" (device code), "pkce" (web login with a
// durable refresh token), or "webcapture" (live Bearer token grabbed from the web player, no
// refresh token). The refresh path uses this to pick the right OAuth client_id/secret.
val TidalAuthFlowKey = stringPreferencesKey("tidalAuthFlow")
// Real account country + user id captured at login, used by the account playback path instead of a
// hardcoded country. userId is also needed for the subscription-tier lookup.
val TidalCountryCodeKey = stringPreferencesKey("tidalCountryCode")
val TidalUserIdKey = longPreferencesKey("tidalUserId")
// Set when a silent token refresh fails so the UI can surface a "reconnect" prompt. Cleared on any
// successful login/refresh.
val TidalNeedsReloginKey = booleanPreferencesKey("tidalNeedsRelogin")
val AppleMusicQualityKey = stringPreferencesKey("appleMusicQuality")
/**
 * Master enable for Apple Music as a streaming source (Settings → Sources → Apple Music).
 * Default off: playback needs a Media-User-Token with an active Apple Music subscription,
 * and the resolution chain hits Apple's unofficial web-playback endpoint.
 */
val AppleMusicSourceEnabledKey = booleanPreferencesKey("appleMusicSourceEnabled")
// Streaming/playback only (like Tidal): the app never bundles endpoints — the user pastes their own
// proxy instance URLs. Each instance exposes get-music (search) + download-music (stream URL).
val QobuzEnabledKey = booleanPreferencesKey("qobuzEnabled")
// Qobuz backup server (mlc.kouzu.in). Separate from Qobuz proper — the
// backup takes a YouTube video id and returns a lossless stream, while
// regular Qobuz uses source pool tokens + community proxy instances. The
// user can toggle / reorder / per-song-pin each independently. Default OFF
// because it's an external community service that should be opt-in.
val QobuzBackupEnabledKey = booleanPreferencesKey("qobuzBackupEnabled")
// CSV of user-provided Qobuz proxy instance base URLs, highest priority first.
val QobuzInstancesKey = stringPreferencesKey("qobuzInstances")
// When ON (default), the app uses shared accounts from the community Source Pool for Tidal, Qobuz,
// Deezer, and Apple Music playback. When OFF, only manually-added accounts are used. The manual
// sign-in UI is always visible regardless of this toggle, so users can add their own accounts
// whether or not they use the pool.
val UsePoolAccountsKey = booleanPreferencesKey("use_pool_accounts")
// The last Qobuz track id that resolved successfully, used as a health "probe" track so we can tell
// a fully-working instance from a reachable-but-preview-only one.
val QobuzLastProbeTrackKey = stringPreferencesKey("qobuzLastProbeTrack")
// JSON list of direct Qobuz API token entries (user_auth_token + user_id + app_id + app_secret +
// metadata). These call www.qobuz.com/api.json/0.2 directly with an MD5 request signature, so they
// need no proxy instance. Tried before proxy URLs during resolution (direct = highest fidelity).
val QobuzTokensKey = stringPreferencesKey("qobuzTokens")
val QobuzAudioQualityKey = stringPreferencesKey("qobuzAudioQuality")
// ---------------------------------------------------------------------------
// Telegram channel streaming integration
// ---------------------------------------------------------------------------
// Streams audio files (lossless-first) directly from Telegram channels via TDLib. The user logs in
// with their own Telegram account (phone + code + optional 2FA password); the app's api_id/api_hash
// are baked in at build time (BuildConfig.TELEGRAM_API_ID/HASH), so no developer credentials are
// entered in-app. The session lives in TDLib's encrypted database under filesDir; these keys only
// hold display metadata for the settings screen.
val TelegramAccountNameKey = stringPreferencesKey("telegramAccountName")
val TelegramAccountPhoneKey = stringPreferencesKey("telegramAccountPhone")
// When ON (default) the channel browser only materialises lossless files (FLAC/WAV/AIFF/APE/ALAC/…)
// into the channel's playlist; when OFF every audio message and audio-typed document is included.
val TelegramLosslessOnlyKey = booleanPreferencesKey("telegramLosslessOnly")
// ---------------------------------------------------------------------------
// Telegram bots
// ---------------------------------------------------------------------------
// Persisted list of bots the user has added (so they can be re-opened without
// re-pasting their @username each time). Stored as a JSON array; the encoder
// lives in app.natune.telegram.TelegramBots.
val TelegramBotsKey = stringPreferencesKey("telegramBots")
// When ON, songs added to a Telegram-backed playlist (LPtg…/channel-id) from a
// bot result are auto-forwarded to that channel so the user's own channel stays
// in sync with what they've collected via bots. Default ON per the feature brief.
val TelegramBotForwardToChannelKey = booleanPreferencesKey("telegramBotForwardToChannel")

// ---------------------------------------------------------------------------
// Multi-source audio framework
// ---------------------------------------------------------------------------
// A configurable set of lossless/stream sources. The user can reorder them (priority for playback
// resolution), toggle each on/off, and pick a primary "search" source. YouTube is the implicit
// terminal fallback and can be toggled off like any other source; disabling it turns "no other
// source matched" into a hard playback failure rather than a silent YouTube stream.
// ---------------------------------------------------------------------------
// QQ Music source
// ---------------------------------------------------------------------------
// Playback uses the user's own QQ Music account, signed in by scanning a QR code in settings. The
// app then calls the same client protocol QQ Music's own desktop and web clients use — catalogue
// search, track detail, and the call that mints a playable URL for that account's entitlements —
// and plays what comes back, decrypting the protected containers a lossless tier arrives in.
//
// The account's ticket lives here. The web client carries the same ticket in a cookie called
// `qm_keyst`; QQ Music's own API calls it the `musickey`. The CSRF parameter the RPC modules take
// (`g_tk`) is derived from it on every request rather than stored, so a rotated ticket cannot leave
// a stale one behind.
//
// Boundaries this source keeps, because the alternative is a source nobody can rely on:
//  - it asks only for the tier the user picked, and an unentitled tier comes back empty and falls
//    through to the next source rather than being worked around;
//  - it decrypts only a container the account itself was served, on device, into the app's own
//    cache, and never writes anything back to the service;
//  - it circumvents no advert, no limit and no purchase prompt.
// QQ sits in AudioSourceConfig.DEFAULT_ORDER like every other source, just above YouTube, and
// defaults to off: the chain filters disabled sources out, so listing it costs nothing until
// the user signs in and switches it on.
val QqMusicEnabledKey = booleanPreferencesKey("qqMusicEnabled")
val QqMusicAudioQualityKey = stringPreferencesKey("qqMusicAudioQuality")
// The account's decimal QQ number, which is the `musicid` the catalogue reports.
val QqMusicUinKey = stringPreferencesKey("qqMusicUin")
// The `musickey` ticket minted for this account at sign-in. Compared with the other sources'
// session keys, this one is a bearer ticket rather than a cookie jar: everything the calls need is
// derived from it.
val QqMusicMusickeyKey = stringPreferencesKey("qqMusicMusickey")
// Display-only nickname from the login reply, so the settings row can say who is signed in without
// keeping anything else about the account.
val QqMusicNicknameKey = stringPreferencesKey("qqMusicNickname")
// ---------------------------------------------------------------------------
// Spotify streaming source (experimental)
// ---------------------------------------------------------------------------
// Audio from Spotify itself, on a second account that exists only for streaming. The catalog
// integration above (SpotifySpDcKey and friends) is never read here and never written from here:
// the streaming source signs in through its own WebView profile, keeps its own cookie and token,
// and refuses the account the catalog is signed in with, so playback reporting and any action
// Spotify takes against a streaming session land on the throwaway account and not on the library.
// Spotify sits in AudioSourceConfig.DEFAULT_ORDER like every other source, just above YouTube,
// and defaults to off. The switch alone does nothing until a streaming account is signed in —
// only then does the source count as enabled.
val SpotifyStreamEnabledKey = booleanPreferencesKey("spotifyStreamEnabled")
val SpotifyStreamSpDcKey = stringPreferencesKey("spotifyStreamSpDc")
val SpotifyStreamSpKeyKey = stringPreferencesKey("spotifyStreamSpKey")
val SpotifyStreamAccessTokenKey = stringPreferencesKey("spotifyStreamAccessToken")
val SpotifyStreamAccessTokenExpiresAtKey = longPreferencesKey("spotifyStreamAccessTokenExpiresAt")
// The streaming account's user id, kept so a later catalog sign-in with the same account can be
// noticed and the streaming source stood down instead of reporting plays into the library.
val SpotifyStreamUserIdKey = stringPreferencesKey("spotifyStreamUserId")
val SpotifyStreamAccountNameKey = stringPreferencesKey("spotifyStreamAccountName")
val SpotifyStreamResolveMethodKey = stringPreferencesKey("spotifyStreamResolveMethod")
// The 40-hex virtual Connect device the web-player method registers. Stable per install so the
// streaming account does not collect a new device on every play.
val SpotifyStreamDeviceIdKey = stringPreferencesKey("spotifyStreamDeviceId")
// ---------------------------------------------------------------------------
// YouTube source
// ---------------------------------------------------------------------------
// YouTube is still the implicit terminal fallback in the resolution chain, but it is a normal
// toggleable source: turning it off means the resolver will not use YouTube even when every other
// source misses, so playback fails with the usual no-stream error instead of silently streaming
// from YouTube. Default ON to preserve the historical "always available" behaviour.
val YouTubeSourceEnabledKey = booleanPreferencesKey("youtubeSourceEnabled")
// CSV of AudioSourceType names, highest priority first. Empty = built-in default order.
val AudioSourceOrderKey = stringPreferencesKey("audioSourceOrder")
// Per-song "play from" overrides, encoded as "songId=SOURCE" entries joined by ';'. The chosen
// source is forced for that specific song (subject to the metadata match gate), overriding the
// global source order. Persisted here so it is included in Settings backups.
val SongSourceOverrideKey = stringPreferencesKey("songSourceOverride")
/** Per-song Qobuz trackId override (CSV of `songId=qobuzTrackId;…`). */
val SongSourceQobuzTrackIdKey = stringPreferencesKey("songSourceQobuzTrackId")
/** Per-song Qobuz-**backup** video-id override (CSV of `songId=youtubeVideoId;…`). */
val SongSourceQobuzBackupVideoIdKey = stringPreferencesKey("songSourceQobuzBackupVideoId")
// When logged in, try the user's own Tidal account (official API) before the public instances.
val TidalAccountFirstKey = booleanPreferencesKey("tidalAccountFirst")
// ---------------------------------------------------------------------------
// Deezer source
// ---------------------------------------------------------------------------
// Unlike Tidal/Qobuz there is no self-hosted proxy tier: Deezer streams come from accounts
// authenticated with an `arl` cookie, supplied by the pool or by a manual sign-in. Defaults OFF
// because the source is inert without accounts.
val DeezerEnabledKey = booleanPreferencesKey("deezerEnabled")
// A manually captured `arl` cookie. Kept separate from the pool cache: the pool is wiped and
// rewritten on every refresh and is gated behind PoolAccountManager.isEnabled, so storing a
// user's own credential there would lose it on the next sync.
val DeezerArlKey = stringPreferencesKey("deezerArl")
// Display label for the manually signed-in account, so the settings row can say who is signed in
// without keeping the ARL itself anywhere near the UI.
val DeezerAccountNameKey = stringPreferencesKey("deezerAccountName")
// Whether the manual account reported a lossless-capable plan. Only orders resolution attempts;
// the provider still verifies the real tier per track.
val DeezerAccountPremiumKey = booleanPreferencesKey("deezerAccountPremium")
// The user's own Deezer API instances (self-hosted servers that stream already-decrypted audio),
// one base URL per line. Tried after accounts; the Source Pool's instance feed is appended to them.
val DeezerInstancesKey = stringPreferencesKey("deezerInstances")
// ---------------------------------------------------------------------------
// Amazon Music source
// ---------------------------------------------------------------------------
// Shaped like Tidal rather than Deezer: the audio tier is reached through a self-hosted "Amazon
// Music Stream API" instance the user adds (the list starts EMPTY — the ecosystem has no working
// public default), and the instance is usually gated behind Cloudflare Turnstile. Authorization is
// therefore one of:
//   - a Turnstile JWT obtained interactively via AmazonTurnstileActivity and persisted here with
//     its expiry (authorize again to refresh), or
//   - the instance operator's bypass_token.
// Without an instance AND auth material the resolver declines every track and playback falls
// through to the next source, which is why the toggle defaults OFF.
val AmazonEnabledKey = booleanPreferencesKey("amazonEnabled")
// Newline- or comma-separated instance URLs, same shape as TidalInstancesKey and QobuzInstancesKey
// so parseInstances() in MusicService reads all three. Empty by default.
val AmazonInstancesKey = stringPreferencesKey("amazonInstances")
// Cloudflare Turnstile JWT (X-Turnstile-JWT header) and the wall-clock ms at which it stops being
// trusted (already discounted for clock skew). Refreshed by running AmazonTurnstileActivity again.
val AmazonTurnstileJwtKey = stringPreferencesKey("amazonTurnstileJwt")
val AmazonTurnstileJwtExpiryMsKey = longPreferencesKey("amazonTurnstileJwtExpiryMs")
// Operator-provided bypass_token query parameter — the alternative to solving Turnstile.
val AmazonBypassTokenKey = stringPreferencesKey("amazonBypassToken")
val AmazonAudioQualityKey = stringPreferencesKey("amazonAudioQuality")
// ---------------------------------------------------------------------------
// JioSaavn source
// ---------------------------------------------------------------------------
// Ported from vivi-music (https://github.com/vivizzz007/vivi-music) under GPL-3.0.
// JioSaavn streams are unauthenticated MP4/AAC; CDN URLs are decrypted locally via DES-ECB.
// Defaults OFF because the source requires JioSaavn's public API to be reachable.
val JioSaavnEnabledKey = booleanPreferencesKey("enableSaavnStreaming")
// Bitrate selection: 96 / 160 / 320 kbps AAC.
val SaavnAudioQualityKey = stringPreferencesKey("saavnAudioQuality")
val WebClientPoTokenEnabledKey = booleanPreferencesKey("webClientPoTokenEnabled")
val PoTokenGvsKey = stringPreferencesKey("poTokenGvs")
val PoTokenPlayerKey = stringPreferencesKey("poTokenPlayer")
val PoTokenSourceUrlKey = stringPreferencesKey("poTokenSourceUrl")
// App rating / star prompt preferences
val LaunchCountKey = intPreferencesKey("launch_count")
val OnboardingCompletedKey = booleanPreferencesKey("onboarding_completed")
// Last onboarding page the user was on. The onboarding ViewModel's page index is plain
// in-memory state; without persisting it, process death or activity recreation resets the
// flow to page 1 even though the user was mid-way through.
val OnboardingCurrentPageKey = intPreferencesKey("onboarding_current_page")
val HasPressedStarKey = booleanPreferencesKey("has_pressed_star")
val RemindAfterKey = intPreferencesKey("remind_after")
// Update settings
val EnableUpdateNotificationKey = booleanPreferencesKey("enableUpdateNotification")
val UpdateChannelKey = stringPreferencesKey("updateChannel")
val LastUpdateCheckKey = longPreferencesKey("lastUpdateCheck")
val LastUpdateCheckChannelKey = stringPreferencesKey("lastUpdateCheckChannel")
val LastNotifiedVersionKey = stringPreferencesKey("lastNotifiedVersion")
val GitHubContributorsEtagKey = stringPreferencesKey("github_contributors_etag")
val GitHubContributorsJsonKey = stringPreferencesKey("github_contributors_json")
val GitHubContributorsLastCheckedAtKey = longPreferencesKey("github_contributors_last_checked_at")
val GitHubTranslationContributorsJsonKey = stringPreferencesKey("github_translation_contributors_json")
val GitHubTranslationContributorsLastCheckedAtKey = longPreferencesKey("github_translation_contributors_last_checked_at")
val GitHubReleasesEtagKey = stringPreferencesKey("github_releases_etag")
val GitHubReleasesJsonKey = stringPreferencesKey("github_releases_json")
val GitHubReleasesLastCheckedAtKey = longPreferencesKey("github_releases_last_checked_at")
val GitHubReleasesFingerprintKey = stringPreferencesKey("github_releases_fingerprint")
val NightlyReleasesEtagKey = stringPreferencesKey("daily_nightly_releases_etag")
val NightlyReleasesJsonKey = stringPreferencesKey("daily_nightly_releases_json")
val NightlyReleasesLastCheckedAtKey = longPreferencesKey("daily_nightly_releases_last_checked_at")
val NightlyReleasesFingerprintKey = stringPreferencesKey("daily_nightly_releases_fingerprint")
// Keyless update feed: CI uploads a schema-v1 manifest per channel to the fixed `update-feed`
// release (releases/download/update-feed/<channel>.json). The app caches the raw JSON with a TTL
// (15 min nightly, 6 h stable) so a fresh manifest costs no api.github.com request at all.
val UpdateFeedManifestNightlyJsonKey = stringPreferencesKey("update_feed_manifest_nightly_json")
val UpdateFeedManifestNightlyLastCheckedAtKey = longPreferencesKey("update_feed_manifest_nightly_last_checked_at")
val UpdateFeedManifestStableJsonKey = stringPreferencesKey("update_feed_manifest_stable_json")
val UpdateFeedManifestStableLastCheckedAtKey = longPreferencesKey("update_feed_manifest_stable_last_checked_at")
// GitHub API rate-limit backoff: epoch ms until which api.github.com must not be called (403/429
// with X-RateLimit-Remaining: 0); caches are served until it passes.
val GitHubRateLimitedUntilKey = longPreferencesKey("github_rate_limited_until")
// Cached Updates-screen commit history, kept per requested branch (dev for Nightly, main for
// Stable) so a channel switch never serves the other branch's page.
val GitHubCommitsJsonKey = stringPreferencesKey("github_commits_json")
val GitHubCommitsEtagKey = stringPreferencesKey("github_commits_etag")
val GitHubCommitsLastCheckedAtKey = longPreferencesKey("github_commits_last_checked_at")
val GitHubCommitsBranchKey = stringPreferencesKey("github_commits_branch")
/**
 * When true, the inline + fullscreen video player renders a slowly drifting
 * blurred copy of the song thumbnail behind the video surface — mimicking
 * YouTube's "ambient mode" so the letterboxed black area glows with the
 * artwork's dominant colors instead of being pure black.
 */
val VideoAmbientModeKey = booleanPreferencesKey("videoAmbientMode")
/**
 * Playback speed applied to BOTH the audio ExoPlayer (in MusicService) and
 * the video ExoPlayer (in VideoArtworkState). Stored as a Float in
 * [0.25f, 2.0f]. 1.0 = normal speed. Surfaced via the fullscreen video
 * overlay's 3-dot overflow menu.
 */
val VideoPlaybackSpeedKey = floatPreferencesKey("videoPlaybackSpeed")
val VideoAspectRatioKey = stringPreferencesKey("videoAspectRatio")
// Bit-perfect output, and the one audio-processing switch the unified chain runs under.
//
// The retired Tryptify/LastWave/float-DSP switches below are still read: they are where the shared chain
// finds its tone, clarity and dither presets, so an install that set them keeps the same sound. Nothing
// writes them any more.
val AudioProcessingEnabledKey = booleanPreferencesKey("audioProcessingEnabled")
val FloatDspEnabledKey = booleanPreferencesKey("floatDspEnabled")
val UsbExclusiveAudioKey = booleanPreferencesKey("usbExclusiveAudio")
val BitPerfectOutputKey = booleanPreferencesKey("bitPerfectOutput")
val BitPerfectNativeRateKey = booleanPreferencesKey("bitPerfectNativeRate")
val TryptifyAudioProcessingKey = booleanPreferencesKey("tryptifyAudioProcessing")
val LastwaveAudioProcessingKey = booleanPreferencesKey("lastwaveAudioProcessing")
// Lyrics provider enable toggles (ported from 4nx3b).
val EnableBiniLyricsKey = booleanPreferencesKey("enableBiniLyrics")
val EnableGeniusLyricsKey = booleanPreferencesKey("enableGeniusLyrics")
val EnableLrcRedKey = booleanPreferencesKey("enableLrcRed")
