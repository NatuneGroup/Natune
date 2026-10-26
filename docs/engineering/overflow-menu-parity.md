# Overflow menu parity (non-player surfaces)

Scope: the Android app's screen and item overflow menus outside the player — library, search,
album, artist, playlist, history and downloads rows — and their owners on the desktop and iOS
hosts. In-player queue menus and backup are owned by the player slice; settings routes by the
settings slice.

The original (`NatuneGroup/ArchiveTune` `dev`, `app/.../ui/menu/`) has 30 menus / 219 actions.
This table maps each original menu to the shared-ui builder that replaces it and to the host
binding that actually runs each action. A row is only offered where a real owner exists; an
unbound feature stays off the menu rather than showing a dead item.

## Shared builders (shared-ui/src/commonMain/.../menu/)

| Original menu | Shared builder / composable | Actions it emits |
|---|---|---|
| `SongMenu.kt` + `YouTubeSongMenu.kt` | `songOverflowActions` + `songLibraryAndDownloadActions(facts, host)` + `SongOverflowMenu` (appends `add_playlist` + picker) | play_next, add_queue, like, view_artist, share, save_canvas; start_radio, library (add/remove), speed_dial, remove_history, remove_cache, download/downloading/remove_download, export_download, open_with_downloader, view_album, sleep_timer, recommendation block/unblock, download_cover, refetch, track_info, add_playlist |
| `AlbumMenu.kt` + `YouTubeAlbumMenu.kt` | `albumOverflowActions` + `AlbumOverflowMenu` | play, shuffle, share, play_next, add_queue, like, download/downloading/remove_download, speed_dial, view_artist |
| `ArtistMenu.kt` + `YouTubeArtistMenu.kt` | artist section of `AlbumArtistMenus.kt` | play, radio, subscribe/unsubscribe, speed_dial, hide_from_home, share |
| `PlaylistMenu.kt` + `YouTubePlaylistMenu.kt` | `playlistMenuActions` + `PlaylistOverflowMenu`, `playlistOverflowActions` | play, shuffle, play_next, add_queue, start_radio, rename, change_cover, remove_cover, manage_tags, telegram_add_songs, telegram_refresh, sync_playlist, share (browseId/shareLink) or export (local playlist — BUG-003) |
| `SelectionSongsMenu.kt` | `selectionSongOverflowActions` + `selectionSongLibraryAndDownloadActions` | play, shuffle, add_queue, like_all/dislike_all, remove_queue, remove_playlist; download_missing, remove_cache_all, remove_history_all |
| `CustomArtworkMenuSection.kt` | `customArtworkOverflowActions` + `customArtworkEntryAction` | set_cover, set_canvas, remove_cover, remove_canvas; custom_artwork (open editor) |
| `LyricsMenu.kt` | `lyricsOverflowActions` | translate, ai_translate, ai_romanize, cancel_translation, undo_translation |
| `AddToPlaylistDialog*.kt` | `SongOverflowMenu`'s editable-playlists picker | add to playlist (add-only on hosts without remove permission) |
| Share destinations | `MenuShareHandler` (`MenuSharing` impl per host) | song (YouTube URL, local file share, telegram message link — BUG-002), playlist, artist |
| Collection row menus | `collectionQueueActions`, `collectionShareAction`, `CollectionOverflowMenu`, `OccurrenceOverflowMenu` | play, shuffle, play_next, add_queue, share |
| Player queue / in-player | `playerQueueSongRows`, `playerQueueArtworkRows`, `SharedPlayerQueueMenu` | owned by the player slice, not this table |
| `SaveCanvasDialog.kt` | `rememberCanvasSaveMenuState` + `CanvasSourcePicker` | canvas source pick (gallery/video); mounted by the host next to the menu |
| `EqualizerDialog`, `AppleMusicSleepTimerSheet` | host-owned sheets opened via `SongMenuHost.openSleepTimer` | desktop mounts the sleep sheet; iOS does not offer the row |
| `CastRoutePicker*` | `castMenuAction` + `CastRoutePickerSheet` | cast device pick; not offered on desktop/iOS row menus |

## Host bindings

### Desktop (`desktopApp/src/jvmMain/.../DesktopItemMenu.kt`)

`DesktopItemMenuState.open(occurrence, playlistId)` is wired from the row "more" affordance and
the playlist page's item overflow (`DesktopHostBindings`, `DesktopLibraryPages` — local playlist
rows pass their `playlistId` so `remove_playlist` is offered). SONG/EPISODE rows mount
`SongOverflowMenu`; ARTIST/ALBUM/PLAYLIST rows mount `DesktopCollectionRowMenu`.

Bound song rows: play_next, add_queue, like, add_playlist, custom_artwork, sleep_timer,
download_cover — plus conditionally: share (non-local, non-telegram link share to clipboard),
library (add only, when the song is absent and not local), remove_history (on history rows),
view_album (when the album id resolves), remove_playlist (on local-playlist rows).

Unbound on desktop, intentionally absent: start_radio, speed_dial, remove_cache, download
states, open_with_downloader, recommendation block, refetch, track_info, view_artist navigation,
telegram share, local-file share. Unavailable host calls route to `refusedAsUnavailable`, which
surfaces the host notice instead of pretending to work.

Collection rows: `share` only (artist UC-ids and YouTube playlists build real links; everything
else is omitted and the menu reports failure instead of listing a dead row).

### iOS (`shared-ui/src/iosMain/.../IosSongMenu.kt`)

`IosSongMenuState.open(occurrence)` is wired from row "more" callbacks in `IosHostBindings` and
dismisses on route change (`IosSongMenuOverlay`). SONG/EPISODE rows mount `IosSongMenu`;
collection rows mount `IosCollectionRowMenu`.

Bound song rows: play_next, add_queue, like, add_playlist — plus conditionally: share (same
link-share rule as desktop), library (add only), remove_history (history rows), remove_playlist
(local-playlist rows).

Unbound on iOS, intentionally absent: everything in the desktop list plus view_album and
sleep_timer (no album navigation or sleep sheet on this host), custom_artwork, download_cover.
`IosSongMenuHost.setInLibrary` writes via `RoomLibraryRepository.saveItems` (add path only —
no remove-from-library mutation exists); `removeFromHistory` goes through
`RoomListeningHistory.remove(HistoryOrigin.LOCAL, …)`. Every other host call reports the
`ios_settings_feature_unavailable` / `ios_menu_action_failed` notice.

Collection rows: `share` for UC-id artists and YouTube playlists, same as desktop.

## Bug regressions covered by `desktopTest` `MenuRegressionsTest`

- BUG-001 (multi-artist split): `splitArtistChoices` keeps each artist's original channel
  id/navigation target; a split display name never becomes a new identity.
- BUG-002 (Telegram share): `MenuShareHandler.song` resolves `telegram://` ids only through
  `telegramMessageLink` and never builds a YouTube URL for them.
- BUG-003 (local playlist share): `playlistOverflowActions` emits the export row (real file
  exporter) when `browseId`/`shareLink` are both absent — never a `list=null` share.
