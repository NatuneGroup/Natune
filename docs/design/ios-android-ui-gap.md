# iOS / Android UI gap inventory

Reference: `origin/dev` at `abc37a26` (2026-11-09). This is a source inventory,
not a simulator, device or screenshot comparison. Android owns its existing
`:app` screens; iOS renders one Compose tree. Desktop continues to use the
Metrolist PC layout. A shared page below does not by itself establish identical
row order, grouping, icons, dialogs or behavior.

Paths in the table are relative to these roots:

- **A**: `app/src/main/kotlin/moe/rukamori/archivetune/ui/`
- **I**: `shared-ui/src/iosMain/kotlin/moe/rukamori/archivetune/shared/ui/ios/`
- **U**: `shared-ui/src/commonMain/kotlin/moe/rukamori/archivetune/shared/ui/`

The route dispatch in `I/IosSettings.kt`, `I/IosLaneSettings.kt`,
`I/IosGeneralSettings.kt`, `I/IosLaneServicePages.kt`, `I/IosHostBindings.kt`
and `I/IosCatalogPages.kt` defines reachability. Multiple routes sharing the
same screen are named together. Native file/image pickers, AirPlay, browser
authentication, Keychain, MediaPlayer and system extensions stay host bindings.

| Screen / routes | Android file (A) | iOS file (I) | Shared? / common file (U) | Baseline gaps |
| --- | --- | --- | --- | --- |
| Settings index | `screens/settings/SettingsScreen.kt` | `IosSettings.kt` | Yes: `settings/SettingsHomePage.kt` | iOS inserts System integrations in place of Android Auto; title/back chrome differs; unsupported navigation rows are filtered. |
| Appearance / Theme / Interface | `screens/settings/AppearanceSettings.kt` | `IosGeneralSettings.kt` | Yes: `settings/AppearanceSettings.kt` | Shared rows, but Android-only anchors are hidden; missing Android grouping/icons; host owns font document picker and arrange dialogs. |
| Player appearance (17 styles) | `screens/settings/AppearanceSettings.kt` | `IosGeneralSettings.kt` | Yes: `settings/PlayerAppearanceSettings.kt` | Shared style meanings retained; several artwork/motion/mini-player rows hidden; no pixel evidence for the 17 style previews. |
| Appearance extras | `screens/settings/AppearanceExtrasSettings.kt` | `IosGeneralSettings.kt` | Yes: `settings/AppearanceSettingsRows.kt` | show_tags_in_library hidden; generic shared list lacks Android group cards/icons. |
| Lyrics animations | `screens/settings/LyricsAnimationSettings.kt` | `IosGeneralSettings.kt` | Yes: `settings/LyricsAnimationSettingsRows.kt` | Shared row definitions; page title, grouping and row icons differ. |
| Playback | `screens/settings/PlayerSettings.kt` | `IosPlaybackSettingsPage.kt` | Yes: `settings/PlaybackSettings.kt` | Rows filtered, unsupported toggles rewritten, then iOS USB rate/status/DSP text appended. Android row order/grouping differs; no playback implementation changes authorized here. |
| Lyrics settings | `screens/settings/LyricsSettings.kt` | `IosGeneralSettings.kt` | Yes: `settings/LyricsSettingsRows.kt` | Shared rows; Android grouping/header and row icon parity incomplete. |
| yt-dlp runtime | `screens/settings/PlayerSettings.kt` | `IosGeneralSettings.kt` | Yes: `settings/YtDlpRuntimeSettings.kt` | Runtime absent on iOS; diagnostic page differs from Android install/update/check rows. |
| Privacy / Behavior | `screens/settings/PrivacySettings.kt` | `IosSettings.kt` | No: iOS owns presentation | Only inline toggles and separate presence section. Missing Listen history / Search history / Misc grouping, clear/reset dialogs, refresh rate, music haptics, screenshot and supported-links rows. |
| Developer | `screens/settings/DebugSettings.kt` | `IosSettings.kt` | Yes: `settings/DeveloperSettings.kt` | Binding already shared; iOS hides debug switches and disables their sections despite live host slots. Android diagnostics unavailable must remain visible. |
| Language packs | `screens/settings/LanguagePackSettings.kt` | `IosSettings.kt` | Yes: `settings/LanguagePackSettings.kt` | Already shared host binding; unavailable SpatialFlow font is shown but layout/header differ. |
| About | `screens/settings/AboutScreen.kt` | `IosAboutSettings.kt` | Yes: `settings/AboutSettings.kt` | Already shared host binding; updater row hidden; platform version/distribution facts differ; generated Android dependency licenses absent. |
| Device Connect | `screens/settings/DeviceConnectSettings.kt` | `IosDeviceConnectSettings.kt` | Yes: `deviceconnect/DeviceConnectSettingsPage.kt` | Already shared state/actions and pairing/transfer dialogs. Android settings top bar and sections need comparison; network adapter must stay native. |
| Content | `screens/settings/ContentSettings.kt` | `IosLaneSettings.kt` | No: iOS owns presentation | Only language, country, explicit; no General/App language/Misc/AI filter groups, suggestion source, hide video, age-restricted, top length or hide AI mix. ISO choice dialog is iOS-only. |
| JioSaavn | `screens/settings/JioSettings.kt` | `IosLaneSettings.kt` | No: iOS owns presentation | Two iOS-created rows instead of Android group/header; quality values already portable. |
| Backup / restore | `screens/settings/BackupAndRestore.kt` | `IosBackupPage.kt` | Yes: `settings/BackupAndRestoreSettings.kt` | Shared archive/Drive/folder page, native picker/date/restart/failure/recovery dialogs; optional folder rows are omitted when host unavailable. Portable format differs from Android legacy archive. |
| Modules / module detail | `screens/settings/ModulesSettings.kt` | `IosModulesPage.kt` | Yes: `settings/ModuleDetailsSettings.kt` | Custom 24dp column: enable/import/configure/remove only. Android has flexible header, enable summary/stat cards, Installed/Catalogs tabs, native extensions, ordered modules, catalogs, FAB/add menu and review dialogs. |
| Android Auto | `screens/settings/AndroidAutoSettings.kt` | `IosLaneSettings.kt` | Yes: `settings/AndroidAutoSettings.kt` | Route is redirected to a redesigned System integrations page. Android rows must remain disabled with Not available on iOS; CarPlay is a separate system host. |
| AOD customization | `screens/settings/AodCustomizedScreen.kt` | `IosLaneSettings.kt` | Yes: `settings/AodSettingsRows.kt` | Blank unavailable page, and the entry is filtered; preserve Android row layout with unavailable states. |
| Launcher icon | `screens/settings/IconScreen.kt` | `IosAppearanceEditors.kt` | No: iOS owns presentation | Two text buttons instead of Android current-icon card, search/sort and segmented icon list. Only two alternate icons ship in the iOS host; other choices must be visible/unavailable. |
| Palette / theme creator | `screens/settings/PalettePickerScreen.kt` | `IosAppearanceEditors.kt` | Yes: `settings/ColorPaletteSettings.kt` | Shared palette picker; Theme creator aliases palette, omitting Android creator tools. |
| Custom background | `screens/settings/CustomizeBackground.kt` | `IosCustomBackground.kt` | No: iOS owns presentation | Native file/image choice plus iOS-rendered sliders; Android preview, row order and dialog chrome differ. |
| Hidden playlists | `screens/settings/HiddenPlaylistsScreen.kt` | `IosHiddenPlaylists.kt` | No: iOS owns presentation | iOS list uses text Restore actions; Android visibility icons, title and empty state differ. |
| Export downloads | `screens/settings/ExportDownloadedSongsScreen.kt` | `IosExportSongsPage.kt` | No: iOS owns presentation | Custom file-export selection page. Android tag/artwork/lyrics export options are missing; retain visible unavailable options. |
| Last.fm | `screens/settings/LastFMSettings.kt` | `IosLastFmPage.kt` | Yes: `settings/LastFmSettingsPage.kt` | Shared settings with iOS sign-in cards/status rows; login dialog/row ordering and ListenBrainz availability differ. |
| Last.fm dashboard | `screens/settings/LastFmDashboardScreen.kt` | `IosLastFmDashboard.kt` | Yes: `settings/lastfmdashboard/LastFmDashboardPage.kt` | Already shared dashboard with native date/format callbacks; no device/pixel evidence. |
| Listen Together settings | `screens/settings/ListenTogetherSettings.kt` | `IosListenTogetherPages.kt` | Yes: `settings/ListenTogetherSettings.kt` | Shared state/actions; iOS owns avatars and suggestion picker. Unsupported platform rows must retain Android position. |
| Account | `screens/settings/AccountSettings.kt` | `IosSettings.kt` | Yes: `settings/AccountSettings.kt` | Already shared settings presentation; Android header/icons/group surfaces differ; host services stay in iosMain. Some anchors are filtered by the host. |
| Tidal | `screens/settings/TidalSettings.kt` | `IosSettings.kt` | Yes: `settings/TidalSettings.kt` | Already shared settings presentation; Android header/icons/group surfaces differ; host services stay in iosMain. Some anchors are filtered by the host. |
| Deezer | `screens/settings/DeezerSettings.kt` | `IosSettings.kt` | Yes: `settings/DeezerSettings.kt` | Already shared settings presentation; Android header/icons/group surfaces differ; host services stay in iosMain. Some anchors are filtered by the host. |
| Qobuz | `screens/settings/QobuzSettings.kt` | `IosSettings.kt` | Yes: `settings/QobuzSettings.kt` | Already shared settings presentation; Android header/icons/group surfaces differ; host services stay in iosMain. Some anchors are filtered by the host. |
| Telegram | `screens/settings/TelegramSettings.kt` | `IosSettings.kt` | Yes: `settings/TelegramSettings.kt` | Already shared settings presentation; Android header/icons/group surfaces differ; host services stay in iosMain. Some anchors are filtered by the host. |
| Integrations | `screens/settings/IntegrationScreen.kt` | `IosSettings.kt` | Yes: `settings/IntegrationSettings.kt` | Already shared settings presentation; Android header/icons/group surfaces differ; host services stay in iosMain. Some anchors are filtered by the host. |
| Lyrics providers | `screens/settings/LyricsProvidersSettings.kt` | `IosSettings.kt` | Yes: `settings/LyricsProviderSettings.kt` | Already shared settings presentation; Android header/icons/group surfaces differ; host services stay in iosMain. Some anchors are filtered by the host. |
| Romanisation | `screens/settings/LyricsRomanisationSettings.kt` | `IosSettings.kt` | Yes: `settings/LyricsRomanisationSettings.kt` | Already shared settings presentation; Android header/icons/group surfaces differ; host services stay in iosMain. Some anchors are filtered by the host. |
| AI integration | `screens/settings/AiIntegrationSettings.kt` | `IosSettings.kt` | Yes: `settings/AiIntegrationSettings.kt` | Already shared settings presentation; Android header/icons/group surfaces differ; host services stay in iosMain. Some anchors are filtered by the host. |
| Sources | `screens/settings/SourceSettings.kt` | `IosLaneSettings.kt` | Yes: `settings/SourceSettings.kt` | Shared page with native actions; header/icons/group treatment differs. Internet/Storage/Spotify append iOS explanatory text; platform restrictions must be row descriptions, not replacement layouts. |
| Amazon | `screens/settings/AmazonSettings.kt` | `IosLaneServicePages.kt` | Yes: `settings/AmazonSettings.kt` | Shared page with native actions; header/icons/group treatment differs. Internet/Storage/Spotify append iOS explanatory text; platform restrictions must be row descriptions, not replacement layouts. |
| Apple Music | `screens/settings/AppleMusicSettings.kt` | `IosLaneSettings.kt` | Yes: `settings/AppleMusicSettings.kt` | Shared page with native actions; header/icons/group treatment differs. Internet/Storage/Spotify append iOS explanatory text; platform restrictions must be row descriptions, not replacement layouts. |
| QQ Music | `screens/settings/QqMusicSettings.kt` | `IosLaneServicePages.kt` | Yes: `settings/QqMusicSettings.kt` | Shared page with native actions; header/icons/group treatment differs. Internet/Storage/Spotify append iOS explanatory text; platform restrictions must be row descriptions, not replacement layouts. |
| Spotify streaming | `screens/settings/SpotifyStreamingSettings.kt` | `IosLaneServicePages.kt` | Yes: `settings/SpotifyStreamingSettings.kt` | Shared page with native actions; header/icons/group treatment differs. Internet/Storage/Spotify append iOS explanatory text; platform restrictions must be row descriptions, not replacement layouts. |
| Discord | `screens/settings/DiscordSettings.kt` | `IosLaneServicePages.kt` | Yes: `settings/DiscordSettings.kt` | Shared page with native actions; header/icons/group treatment differs. Internet/Storage/Spotify append iOS explanatory text; platform restrictions must be row descriptions, not replacement layouts. |
| Discord experimental | `screens/settings/DiscordExperimental.kt` | `IosLaneSettings.kt` | Yes: `settings/DiscordExperimentalSettings.kt` | Shared page with native actions; header/icons/group treatment differs. Internet/Storage/Spotify append iOS explanatory text; platform restrictions must be row descriptions, not replacement layouts. |
| Canvas | `screens/settings/CanvasSettings.kt` | `IosLaneServicePages.kt` | Yes: `settings/CanvasSettings.kt` | Shared page with native actions; header/icons/group treatment differs. Internet/Storage/Spotify append iOS explanatory text; platform restrictions must be row descriptions, not replacement layouts. |
| Internet | `screens/settings/InternetSettings.kt` | `IosLaneServicePages.kt` | Yes: `settings/InternetSettings.kt` | Shared page with native actions; header/icons/group treatment differs. Internet/Storage/Spotify append iOS explanatory text; platform restrictions must be row descriptions, not replacement layouts. |
| Storage | `screens/settings/StorageSettings.kt` | `IosLaneServicePages.kt` | Yes: `settings/StorageSettings.kt` | Shared page with native actions; header/icons/group treatment differs. Internet/Storage/Spotify append iOS explanatory text; platform restrictions must be row descriptions, not replacement layouts. |
| Downloads | `screens/settings/DownloadsSettings.kt` | `IosLaneServicePages.kt` | Yes: `settings/DownloadsSettings.kt` | Shared page with native actions; header/icons/group treatment differs. Internet/Storage/Spotify append iOS explanatory text; platform restrictions must be row descriptions, not replacement layouts. |
| PO token | `screens/settings/PoTokenScreen.kt` | `IosLaneServicePages.kt` | Yes: `settings/PoTokenSettings.kt` | Shared page with native actions; header/icons/group treatment differs. Internet/Storage/Spotify append iOS explanatory text; platform restrictions must be row descriptions, not replacement layouts. |
| Changelog | `screens/settings/ChangelogScreen.kt` | `IosLaneServicePages.kt` | Yes: `settings/ChangelogSettings.kt` | Shared page with native actions; header/icons/group treatment differs. Internet/Storage/Spotify append iOS explanatory text; platform restrictions must be row descriptions, not replacement layouts. |
| Navigation bar | `screens/settings/NavigationBarSettings.kt` | `IosLaneSettings.kt` | Yes: `settings/NavigationBarSettings.kt` | Shared page with native actions; header/icons/group treatment differs. Internet/Storage/Spotify append iOS explanatory text; platform restrictions must be row descriptions, not replacement layouts. |
| Liquid glass | `screens/settings/LiquidGlassSettings.kt` | `IosLaneSettings.kt` | Yes: `settings/LiquidGlassSettings.kt` | Shared page with native actions; header/icons/group treatment differs. Internet/Storage/Spotify append iOS explanatory text; platform restrictions must be row descriptions, not replacement layouts. |
| Updates | `screens/settings/UpdateScreen.kt` | `IosSettings.kt` | Yes: `settings/UpdateSettings.kt` | iOS Release handoff column is prepended; unavailable install/notification rows may be hidden; retain Android layout. |
| Diagnostics / crash list / crash detail | `screens/settings/LogcatScreen.kt` | `IosSettings.kt` | Yes: `screens/diagnostics/DiagnosticLogScreen.kt` | Shared diagnostics and crash pages; iOS native export/share/format callbacks. Android log sources differ. |
| Home (classic / Natune / BitChord / Muzo / Spotify / QQ) | `screens/HomeScreen.kt` | `IosCatalogueFeeds.kt` | Yes: `home/HomeScreen.kt` | Shared greeting/title differs from Android chrome; iOS header uses text buttons; shelf title/icon spacing, source chip availability and variant selection need alignment. Desktop has intentional dense Metrolist feed. |
| Library / Mix | `screens/library/LibraryScreen.kt` | `IosSpotifyLibrary.kt` | Yes: `screens/LibraryScreen.kt` | iOS shared page has standalone title and text action toolbar instead of Android account/title toolbar; source chips and sort/filter/list/grid controls differ. Desktop chips-first layout is intentional. |
| Library Songs | `screens/library/LibrarySongsScreen.kt` | `IosCatalogPages.kt` | Yes: `screens/LibraryScreen.kt` | Shared list/grid presentation and host data; Android sort toolbar, count, empty artwork and filter/action icons need alignment. |
| Library Artists | `screens/library/LibraryArtistsScreen.kt` | `IosArtistPages.kt` | Yes: `screens/LibraryScreen.kt` | Shared list/grid presentation and host data; Android sort toolbar, count, empty artwork and filter/action icons need alignment. |
| Library Albums | `screens/library/LibraryAlbumsScreen.kt` | `IosArtistPages.kt` | Yes: `screens/LibraryScreen.kt` | Shared list/grid presentation and host data; Android sort toolbar, count, empty artwork and filter/action icons need alignment. |
| Library Playlists | `screens/library/LibraryPlaylistsScreen.kt` | `IosPlaylistPages.kt` | Yes: `screens/PlaylistCollectionScreen.kt` | Shared list/grid presentation and host data; Android sort toolbar, count, empty artwork and filter/action icons need alignment. |
| Library Playlist tags | `component/TagsManagementDialog.kt` | `IosPlaylistPages.kt` | Yes: `screens/PlaylistTagsScreen.kt` | Shared list/grid presentation and host data; Android sort toolbar, count, empty artwork and filter/action icons need alignment. |
| Search / local search / online search / provider results | `screens/search/SearchScreen.kt` | `IosCatalogPages.kt` | Yes: `screens/SearchScreen.kt` | Shared query field/filter chips; source picker/suggestion leading and delete icons differ; Android search has back/source affordances and local/online tabs. Preserve shared IME/bottom-search behavior and desktop top-bar search. |
| Album detail | `screens/AlbumScreen.kt` | `IosArtistPages.kt` | Yes: `screens/artistalbum/AlbumScreen.kt` | Shared presentation already mounted; Android host toolbar/group/count/action details require alignment. Provider/system capability and runtime parity are distinct from visual parity. |
| Artist detail | `screens/artist/ArtistScreen.kt` | `IosArtistPages.kt` | Yes: `screens/artistalbum/ArtistScreen.kt` | Shared presentation already mounted; Android host toolbar/group/count/action details require alignment. Provider/system capability and runtime parity are distinct from visual parity. |
| Artist songs | `screens/artist/ArtistSongsScreen.kt` | `IosArtistPages.kt` | Yes: `screens/artistalbum/ArtistSongsScreen.kt` | Shared presentation already mounted; Android host toolbar/group/count/action details require alignment. Provider/system capability and runtime parity are distinct from visual parity. |
| Artist albums | `screens/artist/ArtistAlbumsScreen.kt` | `IosArtistPages.kt` | Yes: `screens/artistalbum/ArtistAlbumsScreen.kt` | Shared presentation already mounted; Android host toolbar/group/count/action details require alignment. Provider/system capability and runtime parity are distinct from visual parity. |
| Artist items | `screens/artist/ArtistItemsScreen.kt` | `IosArtistPages.kt` | Yes: `screens/artistalbum/ArtistItemsScreen.kt` | Shared presentation already mounted; Android host toolbar/group/count/action details require alignment. Provider/system capability and runtime parity are distinct from visual parity. |
| Online/local/Spotify playlist detail | `screens/playlist/OnlinePlaylistScreen.kt` | `IosPlaylistPages.kt` | Yes: `screens/DetailScreen.kt` | Shared presentation already mounted; Android host toolbar/group/count/action details require alignment. Provider/system capability and runtime parity are distinct from visual parity. |
| Browse | `screens/YouTubeBrowseScreen.kt` | `IosCatalogPages.kt` | Yes: `screens/DiscoveryScreens.kt` | Shared presentation already mounted; Android host toolbar/group/count/action details require alignment. Provider/system capability and runtime parity are distinct from visual parity. |
| Discovery / moods / charts | `screens/MoodAndGenresScreen.kt` | `IosCatalogueFeeds.kt` | Yes: `screens/DiscoveryScreens.kt` | Shared presentation already mounted; Android host toolbar/group/count/action details require alignment. Provider/system capability and runtime parity are distinct from visual parity. |
| New releases | `screens/NewReleaseScreen.kt` | `IosCatalogueFeeds.kt` | Yes: `screens/NewReleasesScreen.kt` | Shared presentation already mounted; Android host toolbar/group/count/action details require alignment. Provider/system capability and runtime parity are distinct from visual parity. |
| Account browser | `screens/AccountScreen.kt` | `IosFeaturePages.kt` | Yes: `screens/AccountScreen.kt` | Shared presentation already mounted; Android host toolbar/group/count/action details require alignment. Provider/system capability and runtime parity are distinct from visual parity. |
| History | `screens/HistoryScreen.kt` | `IosHistoryPage.kt` | Yes: `screens/HistoryScreen.kt` | Shared presentation already mounted; Android host toolbar/group/count/action details require alignment. Provider/system capability and runtime parity are distinct from visual parity. |
| Local songs | `screens/library/LocalSongScreen.kt` | `IosDownloadsLocalPages.kt` | Yes: `screens/library/LocalSongScreen.kt` | Shared presentation already mounted; Android host toolbar/group/count/action details require alignment. Provider/system capability and runtime parity are distinct from visual parity. |
| Uploaded songs | `screens/library/UploadedSongsScreen.kt` | `IosYouTubeUploads.kt` | Yes: `screens/UploadedSongsScreen.kt` | Shared presentation already mounted; Android host toolbar/group/count/action details require alignment. Provider/system capability and runtime parity are distinct from visual parity. |
| Downloads | `screens/downloads/DownloadLibraryScreen.kt` | `IosDownloadsLocalPages.kt` | Yes: `screens/DownloadLibraryScreen.kt` | Shared presentation already mounted; Android host toolbar/group/count/action details require alignment. Provider/system capability and runtime parity are distinct from visual parity. |
| Statistics | `screens/StatsScreen.kt` | `IosHostBindings.kt` | Yes: `screens/StatisticsScreen.kt` | Shared presentation already mounted; Android host toolbar/group/count/action details require alignment. Provider/system capability and runtime parity are distinct from visual parity. |
| Year in music | `screens/YearInMusicScreen.kt` | `IosHostBindings.kt` | Yes: `screens/YearInMusicScreen.kt` | Shared presentation already mounted; Android host toolbar/group/count/action details require alignment. Provider/system capability and runtime parity are distinct from visual parity. |
| Podcasts / podcast show | `screens/podcast/PodcastScreen.kt` | `IosPodcastPages.kt` | Yes: `screens/PodcastScreen.kt` | Shared presentation already mounted; Android host toolbar/group/count/action details require alignment. Provider/system capability and runtime parity are distinct from visual parity. |
| Listen Together room/chat | `screens/ListenTogetherScreen.kt` | `IosListenTogetherPages.kt` | Yes: `screens/ListenTogetherScreen.kt` | Shared presentation already mounted; Android host toolbar/group/count/action details require alignment. Provider/system capability and runtime parity are distinct from visual parity. |
| Telegram browse/bots/chat | `screens/TelegramBrowseScreen.kt` | `IosTelegramPages.kt` | Yes: `screens/TelegramBrowseScreen.kt` | Shared presentation already mounted; Android host toolbar/group/count/action details require alignment. Provider/system capability and runtime parity are distinct from visual parity. |
| Recognition / result | `screens/musicrecognition/MusicRecognitionScreen.kt` | `IosRecognitionMount.kt` | Yes: `screens/MusicRecognitionScreen.kt` | Shared presentation already mounted; Android host toolbar/group/count/action details require alignment. Provider/system capability and runtime parity are distinct from visual parity. |
| Track / media information | `utils/ShowMediaInfo.kt` | `IosFeaturePages.kt` | Yes: `screens/mediainfo/MediaInfoScreen.kt` | Shared presentation already mounted; Android host toolbar/group/count/action details require alignment. Provider/system capability and runtime parity are distinct from visual parity. |
| News / article | `screens/NewsScreen.kt` | `IosCatalogPages.kt` | Yes: `screens/NewsScreens.kt` | Shared article/news renderer; date/artwork native callbacks; unavailable feed stays explicit. |
| Expanded player (17 saved styles) | `player/Player.kt` | `IosHostBindings.kt` | Yes: `player/BottomSheetPlayer.kt` | Shared style dispatch already exists; player menu/queue/output affordances, drag handle/insets and per-style spacing require Android comparison. Keep desktop permanent player and all style identities. |
| Mini player / bottom tab bar | `player/MiniPlayer.kt` | `IosMountedRoot.kt` | Yes: `player/ArchiveMiniPlayer.kt` | Shared mini player uses generic progress line/control sizes; Android style-specific height, artwork radius and gestures differ. Preserve Apple one-row tab layout and optional compact setting. |
| Fullscreen / inline / compact lyrics | `player/LyricsScreen.kt` | `IosHostBindings.kt` | Yes: `lyrics/LyricsFullscreenRenderer.kt` | Shared timed renderer is required by AGENTS; Android Enhanced animation must stay untouched. Track header, close/menu controls, alignment/spacing and edit/share dialogs differ. |
| Player menu / sleep / tempo / source / queue | `menu/PlayerMenu.kt` | `IosItemMenu.kt` | Yes: `menu/playerqueue/PlayerQueueMenu.kt` | Shared menu exists, but iOS chooses row subsets and adds native overlays; unsupported entries are omitted. Keep Apple default/Material option, same row ordering/icons/dialogs. |
| Lyrics menu / edit / translation / share | `menu/LyricsMenu.kt` | `IosLyricsTools.kt` | Yes: `menu/LyricsMenuEdits.kt` | Shared lyrics tools mounted; iOS native image share. Full Android provider/edit/account action availability differs. |
| Song / collection menus | `menu/SongMenu.kt` | `IosSongMenu.kt` | Yes: `menu/OverflowMenus.kt` | iOS builds its own filtered rows; Android external downloader/Cast actions omitted. Must retain disabled entries with platform reason. |
| Playlist / album / artist menus | `menu/PlaylistMenu.kt` | `IosPlaylistMenu.kt` | Yes: `menu/artistalbum/ArtistAlbumOverflowMenus.kt` | Shared menus partially reused; iOS row subsets and text controls differ; unsupported entries need explicit retained state. |
| Artwork / playlist edit / sort / arrange dialogs | `component/LibraryChipOrderDialog.kt` | `IosArrangeDialogs.kt` | Yes: `screens/customartwork/CustomArtworkEditor.kt` | Arrange dialogs and tag assignment drawn in iosMain; move their presentation to common, inject repository actions/native image pickers. |
| System integration information | `screens/settings/AndroidAutoSettings.kt` | `IosSystemIntegrationsPage.kt` | No: iOS owns presentation | iOS-only informational column for WidgetKit/CarPlay; must not replace Android Auto rows or settings index structure. |
| OAuth / provider / Telegram login | `screens/settings/TelegramLoginScreen.kt` | `IosTelegramLoginPage.kt` | Yes: `settings/ProviderSignInPage.kt` | Shared provider page plus iOS-rendered Telegram steps and OAuth page; OS sheets remain native, app-content form layout belongs in common. |
| Restore startup/recovery | `screens/settings/BackupAndRestore.kt` | `IosRestoreRecoveryScreen.kt` | No: iOS owns presentation | iOS staged restore recovery has native safety constraints; app-content recovery/restart dialogs should be shared, system restart action remains native. |

## Cross-cutting baseline differences

- `SettingsPage` filters `AndroidOnlySettingsAnchors.all` on iOS. This hides
  capability gaps, changes row order and makes saved settings unreachable.
- Shared settings use ungrouped Material `ListItem`s without Android row icons;
  the pinned back control has no page title. Android uses titled settings app
  bars and `PreferenceGroup` surfaces with leading icons.
- Home/Library host actions use text buttons where Android uses icon buttons.
  Shared sort/filter/source controls have different placement and labels.
- iOS-only explanatory blocks prepend/append several otherwise shared pages.
  Platform availability should be attached to the original row.
- Shared resources already include most Android labels and vectors. Missing
  labels/vectors belong in `shared-ui/src/commonMain/composeResources`, never
  in Swift app resources. Common Kotlin must not import Android/JVM APIs.
- The parity report includes feature implementation gaps. A UI change cannot
  certify those APIs work; unavailable actions must remain visible and disabled.

## Acceptance and evidence

For each changed screen compare Android's default layout and preference-dependent
rows, title, leading icon, group boundary, row order, choice labels, dialog
buttons and empty state. Preserve existing saved preference identities and
host callbacks. Unsupported controls must be disabled and say **Not available
on iOS**. Desktop changes must be conditional on the phone presentation.

Only permitted local checks: `python3 scripts/check_repository.py --worktree`
and `python3 -m unittest discover -s iosApp/tests -p 'test_*.py'`. Kotlin/Native,
Android and desktop compilation require CI; device/pixel/physical-audio evidence
is not supplied by this inventory. No local Gradle/JVM/Xcode build or lint.

Implementation updates and residual differences will be recorded below as
changes land; the table above describes the starting revision.

## Shared settings presentation implementation

The phone host now opts into a common segmented settings renderer and 36
Android source references (463 row/group references). Reference titles and
icons are shared Compose resources; vector geometry comes from Android with
OS color references normalized to portable literals. Existing typed settings
rows retain their callbacks and persisted keys. Unbound controls have no writer
or action and show `Not available on iOS`. Conditional bound controls retain
their existing preference-dependent availability.

Content runtime language/country selection and Behavior presentation now live
in commonMain. The Behavior statistics reset uses the existing history owner
and a shared confirmation. Android Auto and AOD routes retain reference rows
instead of a replacement informational/empty page. Developer diagnostics use
the already supplied native slots. The iOS Update handoff block is removed
from the ordinary settings page.

These source references are an implementation aid, not pixel or compilation
evidence. Android helper-drawn/custom controls and host service cards still
need individual presentation ports; native dialogs and unsupported APIs remain
explicit review items. Desktop does not opt into this renderer.

The next port moves Modules summary/tabs/add/menu/remove confirmation,
About loading/dialog state, background editing, hidden-playlist list/actions,
download export selection, backup restart/failure/recovery dialogs and
home/library/artwork/tag arrange dialogs into commonMain. Arrange lists use
Android's numbered rows and drag handles through the same KMP reorderable
dependency. Native bindings retain file/image IO, observed output facts,
package validation and process/restore operations. Module catalog/native
extension operations without an iOS owner stay visible and unavailable.

Launcher selection uses an iOS-source implementation of Android's current-icon
card, search/sort, segmented rows, preview sizes and author attribution, rendered
by the shared Compose iOS settings route. All 20 pinned IconPack choices are
included. `LauncherIconScreen.kt`, `LauncherIconSettings.kt`, `SharedLauncherIconCatalog.kt`,
its previews and catalog labels live in `shared-ui/src/iosMain`, not in Android packaging;
the shared attribution and common license notice remain.
`scripts/sync_launcher_icon_resources.py` reproduces those iOS resources and native
asset catalogs. UIKit applies the alternate icon after its completion callback
succeeds; a refused platform capability disables the same row with the iOS
availability description. Native catalogs retain the legacy `ArchiveTuneAndroid`
alias. The iOS asset-catalog compile and device icon switching still require
CI/device evidence.

Last.fm authorization prompts, the backup date picker and startup recovery
surface now render in commonMain as well. The settings index retains Modules
and Liquid glass in Android's Player/content group. All new strings are in
shared Compose resources. Android UI and playback sources are unchanged.

## Browsing, player and lyrics implementation

- iOS opts into Android phone presentation through one composition local.
  Desktop keeps its existing Metrolist PC branches, including narrow windows.
- Phone song rows use Android's 72dp height, 56dp sleeves, 10dp radius,
  semibold one-line titles, duration subtitles and icon overflow actions.
- iOS Home binds the existing shared BitChord/Android feed to real remote
  shelves, recent listens, speed-dial pins and linked account playlists.
  Greeting and category chips scroll with the feed. Shelf playback still uses
  the existing complete-list/queue owner; radio uses its existing radio path.
- Library exposes the existing persisted sort/filter keys and playlist grid
  setting; artists/albums use the phone grid. The existing owner re-reads the
  lists after edits. Search embeds the existing Explore/Suggestions owner and
  uses the inline microphone/source field and segmented tabs.
- Mini player uses Android's 70dp/64dp heights and swipe threshold/velocity
  formula, with a real monotonic clock, current-occurrence and direction guards.
- Player More opens the shared Apple/Material player menu. Lyrics More opens
  its own shared action list, with the Apple popup anchored to the clicked
  Compose control. Source, tempo, sleep, playlist selection, lyric editing,
  timing and translation dialogs render in commonMain. The existing shared
  timed-lyrics renderer and all saved player-style meanings remain intact.
- Unsupported Cast/AOD/download/source actions retain disabled controls.
  Generic song actions retain their rows rather than being filtered. Native
  callbacks capture the opened occurrence for async operations. Lyric edits
  keep revision/current-track checks and report a rejected write.

## Remaining differences and limits

**This branch does not establish full pixel or behavior parity.**

| Area | Remaining difference / reason |
| --- | --- |
| Android helper-drawn settings controls | The source reference records static row/group order and binds existing typed controls. Helper-only/custom layouts, inline numeric controls and some provider/account cards still use existing shared implementations; they require individual comparison/ports. |
| Home recommendation data | Forgotten favorites and similar-recommendation owners are not fully bound on iOS. Recents, hero picks and keep-listening currently derive from local recent listens. Remote quick-picks/live grouping follows the supplied shelf metadata. |
| Library variants/detail chrome | Phone sort/filter/grid controls are bound; Android artist/album spotlight headers and every Apple library/detail/menu permutation are not fully reproduced. |
| Lyrics provider search / ordinary translation | Provider-search and the Java-only ordinary translator have no iOS binding. Their same menu/dialog choices remain visible and unavailable; configured shared AI translation/romanization and persistent edits are wired. |
| Module catalogs and native extensions | Installed API-v1 packages work through the existing host. Catalog import/update/check and native binary-extension operations have no iOS UI owner yet, and retain unavailable controls. This is current implementation availability, not a claim that all catalog operations are inherently forbidden by iOS. |
| Native output / restore facts | iOS output-rate/observed-format rows and staged-restore quit/recovery behavior remain because they describe existing native APIs. They render in shared UI, but differ from Android system facts. |
| Platform mechanisms | Android Auto, AOD, Media3 offload/PIP/USB-exclusive and Android screenshot/system controls cannot use their Android APIs on iOS; reference controls remain disabled with the shared availability description. UIKit pickers, AirPlay, authorization and alternate-icon confirmation remain system surfaces. |
| Compilation and runtime | No local Gradle/JVM/Xcode build, lint or JVM test ran. Exact-head Android/desktop/Kotlin-Native CI, full unsigned iOS app linkage, device launch, Dynamic Type extremes, gestures and pixel checks remain unverified. |

Permitted checks after this implementation: repository checker **0 findings**;
iOS Python contracts **38 tests passed**. Additional bounded source reads
verified no changed Android sources, no Android/JVM resource imports in the
changed common code, and no unresolved literal shared-resource references.
No PR was opened and no CI workflow was dispatched.

## Render audit status

Fixes committed on `fix/ios-desktop-ui-render-audit`, one commit per item:

- `eda1b388` gives the queue lock vector's three stroke paths a transparent fill, which the vector test requires.
- `c6d3928b` closes the phone queue to the artwork on a tap or downward drag of its drag strip, and on a downward drag of non-scrolling headers.
- `ec507a10` removes the edge fade from phone setting descriptions. A static description ends in an ellipsis, as Android's marquee does.
- `21fd177b` centres the lossless badge between the timestamps on the fullscreen lyrics row and the BitChord seek row, with Android's wave glyph and a shimmer on lossless streams. The wave is drawn in code (`QualityBadge.kt`), not from a shared resource.
- `ca9142e5` draws the phone mini player as a raised card with the pill's hairline border, above the nav pill.
- `ce3b9011` shows the same centred badge on the SimpMusic seek row, which Android shows when the codec setting is on.

Earlier commits on this branch cover the Cinematic phone header and footer, the artwork progress ring, the phone queue sheet chrome and current rows, the fullscreen lyrics backdrop and transport, and the settings heading and root groups.

The native iOS render at `2008b225` (run 42949329927) shows the centred badge on the fullscreen lyrics and BitChord rows, and the raised mini card. These are native Skia frames, not simulator screenshots. No Android frame with the same data exists yet.

Still open:

- Mini player: the floating card's fill and border are ported in code. The mini player and the nav pill are now two floating cards, as on Android. No render compares them yet.
- Lossless badge: the fullscreen lyrics, BitChord, and SimpMusic rows use the centred badge. The lyrics row shows it whenever a format is known, as Android's lyrics screen does. The BitChord and SimpMusic rows follow the codec setting, as Android does. Desktop layout tests check the centring. The text is the shared format line, and Android uses a shorter stream label, so the text may differ. No render compares it.
- Phone player format line: the other shared styles keep their own format chip. Android's player uses `LosslessOrStats` only on the BitChord, SimpMusic, and lyrics screens, so the chips on the other styles were not compared with an Android screen.
- Queue sheet: the header, source line, counts, four actions, connected row, and Continue Playing row are in `PhoneQueueHeader.kt`. This was checked in source only, and no render compares it with Android.
- Fullscreen lyrics glyphs and footer pill: the play, pause, and volume icons come from the shared glyph set, not from Android's lyrics drawables (`player_play`, `player_pause`, `player_volume_min`, `player_volume_up`). The shared footer pill shows the lyrics state. Neither is compared in a render. The volume track is 8 dp on both platforms, so it is unchanged.
- Home header: the copy, sizes, weights, and gutter match Android's `HomeWelcomeHeader`, so the header itself is unchanged. The iOS home builds only the BitChord page (`IosCatalogueFeeds.kt`), which draws this header. Android's default home style is the classic page, which has no hero header. Changing the iOS page is a route decision, so it is not made here.
- Settings titles: Android requests Bold for every preference title. Its bold may be synthetic, because the Poppins asset is declared as Regular only. The shared titles keep the real Bold face. No render compares the two.
- SimpMusic style layout: at 393 × 852 pt the transport row was cut at the bottom edge, and the Lyrics, Queue, Sleep, and More row sat below the frame. The cut also appears at `e699cb54`, so the lossless badge did not cause it. The phone sleeve now gives up height to the rows below it, and the SimpMusic-local collapse is removed, so the shared close control is the one collapse, as in Android's top bar. `SimpMusicPortraitFitTest` checks the rows at 393 × 852 and 360 × 640; the desktop keeps its full-width sleeve.

Not verified:

- UIKit safe areas, gestures, lifecycle, and Dynamic Type. The phone scenes inject padding and do not mount the iOS window.
- Loaded Android screens with the same data. The Android CI captures run in airplane mode, so Home, Search, and Library show empty or error states.
- Matched palettes and artwork.
- Desktop suites for the queue, settings, player styles, and the shell were run locally. The full Gradle suite, the Android build, and the iOS compile were not run.
