# iOS UI audit (r3)

Scope: `iosApp` (SwiftUI/UIKit shell) and `shared-ui` `iosMain`/`commonMain`, against the
Android `:app` host and the `:desktopApp` host. The shared Compose tree owns every screen, the
shell chrome, the player and the lyrics on all three platforms; SwiftUI is limited to the iOS
app/scene shell per PR #80, and the one UIKit job is the root controller that reports the window's
safe area. This is a **code audit**: `ios_compile.yml` type-checks Swift and compiles Kotlin/Native,
but no full build, simulator run or device QA was performed here.

## What is correct and needs no change

- **Safe areas / notch / home indicator.** `IosRootController` reports `view.safeAreaInsets` to
  `IosSafeArea`; `SafeAreaPadding` reserves those physical edges with `absolutePadding` (never
  mirrored under RTL) and consumes `WindowInsets.systemBars` so no screen reserves them twice.
  The root paints its theme background first, inside `ArchiveTuneTheme`, and pads inside it, so the
  strips keep the app palette. The root publishes `LocalHostSafeArea`; the expanded player is
  deliberately placed **outside** the padded box (but inside the provider) so its background bleeds
  edge to edge while its own controls are inset by `safeAreaPadding(LocalHostSafeArea.current)`.
  `SafeAreaPaddingTest` (LTR/RTL/zero/clamps) and the iOS source contracts cover this. Status/nav
  bar strip colour therefore follows the theme; Android's `setSystemBarAppearance` and the
  desktop `WindowsWindowChrome` are the equivalent host-side jobs.
- **Home indicator / bottom bar / mini player.** `ArchiveTuneShell` measures its own bar and mini
  player height into `LocalShellContentPadding`, and `FloatingNavigationToolbar` /
  `SukisuNavigationBar` read `WindowInsets.systemBars.only(Horizontal)` (consumed to zero by the
  root). One shared shell on every platform. No Android-only bottom-nav crop leaks on iOS.
- **Now Playing / Lock Screen (MPNowPlayingInfoCenter + MPRemoteCommandCenter).** Complete:
  title (`MPMediaItemPropertyTitle`), artist, album, duration, artwork
  (`MPMediaItemArtwork`, cookie-free 15 s / 2 MiB / 512 px bounded fetch), elapsed position
  (`MPNowPlayingInfoPropertyElapsedPlaybackTime`, reanchored to NOW), `PlaybackRate` /
  `DefaultPlaybackRate`, media type and external content identifier. Remote commands bound: play,
  pause, toggle, stop, next, previous, seek (`changePlaybackPosition`), skip forward/backward, with
  occurrence/generation-stale guards and queue-owned `canNext`/`canPrevious` enablement. AVAudioSession
  is activated for playback (`configure_playback_locked`, long-form route sharing) so MediaPlayer
  receives remote events; `UIBackgroundModes = [audio]` is set. Artwork size (512 px edge) is shared
  with the desktop Now Playing sleeve policy.
- **Lyrics sheet.** Every lyric look is the shared Compose pane (`LyricsFullscreenRenderer`, side
  panel, fullscreen column). There is no UIKit/SwiftUI lyric surface; a lock-screen lyric/Live
  Activity would need a documented WidgetKit/ActivityKit system exception, which does not exist.
- **Keyboard insets in search.** `SearchScreen` uses `WindowInsets.safeDrawing.union(ime)` on the
  bottom search pill and `WindowInsets.ime` for visibility; `ListenTogetherChatScreen`,
  `CommentTogetherScreen`, `MusicRecognitionScreen` use `imePadding()`. These are not consumed by the
  root (which only consumes `systemBars`), so the IME still reaches them on iOS.
- **expect/actual.** Every `expect` has a real `iosMain` `actual` (SQLite driver, deflate/inflate,
  NFD normalization, lyrics transliteration, acoustic heat image, automix interrupt, shortcut
  platform). None returns a UI-depended-on no-op. `MainViewController` is a `ComposeUIViewController`;
  the two Swift sources are compiled by the Xcode target.
- **Dynamic type / scale.** iOS system Dynamic Type reaches Compose through `LocalDensity.fontScale`.
  The Android-only `ui_scale` row is deliberately hidden on iOS (`AndroidOnlySettingsAnchors`) and
  the iOS root never builds a scaled `Density`, so a stored value cannot produce a divergent layout;
  the shared root's font choice is restart-scoped. Haptics use `LocalHapticFeedback` in common code.
  Scroll physics/overscroll are Compose defaults shared with Android; there is no platform-specific
  overscroll branch to leak. Swipe-back: iOS has no `UINavigationController` here; the root owns back
  through the shell's own dispatch and `backRequests`, so no native gesture is intercepted.
  Context menus are shared Compose dialogs; no `UIContextMenuConfiguration` diverges.

## Concrete defect fixed

**Remote transport seek was unbounded and could reset streams reporting a zero duration.**
`IosNowPlaying` handled the Lock Screen/Control Center scrubber with
`player.seekTo((positionTime * 1_000).toLong())` and interval skips with
`(position + seconds * 1_000).coerceIn(0L, duration)`:

- The absolute scrubber value was **not** clamped to the reported duration, and a large
  `positionTime` could overflow the `Double -> Long` cast on the way in.
- `skipBy` derived `duration` as `snapshot.durationMs?.coerceAtLeast(0L) ?: Long.MAX_VALUE`. A
  `null` duration was already unbounded (`Long.MAX_VALUE`), but a **reported `durationMs == 0`**
  (a stream or a track before its length is parsed) made `coerceIn(0L, 0L)` clamp **every forward
  skip to position 0**, seeking back to the start. `skipBackward` clamping to 0 was correct; the
  forward case was a real regression. Both the absolute seek and `position + delta` could also wrap
  a `Long` for very large event values.

Every on-screen seek path (`iosSeekTarget` in `shared-ui/iosMain`, `desktopSeekTarget`, the Android
player) bounds only to a duration **strictly greater than zero** (`if (durationMs > 0L) ... else
target`) and guards the addition against overflow. The remote commands silently disagreed with them.

Fix:

- Added `shared-data/src/commonMain/.../playback/NowPlayingSeekPolicy.kt` with
  `nowPlayingSeekTargetMs(basePositionMs, deltaSeconds, durationMs)`: converts seconds to ms,
  rejects non-finite requests, bounds to `[0, known positive duration]` (unbounded otherwise), and
  saturates instead of wrapping. Platform-independent so the Lock Screen and on-screen controls
  cannot drift.
- `IosNowPlaying` now routes **both** `changePlaybackPositionCommand` (absolute: base `0`) and
  `skipBy` (relative: interpolated position) through the one policy, so a remote seek obeys exactly
  the same bounds as a tap on the on-screen skip button.

Tests added:

- `shared-data/src/commonTest/.../NowPlayingSeekPolicyTest.kt` — absolute scrubber conversion and
  duration clamp, interval skip forward/back from position, unknown/zero/negative duration as
  unbounded (the live-stream regression), negative base, non-finite rejection and overflow
  saturation.
- `iosApp/tests/test_source_contracts.py::test_remote_seeks_use_one_bounded_shared_policy` — pins
  both call sites to the shared policy and the unbounded-duration rule.

## Left open / uncertain (needs Apple SDK or a device, not code-proven here)

- **Runtime verification of the Lock Screen.** The publisher only writes `nowPlayingInfo`; it never
  sets `MPNowPlayingInfoCenter.playbackState` (the desktop macOS bridge does). The play/pause state
  is conveyed through `ElapsedPlaybackTime` + `PlaybackRate`, which is the documented MediaPlayer
  contract for an app that drives its own clock, but lock-screen/Control Center transport rendering,
  remote-command delivery while backgrounded, and artwork appearance have **not** been exercised on
  a device or simulator. No `UIApplication.beginReceivingRemoteControlEvents` is called; with an
  active playback `AVAudioSession` and `UIBackgroundModes = audio` this is normally not required,
  but that remains unverified on a real Apple host.
- **OS light/dark changes at runtime.** The iOS host passes `systemDark` only via the shared
  `ArchiveTuneRoot` default (`isSystemInDarkTheme()`); unlike the desktop host there is no
  UIKit `traitCollectionDidChange` watcher feeding a `StateFlow`. Compose's `isSystemInDarkTheme()`
  is expected to observe the trait environment, but this is not proven here; an app that changes
  appearance while backgrounded could resume with a stale "AUTO" scheme. Static light/dark is fine.
- **Status-bar icon contrast per theme.** `IosRootController` delegates `childForStatusBarStyle` to
  the Compose controller and the theme paints the strip, but the Compose layer never sets
  `UIStatusBarStyle`; iOS uses the view controller's `preferredStatusBarStyle`. Whether the light
  content style tracks a dark/tinted palette (Android does this via `setSystemBarAppearance`) is not
  established from code.
- **Dynamic Type extremes.** `LocalDensity.fontScale` is honored by `sp` text, but very large
  accessibility sizes were not laid out on a device; fixed `dp` artwork/chrome sizes stay fixed by
  design, as on Android.
- The audit found **no** wrong insets, no Android-only UI leaking onto iOS, and no missing
  expect/actual no-op that the UI depends on. Those conclusions are code-level; the baseline iOS
  source-contract suite (24 tests) and `check_repository.py --worktree` pass after the fix.
