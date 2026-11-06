# Settings route map — original `dev` routes vs KMP hosts

Every settings/login route the original Android app (ArchiveTune `dev`, HEAD b7e4dd444)
declares, and which KMP host mounts it today. "Mounted" means a real page renders;
a host that cannot supply a page's owners keeps the explicit unbound/boundary surface
instead of an empty page.

Legend: **A** = Android (`app/` NavigationBuilder + settings screens),
**D** = desktop (`desktopApp`, `DESKTOP_SETTINGS_ROUTES` + `DESKTOP_GENERAL_SETTINGS_ROUTES` + `DESKTOP_EXTRA_SETTINGS_ROUTES`),
**iOS** = iOS host (`IOS_SETTINGS_ROUTES` + `IOS_GENERAL_SETTINGS_ROUTES` + `IOS_LANE_SETTINGS_ROUTES`).

| Route | A | D | iOS | Notes |
|---|:-:|:-:|:-:|---|
| `settings` (home) | ✓ | ✓ | ✓ | |
| `settings/account` | ✓ | ✓ | ✓ | iOS: the shared AccountSettings over `IosYouTubeAccountOwner`, also mounted as plain `account`; the force-sync-after-switch row reports unavailable (no YouTube library sync on this host) |
| `settings/hidden_playlists` | ✓ | ✓ | ✓ | D/iOS: lists hidden local playlists with Unhide over the shared library; the desktop Library always leaves them out, and the Playlists page leaves them out until its Show hidden toggle is on, as Android does |
| `settings/appearance` | ✓ | ✓ | ✓ | one page: Theme, Player and Interface sections (anchors `appearance_theme`, `appearance_player`, `appearance_interface`); D/iOS: no wallpaper colors/dynamic, no font picker on iOS; desktop opens the home-screen, library-chip, playlist-tag and artwork-provider editors, iOS opens its own dialogs for the same four preferences |
| `settings/appearance/theme` | ✓ | ✓ | ✓ | redirect: the Appearance page scrolled to `appearance_theme` (or its `scrollTo`) |
| `settings/appearance/player` | ✓ | ✓ | ✓ | redirect: the Appearance page scrolled to `appearance_player` (or its `scrollTo`) |
| `settings/appearance/interface` | ✓ | ✓ | ✓ | redirect: the Appearance page scrolled to `appearance_interface` (or its `scrollTo`) |
| `settings/appearance/extras` | ✓ | ✓ | ✓ | |
| `settings/appearance/lyrics_animations` | ✓ | ✓ | ✓ | |
| `settings/appearance/navigation_bar` | ✓ | ✓ | ✓ | shared page hoisted from the iOS rows; the Liquid Glass and frosted rows report why they are disabled on each host |
| `settings/appearance/liquid_glass` | ✓ | ✓ | ✓ | shared tuning page over the same persisted keys; rows explain the off state (blur off, no refraction renderer, master switch off) |
| `settings/appearance/aod_customized` | ✓ | — | ✓ | iOS mounts the shared AOD rows plus an honest note that this host has no such display; desktop keeps the route unmounted |
| `settings/appearance/icon` | ✓ | — | — | launcher icon switcher, Android-only; the iOS route resolves to the honest unavailable page |
| `settings/appearance/palette_picker` | ✓ | — | — | Android-only; the iOS route resolves to the honest unavailable page |
| `settings/appearance/theme_creator` | ✓ | — | — | Android-only |
| `settings/content` | ✓ | ✓ | ✓ | iOS: the ISO language and country catalogs of the platform locale |
| `settings/lyrics` | ✓ | ✓ | ✓ | |
| `settings/lyrics/providers` | ✓ | ✓ | ✓ | real Paxsenix stats + endpoint probes |
| `settings/lyrics/romanisation` | ✓ | ✓ | ✓ | JP toggle gated on real pack state |
| `settings/language_packs` | ✓ | ✓ | ✓ | JP pack real; desktop installs and removes a user-chosen SpatialFlow TTF (no pinned download on desktop) and the SpatialFlow title uses it once installed; the on-device render is not verified yet; iOS reports the SpatialFlow font unavailable (no host font consumer) |
| `settings/internet` | ✓ | ✓ | ✓ | iOS: the saved static proxy (HTTP or SOCKS, address and credentials) applies at launch to the session graph's Darwin engine and the uploads client, and a saved edit applies next launch; rotation is hidden (one `NSURLSession` per engine), DNS-over-HTTPS, the YouTube region and the stream bypass switch are hidden because no iOS path applies them, and the page states the limits |
| `settings/player` | ✓ | ✓ | ✓ | bit-perfect/USB rows read unsupported; the playlist-tag row opens `library/tags`, the real tag manager on iOS |
| `settings/player/ytdlp` | ✓ | ✓ | ✓ | BUG-004 target; real runtime status (Python/yt-dlp absent on iOS by design) |
| `settings/canvas` | ✓ | ✓ | ✓ | iOS: connectivity + provider probes and a real canvas store (bytes, cache limit, clear) |
| `settings/sources` | ✓ | ✓ | ✓ | iOS: per-source reachability probes; Spotify reports unreachable because the host has no streaming transport |
| `settings/android_auto` | ✓ | — | — | Android-only surface; the iOS route resolves to the honest unavailable page |
| `settings/storage` | ✓ | ✓ | ✓ | desktop: a folder move only records a staged marker; the next start copies the closed database and the rest of the instance under the source lock and writes the pointer atomically; iOS: image-cache bytes and clear over the shared URL cache plus the decoded-image holder, and the image-cache limit applies at open and on commit; the song/canvas/lyrics kinds report unavailable and storage move reports unavailable (the app container is fixed) |
| `settings/storage/export_songs` | ✓ | ✓ | ✓ | desktop: search, select, export downloads to a folder, delete; iOS: exports the stored downloads to a Files-shared location |
| `settings/downloads` | ✓ | ✓ | ✓ | iOS: clear drops files, rows and staged bytes through the downloads owner |
| `settings/privacy` | ✓ | ✓ | ✓ | BUG-005 defaults hold via shared rows; desktop adds pause listen/search history and clear listen history |
| `settings/backup_restore` | ✓ | ✓ | ✓ | desktop mounts, plus Export for Android (schema-38 `song.db` + `settings.xml`); iOS: document-picker file backup/restore over `IosPlatformFiles`, no Drive transport |
| `settings/discord` | ✓ | ✓ | ✓ | iOS: PKCE authorize through the browser sheet and a bounded Gateway presence driven by playback only |
| `settings/discord/experimental` | ✓ | ✓ | ✓ | |
| `settings/integration` | ✓ | ✓ | ✓ | pool/Spotify unconfigured → honest states; iOS reports cross-service import unavailable |
| `settings/integrations/listen_together` | ✓ | ✓ | ✓ | iOS: one owner over the session graph; custom member picture reports unavailable |
| `settings/tidal` | ✓ | ✓ | ✓ | |
| `settings/tidal/login` | ✓ | ✓ | ✓ | iOS: PKCE web sign-in in the embedded sheet; reports unavailable without the `ARCHIVETUNE_*` TIDAL client settings |
| `settings/qobuz` | ✓ | ✓ | ✓ | iOS: mounted; token and instance health checks |
| `settings/qobuz/login` | ✓ | ✓ | ✓ | iOS: mounted; token verification and web-player secret candidates |
| `settings/deezer` | ✓ | ✓ | ✓ | |
| `settings/deezer/login` | ✓ | ✓ | ✓ | iOS: mounted Deezer web sign-in page |
| `settings/amazon` | ✓ | ✓ | ✓ | Authorize runs the Turnstile challenge (native window on desktop, the authorization sheet on iOS) and reports not configured without the build-time sitekey |
| `settings/jiosaavn` | ✓ | ✓ | ✓ | |
| `settings/qqmusic` | ✓ | ✓ | ✓ | iOS: page mounts; the QR sign-in loop reports unavailable (no iOS implementation) |
| `settings/spotify_streaming` | ✓ | ✓ | ✓ | the separate streaming account sign-in on each host's web flow |
| `settings/applemusic` + `/login` | ✓ | ✓ | ✓ | sign-in row opens the web flow on both hosts; iOS harvests the MusicKit pair in a JS-evaluating sheet, desktop reads the media-user-token cookie, then both write the two token keys |
| `settings/telegram` | ✓ | ✓ | ✓ | iOS: one TDLib session page; the unbound boundary until the session exists |
| `settings/telegram/login` `/browse` `/bots` `/bot/{id}` | ✓ | login, browse, bots, chat | login, browse, bots, chat | `settings/telegram/login` is a settings page; browse/bots/bot chat are `telegram/*` content destinations both hosts map while the session exists — plain `telegram` lands on browse too; iOS shows the unbound boundary without a session |
| `settings/ai_integration` | ✓ | ✓ | ✓ | real KtorAiTextService; "not configured" without provider |
| `settings/lastfm` | ✓ | ✓ | ✓ | iOS: account status, web sign-in card and the shared rows; scrobbles persist in the app container and likes mirror when sending loves is on; the dashboard row opens `settings/lastfm/dashboard` |
| `settings/lastfm/login` `/librefm-login` | ✓ | ✓ | — | desktop: starts the browser sign-in for Last.fm or Libre.fm and returns when it ends; iOS starts the same sign-in from the Last.fm page's Connect rows instead |
| `settings/lastfm/dashboard` | ✓ | ✓ | ✓ | the shared dashboard page over each host's Last.fm owner |
| `settings/misc` (developer) | ✓ | ✓ | ✓ | acoustic/discord-debug/nerd-stats sections unavailable on iOS |
| `settings/logcat` | ✓ | ✓ | ✓ | DiagnosticLogScreen on all three |
| `settings/crash_logs` `/{recordId}` | ✓ | ✓ | ✓ | list and record detail over each host's bounded record store, filled by the host's own crash capture; the detail rides `settings/crash_logs/record?id=`; iOS shows the unbound surface when no store exists |
| `settings/update` | ✓ | ✓ | ✓ | desktop mounts; iOS mounts the page with checks reported unavailable |
| `settings/changelog?channel=` | ✓ | ✓ | ✓ | iOS: the release-notes feed answers; the page never offers an install |
| `settings/about` | ✓ | ✓ | ✓ | iOS: real GitHub contributors/translations fetch |
| `settings/po_token` | ✓ | ✓ | ✓ | iOS: real BotGuard generation through the shared QuickJS engine and the graph's PO-token owner; fails honestly when the build credentials are absent |
| `settings/customize_background` | ✓ | ✓ | ✓ | desktop: local image, blur, contrast, brightness (also `customize_background`); iOS: photo pick downscaled to a JPEG in the app container plus the same sliders; the player slot renders the stored image |
| `settings/youtube/oauth` | ✓ | ✓ | ✓ | device-code sign-in over the shared `YouTubeDeviceCodeFlow`; the client pair comes from the build (`ARCHIVETUNE_YOUTUBE_OAUTH_CLIENT_ID`/`_SECRET`: CI secrets or env/system property on desktop, the matching Info.plist keys on iOS) and the page reports not configured without it; the token is stored under the shared `InnerTubeOAuth*` keys so the account page's sign-out/revoke and backups apply, and no shared host uses it for playback yet |
| `login` (YouTube) | ✓ | ✓ | ✓ | the browser sign-in page on each host |

## Settings search

Android resolves `parent/scrollKey` through `searchableSettingsRoute` in
`app/.../settings/SettingsScreen.kt`; every mounted route is a search destination,
and `playback/ytdlp` resolves to `settings/player/ytdlp?scrollTo=ytdlp` (BUG-004).
The shared `settingsSearchRoute` in `shared-ui` `SettingsRoutes.kt` carries the same
owner→route table for shared hosts, including the `ytdlp` leaf.

## Row menus / overflow (iOS)

`ArchiveTuneHostBindings.more` opens `IosItemMenuState`; playlist and history pages
pass it as their `more` handler and the overlay renders the menu rows the iOS queue,
library, download and share owners answer — play next/queue, speed-dial pin, library
toggle, download and download export, artist/album navigation, share links. The rows
whose owners still do not exist on the host (`removeFromCache`,
`setRecommendationBlocked`, `shareLocalAudio`) stay filtered or report unavailable —
same policy as the desktop menu; see `overflow-menu-parity.md` for the row table.
