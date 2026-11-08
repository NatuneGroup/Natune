# Desktop settings and cross-device sync — comparison and fixes

Reference material (read-only): `/data/workspace/re-out` (decompiled Metrolist desktop
14.0.0-rc8 — `desktopApp`, `sharedSub`, `ui-shell`, `itx-v0.11.2`) and
`/data/workspace/metrolist-src`. The decompiled Metrolist tree is a **behaviour reference
only**; no GPL source is copied into this repository. This document records the audit and
the changes on `fix/r3-desktop-settings-sync`.

## Scope and method

Two goals:

1. Compare every desktop settings section/option against Metrolist desktop, gate the ones
   that cannot do anything on a desktop window, and wire any remaining desktop option to a
   real reader.
2. Compare Metrolist's cross-device sync with our encrypted LAN "Device Connect", and close
   the gaps so desktop ↔ Android ↔ iOS get equivalent behaviour.

Auditing a "dead" option means: for each preference key a desktop page writes, grep for a
reader in `desktopApp/src/jvmMain`, `shared-ui/src/commonMain` (host/player/screens),
`shared-domain/src/commonMain` and `shared-data/src/commonMain`. A key whose only readers
are the settings row itself, `PersistedPreferenceKeys/Registry`, or the Android `:app`
module has no desktop behaviour. Those are the rows to gate. Stored values are never
removed — mobile hosts and backups still read them.

## Part 1 — desktop settings

### Metrolist desktop settings structure

Metrolist desktop uses one `SettingsOverlay` with a searchable, grouped registry
(`com.metrolist.shared.ui.settings.*`). `SettingsPageId` names ~34 pages; the desktop
overlay groups them as Playback, Interface, Integrations, Privacy/security, Storage/data,
Application/about (`SettingsOrganizationKt`, `MetrolistSettingsRegistryKt`,
`IntegrationsOverlayKt`). It is a **shared registry**: rows that are meaningless on a given
form factor are not registered there (e.g. Android Auto, phone navigation-bar styles have no
desktop surface). `DEVICE_CONNECT` is a first-class page; the integrator
(`DeviceConnectSettingsKt`) builds it from `DeviceConnectManager.State`. There is no
separate "phone settings list" on desktop — the same grouped overlay both lists and opens
pages.

### Our desktop settings structure

We mount the same grouped overlay (`desktopSettingsNavRows` in
`shared-ui/.../settings/DesktopSettingsNav.kt`): six headers in Metrolist order, each entry
a page this host mounts. Command rows (integrations overlay, equalizer, shortcuts, and now
Connected devices) carry a route so the two-pane navigation can list/search them, but the
window catches them and opens a sheet/overlay instead of replacing the page.

**Most of the mobile-only gating already landed in PR #83 (`08282d8f`, "post-4pm").**
`PlaybackSettings` (`desktop = true`) drops PIP, wakelock, stop-on-task-clear, Bluetooth
autostart, offload/USB/bit-perfect Android audio, swipe-to-open-queue and history duration;
`AppearanceSettingsRows` drops phone navigation styles (`navigation_bar_settings` and the
whole `NavigationBarSettings` page), search-bar position, tablet mode, home-screen style,
system status bar/splash/UI-scale, phone palette pickers; `PlayerAppearanceSettings` drops
AOD/compact mini-player/mobile-data artwork; `desktopPrivacySettingsRows` omits metered
low-data mode. `AndroidOnlySettingsAnchors` (applied on iOS via
`LocalUnsupportedSettingsAnchors`) inventories the same class of rows. `DesktopSettingsNavTest`
and `AndroidOnlyRowsTest` pin these gates. The desktop-specific `Content`/`JioSaavn`/
`AppleMusic` pages live in `shared-ui/src/desktopMain` and only expose rows whose readers
are the desktop resolver/content policy.

### Residual dead options found and gated (this branch)

The audit found four rows still offered on desktop whose keys have no desktop reader. All
were gated with a per-platform `desktop` flag on the shared row builder, keeping the phone
default unchanged.

| Anchor | Key | Only reader | Fix |
|---|---|---|---|
| `show_tags_in_library` | `ShowTagsInLibraryKey` | Android `LibraryScreen` (no shared/desktop reader) | `appearanceExtrasSettingsRows(desktop = true)` omits it; `DesktopGeneralSettings` passes the flag |
| `auto_download_like` | `AutoDownloadOnLikeKey` | Android `MusicService` | `downloadsSettingsRows(desktop = true)` omits it |
| `external_downloader` | `ExternalDownloaderEnabledKey` | Android menus/service; desktop `openWithExternalDownloader` returns `unavailable` | omitted on desktop |
| `external_downloader_package` | `ExternalDownloaderPackageKey` | Android menus only | omitted on desktop |

The remaining Downloads rows (`clear_all_downloads`, `export_downloaded_songs`,
`download_source`) are real on desktop (`clearDownloads`, the export page, the reorder
dialog) and stay. `DownloadsSettings` was refactored to a pure `downloadsSettingsRows`
builder so the gate is unit-testable without a Compose runtime.

**Known, deliberately left:** `DesktopItemMenu` still shows an "Open with external
downloader" row when `ExternalDownloaderEnabledKey` is true (e.g. restored from an Android
backup), which reports "unavailable" when tapped. That is a menu action with explicit
unavailability feedback, not a settings option; the player menu already forces
`externalDownloaderEnabled = false`. Gating the settings toggle is sufficient for this
task; changing that menu belongs to a separate UX decision.

### Verification

Every remaining desktop option was checked against a reader. Representative wired keys:
`PauseOnDeviceMuteKey`/`DeviceMutePlaybackRecoveryVolumeKey` → `DesktopOutputMutePolicy`;
`SeekExtraSeconds` → `DesktopPlayerControls`; `AudioQualityKey` → `DesktopYouTubeResolver`
/ `HostSourceSelection`; `EnableVideoPlaybackKey` → `DesktopVideoArtwork`;
`CanvasResolverEndpointsKey` → `DesktopCanvasOwner`; `HideLikedSongsCardKey` etc. →
shared `HostLibraryPages`; `ShowHomeCategoryChipsKey` → `DesktopCataloguePages`;
privacy keys → `ListenHistoryDriver`. The two exceptions that are actions, not keys
(equalizer, shortcut table) are command rows backed by host sheets. No desktop page now
writes a key that nothing reads.

## Part 2 — cross-device sync

### What Metrolist syncs

Metrolist's cross-device feature **is Device Connect** (`com.metrolist.shared.deviceconnect.*`
in the decompiled `shared-sub` jar) — an encrypted LAN WebSocket transport with mDNS/NSD
discovery, SAS/confirmation-code pairing, a persisted pair store, replay-protected sealed
frames and a heartbeat. `DcSyncMessageType` is
`ATTACH, TRANSFER, PULL, STATE, UPDATE, COMMAND, RELEASE, ERROR`. `DcPlaybackSnapshot`
carries the queue (`MusicQueueSnapshot`), current track, position, playing state, speed/
pitch, repeat and volume; `DcPlaybackAction` covers PLAY/PAUSE/SEEK/NEXT/PREVIOUS/SELECT/
REPLACE_QUEUE/ADD/PLAY_NEXT/REMOVE/MOVE/REORDER/CLEAR/SHUFFLE/REPEAT/SPEED/PITCH/VOLUME/
RESTORE_QUEUE/REPLACE_CURRENT. In other words it syncs **live playback state and hands the
queue off** between devices, and lets one device **control** the device that is playing.

It does **not** sync the library or likes over the LAN. Likes, playlists and subscriptions
come from the signed-in YouTube/YouTube-Music account independently on each device
(account-based sync), or from backup/restore — never from the Device Connect wire. Expecting
Device Connect to mirror a library is a category error; the feature is playback continuity.

### Our Device Connect

`shared-domain/.../deviceconnect` defines the same v1 protocol
(`DeviceMessageType` HELLO…PLAYBACK_CHUNK, `DeviceSyncMessageType` ATTACH…ERROR,
`DevicePlaybackAction`, `DevicePlaybackSnapshot`) with the same shape of fields (queue
entries, play order, shuffle, repeat, position, playing, speed, volume, revisions).
`shared-data/.../deviceconnect` implements the encrypted transport, server, client, state
machine and `QueueDevicePlaybackPort`; the latter applies capture/apply/resume/command over
the host's ordinary `QueueController` and player — no second player or mirror queue. Every
host (`:app`, `:desktopApp`, `iosApp`) builds a `DeviceConnectOwner` over its real queue and
an optional platform volume/repeat bridge. On desktop, `DesktopDeviceConnect` supplies
`QueueDevicePlaybackPort` wired to `DesktopSessionGraph.queue` and the engine's output
volume, plus `JvmDeviceDiscovery` and `SecureDevicePairRepository`.

So the transport, pairing, playback handoff and remote-control **logic is already equivalent
and shared**, and device-connect/auth fixes are already merged (`c3898311` … `0093a47c`).

### The real gap: desktop could not reach it

- `DeviceConnectSettingsPage`, `ConnectedDevicesSheet`, `DevicePairingDialog` and
  `DeviceTransferDialog` are all implemented in `shared-ui/.../deviceconnect` and are fully
  wired with handoff ("Play on X" / "Play here") and controller transport buttons
  (play/pause/next/previous, pull-back, release).
- Android opens the sheet from the player menu (`DeviceConnectPlayerMenuAction`); iOS mounts
  a global host (`IosDeviceConnectHost` in `IosMountedRoot`).
- On **desktop** the sheet hook (`DesktopConnectedDevicesSheet`) had **zero callers**,
  `DesktopDeviceConnect.openConnectedDevices()` had **no caller**, and the sheet was **not
  mounted anywhere**. Device Connect was reachable only as a nested row inside the
  integrations overlay, and even then only the management settings page — an incoming
  pairing/hand-off prompt had nowhere to appear, and there was no player-style "Connected
  devices" entry. `cast()` is deliberately `unsupported` on desktop, so the cast button was
  not an alternative.

### Changes (this branch)

- Discoverable from desktop settings: added two first-class entries under the Integrations
  group of `desktopSettingsNavRows`:
  - **Connected devices** — a command row (`DesktopConnectedDevicesRoute`) that opens the
    handoff/remote-control sheet over the current page; listed and searchable in the
    two-pane navigation and present in the single-column list.
  - **Device Connect** — a plain top-level navigation entry to
    `settings/integrations/device_connect` (enable, rename, pair, connect-by-IP, forget).
  - `DesktopSettingsHost` wires `openConnectedDevices` to
    `graph.deviceConnect.openConnectedDevices()` in both the list and two-pane builders and
    catches the command route in the two-pane `onSelect`.
- Mapping UI, handoff and remote control working on desktop: replaced the unused
  `DesktopConnectedDevicesSheet` with a global `DesktopDeviceConnectHost`, mounted once in
  the desktop `overlays` slot (`DesktopHostBindings`). It always renders the pairing and
  incoming-transfer dialogs over any page (so a request that arrives with the sheet closed
  still shows) and renders `ConnectedDevicesSheet` when the visible flag is raised — the
  same shape as `IosDeviceConnectHost`. The dialogs were removed from the settings page to
  avoid double composition. `onOpenSettings` routes to the device-connect settings page
  through the host's existing `ownRoutes` channel.
  Mounting the host touches the lazy `graph.deviceConnect` getter at startup; that only
  starts `DeviceConnectOwner.start()`, which observes `DeviceConnectEnabledKey` and starts
  the server/discovery only when the user enables it — no server is opened by default.

### Result

Desktop now has, like Android and iOS: a discoverable settings entry, a settings page to
enable/pair, an attached "Connected devices" sheet, pairing/transfer dialogs wherever the
user is, and playback handoff plus remote control over the real desktop queue/output. The
wire protocol and port were already shared, so desktop ↔ Android ↔ iOS behave equivalently.

## Open items / uncertainty

- No JVM/Gradle build, compile, lint or on-device run was performed here (per the mission's
  no-full-Gradle-build rule). Validation is source-level: `check_repository.py --worktree`,
  `git diff --check`, ktfmt on the new test file, careful call-site reading, and the added
  pure-Kotlin unit tests. Exact-head BUILD/TESTS remain orchestrator-controlled (Blacksmith
  CI).
- The desktop has no "player-bar" cast/connected-devices icon; the entry point is the
  settings tree (plus the global dialog host). Adding a title-bar/player-chrome button is a
  UI decision not required for functional parity, since the command row and sheet provide
  the same actions. This is the one place our surface can still differ visually from the
  reference, deliberately.
- The `AndroidOnlySettingsAnchors` mechanism and the explicit `desktop = true` flags are two
  parallel gating styles; this branch followed the dominant desktop style for consistency.
  Reconciling them is out of scope.
- `DeviceConnectManager` in the GPL decompile is a behaviour reference only; nothing was
  copy-pasted. Our implementation predates this branch and keeps its own packet format.
