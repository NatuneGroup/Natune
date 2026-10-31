# Settings route map — original `dev` routes vs KMP hosts

Every settings/login route the original Android app (ArchiveTune `dev`, HEAD b7e4dd444)
declares, and which KMP host mounts it today. "Mounted" means a real page renders;
a host that cannot supply a page's owners keeps the explicit unbound/boundary surface
instead of an empty page.

Legend: **A** = Android (`app/` NavigationBuilder + settings screens),
**D** = desktop (`desktopApp`, `DESKTOP_SETTINGS_ROUTES` + `DESKTOP_GENERAL_SETTINGS_ROUTES` + `DESKTOP_EXTRA_SETTINGS_ROUTES`),
**iOS** = iOS host (`IOS_SETTINGS_ROUTES` + `IOS_GENERAL_SETTINGS_ROUTES`).

| Route | A | D | iOS | Notes |
|---|:-:|:-:|:-:|---|
| `settings` (home) | ✓ | ✓ | ✓ | |
| `settings/account` | ✓ | ✓ | — | no YouTube account owner on iOS |
| `settings/hidden_playlists` | ✓ | ✓ | — | desktop: lists hidden local playlists with Unhide; the desktop Library always leaves them out, and the Playlists page leaves them out until its Show hidden toggle is on, as Android does |
| `settings/appearance` | ✓ | ✓ | ✓ | one page: Theme, Player and Interface sections (anchors `appearance_theme`, `appearance_player`, `appearance_interface`); D/iOS: no wallpaper colors/dynamic, no font picker on iOS; desktop opens the home-screen, library-chip, playlist-tag and artwork-provider editors, iOS keeps them unavailable |
| `settings/appearance/theme` | ✓ | ✓ | ✓ | redirect: the Appearance page scrolled to `appearance_theme` (or its `scrollTo`) |
| `settings/appearance/player` | ✓ | ✓ | ✓ | redirect: the Appearance page scrolled to `appearance_player` (or its `scrollTo`) |
| `settings/appearance/interface` | ✓ | ✓ | ✓ | redirect: the Appearance page scrolled to `appearance_interface` (or its `scrollTo`) |
| `settings/appearance/extras` | ✓ | ✓ | ✓ | |
| `settings/appearance/lyrics_animations` | ✓ | ✓ | ✓ | |
| `settings/appearance/navigation_bar` | ✓ | — | — | Android-only surface |
| `settings/appearance/liquid_glass` | ✓ | — | — | Android-only liquid glass tuning; added after the baseline |
| `settings/appearance/aod_customized` | ✓ | — | — | Android-only surface |
| `settings/appearance/icon` | ✓ | — | — | launcher icon switcher, Android-only |
| `settings/appearance/palette_picker` | ✓ | — | — | Android-only |
| `settings/appearance/theme_creator` | ✓ | — | — | Android-only |
| `settings/content` | ✓ | ✓ | — | desktop mounts |
| `settings/lyrics` | ✓ | ✓ | ✓ | |
| `settings/lyrics/providers` | ✓ | ✓ | ✓ | real Paxsenix stats + endpoint probes |
| `settings/lyrics/romanisation` | ✓ | ✓ | ✓ | JP toggle gated on real pack state |
| `settings/language_packs` | ✓ | ✓ | ✓ | JP pack real; desktop installs and removes a user-chosen SpatialFlow TTF (no pinned download on desktop) and the SpatialFlow title uses it once installed; the on-device render is not verified yet |
| `settings/internet` | ✓ | ✓ | — | desktop mounts; no network owner on iOS |
| `settings/player` | ✓ | ✓ | ✓ | bit-perfect/USB rows read unsupported |
| `settings/player/ytdlp` | ✓ | ✓ | ✓ | BUG-004 target; real runtime status (unavailable on iOS) |
| `settings/canvas` | ✓ | ✓ | — | desktop mounts |
| `settings/sources` | ✓ | ✓ | — | desktop mounts |
| `settings/android_auto` | ✓ | — | — | Android-only surface |
| `settings/storage` | ✓ | ✓ | — | desktop mounts |
| `settings/storage/export_songs` | ✓ | ✓ | — | desktop: search, select, export downloads to a folder, delete |
| `settings/downloads` | ✓ | ✓ | — | desktop mounts |
| `settings/privacy` | ✓ | ✓ | ✓ | BUG-005 defaults hold via shared rows; desktop adds pause listen/search history and clear listen history |
| `settings/backup_restore` | ✓ | ✓ | — | desktop mounts, plus Export for Android (schema-38 `song.db` + `settings.xml`); iOS has no transports |
| `settings/discord` | ✓ | ✓ | — | desktop mounts |
| `settings/discord/experimental` | ✓ | ✓ | ✓ | |
| `settings/integration` | ✓ | ✓ | ✓ | pool/Spotify unconfigured → honest states |
| `settings/integrations/listen_together` | ✓ | ✓ | ✓ | iOS: one owner over the session graph; custom member picture reports unavailable |
| `settings/tidal` | ✓ | ✓ | ✓ | |
| `settings/tidal/login` | ✓ | ✓ | ✓ | iOS: mounted web sign-in page; reports unavailable without the `ARCHIVETUNE_*` TIDAL client settings |
| `settings/qobuz` | ✓ | ✓ | ✓ | iOS: mounted; token and instance health checks |
| `settings/qobuz/login` | ✓ | ✓ | ✓ | iOS: mounted; token verification and web-player secret candidates |
| `settings/deezer` | ✓ | ✓ | ✓ | |
| `settings/deezer/login` | ✓ | ✓ | ✓ | iOS: mounted Deezer web sign-in page |
| `settings/amazon` | ✓ | ✓ | — | desktop mounts; Authorize runs the Turnstile challenge in a native window on the sign-in origin and reports not configured without the build-time sitekey |
| `settings/jiosaavn` | ✓ | ✓ | — | desktop mounts |
| `settings/qqmusic` | ✓ | ✓ | — | desktop mounts |
| `settings/spotify_streaming` | ✓ | ✓ | — | desktop mounts |
| `settings/applemusic` + `/login` | ✓ | page | — | desktop mounts the page, not `/login` |
| `settings/telegram` | ✓ | ✓ | ✓ | iOS: one TDLib session page; the unbound boundary until the session exists |
| `settings/telegram/login` `/browse` `/bots` `/chat/{id}` | ✓ | login | browse, bots | desktop mounts login only; iOS mounts browse and bots while the session exists, and login and bot chat stay on the boundary |
| `settings/ai_integration` | ✓ | ✓ | ✓ | real KtorAiTextService; "not configured" without provider |
| `settings/lastfm` | ✓ | ✓ | — | desktop mounts (+dashboard route) |
| `settings/lastfm/login` `/librefm-login` | ✓ | ✓ | — | desktop: starts the browser sign-in for Last.fm or Libre.fm and returns when it ends |
| `settings/lastfm/dashboard` | ✓ | ✓ | — | desktop mounts |
| `settings/misc` (developer) | ✓ | ✓ | ✓ | acoustic/discord-debug/nerd-stats sections unavailable on iOS |
| `settings/logcat` | ✓ | ✓ | ✓ | DiagnosticLogScreen on all three |
| `settings/update` | ✓ | ✓ | ✓ | desktop mounts; iOS mounts the page with checks reported unavailable |
| `settings/changelog?channel=` | ✓ | ✓ | — | desktop mounts |
| `settings/about` | ✓ | ✓ | ✓ | iOS: real GitHub contributors/translations fetch |
| `settings/po_token` | ✓ | ✓ | — | YouTube-only; desktop mounts |
| `settings/customize_background` | ✓ | ✓ | — | desktop: local image, blur, contrast, brightness (also `customize_background`) |
| `settings/youtube/oauth` | ✓ | ✓ | — | desktop: device-code sign-in; the client pair comes from the build (CI secrets) or an environment variable or system property of the same name, and sign-in reports not configured without it; nothing on desktop uses the token for playback yet |
| `login` (YouTube) | ✓ | ✓ | — | desktop mounts |

## Settings search

Android resolves `parent/scrollKey` through `searchableSettingsRoute` in
`app/.../settings/SettingsScreen.kt`; every mounted route is a search destination,
and `playback/ytdlp` resolves to `settings/player/ytdlp?scrollTo=ytdlp` (BUG-004).
The shared `settingsSearchRoute` in `shared-ui` `SettingsRoutes.kt` carries the same
owner→route table for shared hosts, including the `ytdlp` leaf.

## Row menus / overflow (iOS)

`ArchiveTuneHostBindings.more` now opens `IosItemMenuState`; playlist and history
pages pass it as their `more` handler and the overlay renders `IosItemMenuDialog`,
offering the queue commands the iOS queue owner answers (Play next, Add to queue).
Menu rows needing library/download/share/artist owners stay out until those owners
exist — same policy as the desktop menu.
