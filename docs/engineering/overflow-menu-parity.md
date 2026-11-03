# Overflow menu parity (non-player surfaces)

Scope: the Android app's screen and item overflow menus outside the player — library, search,
album, artist, playlist, history and downloads rows — and their owners on the desktop and iOS
hosts. In-player queue menus and backup are owned by the player slice; settings routes by the
settings slice.

The original (`NatuneGroup/ArchiveTune` `dev`, `app/.../ui/menu/`) has 30 menus / 219 actions.
This table maps each original menu to the shared-ui builder that replaces it and to the host
binding that actually runs each action. A row is only offered where a real owner exists; an
unbound feature stays off the menu rather than showing a dead item on iOS, while the desktop
menu keeps the row and reports a resource-backed reason.

## Shared builders (shared-ui/src/commonMain/.../menu/)

| Original menu | Shared builder / composable | Actions it emits |
|---|---|---|
| `SongMenu.kt` + `YouTubeSongMenu.kt` | `songOverflowActions` + `songLibraryAndDownloadActions(facts, host)` + `SongOverflowMenu` (appends `add_playlist` + picker) | play_next, add_queue, like, view_artist, share, copy_link, save_canvas; start_radio, library (add/remove), speed_dial, remove_history, remove_cache, download/downloading/remove_download, export_download, open_with_downloader, view_album, sleep_timer, recommendation block/unblock, download_cover, refetch, track_info, add_playlist |
| `AlbumMenu.kt` + `YouTubeAlbumMenu.kt` | `albumOverflowActions` + `AlbumOverflowMenu` | play, shuffle, share, play_next, add_queue, like, download/downloading/remove_download, speed_dial, view_artist |
| `ArtistMenu.kt` + `YouTubeArtistMenu.kt` | artist section of `AlbumArtistMenus.kt` | play, radio, subscribe/unsubscribe, speed_dial, hide_from_home, share |
| `PlaylistMenu.kt` + `YouTubePlaylistMenu.kt` | `playlistMenuActions` + `PlaylistOverflowMenu`, `playlistOverflowActions` | play, shuffle, play_next, add_queue, start_radio, rename, change_cover, remove_cover, manage_tags, telegram_add_songs, telegram_refresh, sync_playlist, share (browseId/shareLink) or export (local playlist — BUG-003) |
| `SelectionSongsMenu.kt` | `selectionSongOverflowActions` + `selectionSongLibraryAndDownloadActions` | play, shuffle, add_queue, like_all/dislike_all, library_all, remove_queue, remove_playlist; download_missing, remove_cache_all, remove_history_all, remove_downloads |
| `CustomArtworkMenuSection.kt` | `customArtworkOverflowActions` + `customArtworkEntryAction` | set_cover, set_canvas, remove_cover, remove_canvas; custom_artwork (open editor) |
| `LyricsMenu.kt` | `lyricsOverflowActions` | translate, ai_translate, ai_romanize, cancel_translation, undo_translation |
| `AddToPlaylistDialog*.kt` | `SongOverflowMenu`'s editable-playlists picker | add to playlist (add-only on hosts without remove permission) |
| Share destinations | `MenuShareHandler` (`MenuSharing` impl per host) | song (YouTube URL, local file share, telegram message link — BUG-002), playlist, artist, copy_link |
| Collection row menus | `collectionQueueActions`, `collectionShareAction`, `CollectionOverflowMenu`, `OccurrenceOverflowMenu` | play, shuffle, play_next, add_queue, share |
| Player queue / in-player | `playerQueueSongRows`, `playerQueueArtworkRows`, `SharedPlayerQueueMenu` | owned by the player slice, not this table |
| `SaveCanvasDialog.kt` | `rememberCanvasSaveMenuState` + `CanvasSourcePicker` | canvas source pick (gallery/video); mounted by the host next to the menu |
| `EqualizerDialog`, `AppleMusicSleepTimerSheet` | host-owned sheets opened via `SongMenuHost.openSleepTimer` | desktop mounts `DesktopSleepTimerSheetState`, iOS mounts `IosSleepTimerOverlay` over `IosSessionGraph.sleepTimer` |
| `CastRoutePicker*` | `castMenuAction` + `CastRoutePickerSheet` | cast device pick; not offered on desktop/iOS row menus |

## Host bindings

### Desktop (`desktopApp/src/jvmMain/.../DesktopItemMenu.kt`)

`DesktopItemMenuState.open(occurrence, playlistId)` is wired from the row "more" affordance and
the playlist page's item overflow (`DesktopHostBindings`, `DesktopLibraryPages` — local playlist
rows pass their `playlistId` so `remove_playlist` is offered). SONG/EPISODE rows mount
`SongOverflowMenu`; ARTIST/ALBUM/PLAYLIST rows mount `DesktopCollectionRowMenu`. The desktop
host does not filter the emitted rows: every shared row stays on the menu and a row whose owner
is absent fails with a named reason (`state.unavailable("content_menu_*")` surfaces the notice
instead of pretending to work).

Bound song rows with a real owner: play_next, add_queue (fresh occurrence per row), like,
view_artist (artist picker dialog, or the podcast show for episodes), share, copy_link (catalog
URL to the clipboard — YouTube watch/channel/playlist/browse links, Spotify open links; a
Telegram or unsupported id reports the share notice), add_playlist (editable-playlists picker),
library add/remove (`graph.library.saveItems`/`removeItems`), start_radio (YouTube song via
`graph.radio`), speed_dial (`SpeedDialSongIdsKey`, YouTube song, 25-pin cap), remove_history
(history rows via `pageOwners.history`), download/downloading/remove_download and
export_download (completed files export through the save dialog), open_downloader (only while
`ExternalDownloaderEnabledKey` is set; then reports the platform reason), view_album (when the
item carries an album id; podcast show for episodes), sleep_timer (`DesktopSleepTimerSheetState`),
recommendation_block (`BlockedSongIdsKey`, YouTube song — the portable counterpart of the
baseline `song.blockedAt` flag), download_cover (PNG save dialog), refetch (YouTube
`YouTube.queue` re-read over the stored row), track_info, media_info, remove_playlist
(local-playlist rows), custom_artwork.

Emitted but honestly unavailable on desktop: save_canvas (mounted row, reports
`content_menu_canvas_export_unsupported`), remove_cache (only when the opened occurrence came
from the `cache:` source, which no producer emits), local-audio share (desktop reveals the file
in the manager instead, when a local path resolves).

Collection rows (`DesktopCollectionRowMenu`): open_item (the content route) and share (the
catalog URL above; local items reveal their file, unresolvable ids report the notice).

### iOS (`shared-ui/src/iosMain/.../IosSongMenu.kt`)

`IosItemMenuState.open(occurrence)` is wired from row "more" callbacks in `IosHostBindings` and
dismisses on route change (`IosItemMenuDialog`). SONG/EPISODE rows mount `IosSongMenu`;
collection rows mount `IosCollectionRowMenu`. `iosBoundSongRows` filters the emitted rows to
the owners this host connects, so an unbound feature never appears.

Bound song rows: play_next, add_queue (fresh occurrence per row), like, add_playlist
(editable-playlists picker), custom_artwork, track_info, media_info, sleep_timer
(`IosSleepTimerOverlay` over `graph.sleepTimer`), tempo_pitch (`IosTempoPitchOverlay`) —
always; share (link share through the system sheet; Telegram tracks resolve the `t.me` message
link through the signed-in client's `messageLink` query, no client means no link), library
add/remove (`graph.library.saveItems`/`removeItems`), speed_dial (`SpeedDialSongIdsKey`,
25-pin cap), download_cover (`IosArtworkLoader` decode → PNG export through the document
picker), refetch (watch-next re-read over the stored row) — non-local only; remove_history
(history rows via `pageOwners.history`); view_album (when the item carries an album id);
view_artist (when a provider artist credit resolves); download/downloading/remove_download and
export_download (document-picker export of the committed file, extension sniffed from the
stored bytes) — providers `IosDownloadsOwner` cannot store (encrypted Deezer/Apple,
streaming-only Spotify, local files, Telegram audio) emit no download row at all; start_radio
and recommendation_block — YouTube songs only (`recommendation_block` commits
`BlockedSongIdsKey` through `toggleBlockedSongRef`, the same persisted preference the desktop
host writes); remove_playlist (local-playlist rows).

Filtered on iOS, intentionally absent: copy_link (the system share sheet covers the link rows),
remove_cache (no cache-list producer emits `cache:` occurrences), open_with_downloader
(Android-only plugin), save_canvas (no canvas-save mount on this host).

Collection rows: `share` for UC-id artists and YouTube playlists only; everything else reports
the notice rather than listing a dead row.

The block list is a `blockedSongIds` string preference (`BlockedSongRef` `provider:id` entries,
512 cap) in shared-domain settings, so it exports with a SETTINGS backup and does not need a
Room schema bump. The shared home feeds do not consult it yet: on Android the flag feeds
`filterBlockedSongs` in `HomeViewModel`; the shared feed owners have no song-filter seam today
(the artist-block flow filters library lists and the queue, not feeds).

## Bug regressions covered by `desktopTest` `MenuRegressionsTest`

- BUG-001 (multi-artist split): `splitArtistChoices` keeps each artist's original channel
  id/navigation target; a split display name never becomes a new identity.
- BUG-002 (Telegram share): `MenuShareHandler.song` resolves `telegram://` ids only through
  `telegramMessageLink` and never builds a YouTube URL for them.
- BUG-003 (local playlist share): `playlistOverflowActions` emits the export row (real file
  exporter) when `browseId`/`shareLink` are both absent — never a `list=null` share.
