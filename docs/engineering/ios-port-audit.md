# iOS port defect audit

Baseline: `a6c376ac`, branch `feat/r3-ios-port`. References below are baseline
line numbers. This audit precedes implementation. No local Gradle/JVM builds,
lint or tests; simulator compilation and Kotlin tests belong to CI. Device
behaviour and the installed video's revision are not verified here.

## Evidence and priority

Inspected the supplied `natune-ios-player.jpg`, `natune-ios-decode-failed.jpg`,
`ios-bug-video-sheet.jpg` and `android-reference-sheet.jpg` under
`/data/workspace/ios-port-inputs`. The iOS recording shows a blank launch wait,
search edits disappearing, delayed results, a playback failure over Search,
and a full player with no visible dismiss control. Android is the presentation
reference: Solar controls, square artwork, artwork/title/artist mini player and
pause pill, existing grouped settings and independent style preferences.

1. **P0 playback stalls.** `shared-data/src/iosMain/kotlin/moe/rukamori/archivetune/shared/data/provider/IosYouTubeResolver.kt:62`
   prefers Opus/WebM itag 251; `:507` adds a WebM bonus in Echo; `:599`
   requests AUTO/HIGH from InnerTubeX. InnerTubeX already disables HLS/SABR/
   bounded-range at `:265`; preserve that guard and the four-tier source chain.
   `platform/ios/IosHttpRangeSource.kt:178` resumes a paused NSURLSession task
   outside its state lock, while `:215` marks it paused before suspending
   outside the lock. A consumer can resume before the producer suspends,
   leaving a drained source suspended indefinitely. This is a concrete race,
   not proof that it caused this recording. `:447` also treats any partial
   response ending before total resource length as premature EOF; inspect the
   Content-Range end and distinguish a complete partial response from truncation.
   `platform/ios/IosPlaybackPort.kt:838` watches lack of progress; `:857` emits
   the exact reported message. The message proves a stall, not a codec failure.
   Repair transport and format policy; retain bounded, source-aware recovery.
2. **P0 cannot leave player.** `shared-ui/src/commonMain/kotlin/moe/rukamori/archivetune/shared/ui/ArchiveTuneRoot.kt:208`
   already wires collapse through `PlayerInteractionState.back`; `:227` gives
   player panels priority over the route stack. `player/BottomSheetPlayer.kt:44`
   delegates back dispatch to the host, while numbered players draw only a
   label above artwork (`playerstyles/NumberedPlayers.kt:153`). iOS has no
   Android hardware back. Provide a shared, always reachable dismiss control
   and inspect gesture/route dispatch without adding SwiftUI app screens.
3. **P0 search editing.** `shared-ui/src/commonMain/kotlin/moe/rukamori/archivetune/shared/ui/host/HostCatalogPages.kt:71`
   keys query state to `route.value`; `screens/SearchScreen.kt:226` uses the
   String text-field overload. Trace binding recreation, submission, async
   results and IME composition before changing debounce. Preserve entry state
   and keep active editing independent of response recomposition.
4. **P1 Solar resources/controls.** `playerstyles/PlayerStyleParts.kt` and
   `player/PlayerComponents.kt` draw transport symbols themselves; navigation
   has similar substitutes. Audit every shared drawable against Compose XML
   vector support, and replace placeholder `m4_icon_*` / `m6_icon_*` paths with
   actual Android assets. Reuse `app/src/main/res/drawable/solar_*.xml`; preserve
   attribution, literal colours and the existing 17 player style meanings.
5. **P1 credits and cover.** `shared-data/src/commonMain/kotlin/moe/rukamori/archivetune/shared/data/provider/YouTubeShelfWire.kt:377`
   reads only the first subtitle group for artists, which can be `Song`.
   Trace the second browse parser and queue metadata projection so list,
   player, mini player and Lock Screen receive the same corrected credit.
   `shared-ui/src/iosMain/kotlin/moe/rukamori/archivetune/shared/ui/ios/IosPlayerControls.kt:222`
   supplies an empty palette. `playerstyles/NumberedPlayers.kt:158` fills a
   tall weighted frame with blurred artwork behind a square, producing bars.
6. **P1 player/mini player parity.** Shared player styles and shell chrome
   already own iOS. Port Android geometry and Solar artwork/control treatment
   into these components, including compact layouts, rather than replacing
   independent preferences or introducing another platform renderer.
7. **P2 launch wait.** `shared-ui/src/iosMain/kotlin/moe/rukamori/archivetune/shared/ui/ios/IosMountedRoot.kt:55`
   renders only a spinner until the entire graph is Ready. Trace `IosHost`
   startup dependencies, move optional/network work after readiness and show
   useful shared launch content without bypassing restore/database recovery.

## Metrolist comparison actually inspected

- `/data/workspace/re-out/sharedSub/sources/com/metrolist/shared/playback/IosAudioQualityKt.java:104`:
  LOW remains LOW; AUTO/HIGH/MP4 become MP4. `withIosAudioCapabilities` calls
  `withStreamCapabilities(allowHls, false, false)` (HLS explicit fallback,
  SABR and bounded ranges disabled).
- Adjacent `PlaybackProgressWatchdog.java:43`: 12 s timeout, 2 s end tolerance,
  resets on instance/position changes, pause/resume and end proximity; reports
  once per unchanged interval. A watchdog detects a symptom, not its source.
- Inspected the existing Mach-O in place using mmap, without extraction.
  `AVPlayer` ASCII offset 95831221, `resourceLoader` UTF-16LE 106801672,
  `addPeriodicTimeObserver` ASCII 96000801, `timeControlStatus` UTF-16LE
  110814306, `MPRemoteCommandCenter` ASCII 113511655, and
  `PlaybackProgressWatchdog` UTF-16LE 105096370. These support an AVPlayer /
  resource-loader / observer / remote-command implementation; strings alone
  do not establish control flow. Natune uses FFmpeg and AVAudioEngine, so
  replacing its engine based solely on strings would bypass existing DSP,
  lease and source contracts. Apply the evidenced policy and repair its adapter.

## Validation and handoff

Add focused commonTest/iosTest coverage for format ranking, progress watchdog,
subtitle credits and route back order. Run only
`python3 scripts/check_repository.py --worktree` and
`python3 -m unittest discover -s iosApp/tests -p 'test_*.py'` locally.
Commit and push each logical fix. No PR or workflow dispatch.

On device: reproduce the same songs through the full duration and seeking;
test interrupted/network-starved playback, background/Lock Screen and source
fallback; type continuously with iOS composition/autocorrect; dismiss player
and inner panels from every tab; inspect every player style, Solar vectors,
compact mini player, safe areas and cold/warm/offline launch. A green light
check is not simulator compilation or device evidence.

## Implementation: playback transport and policy

- Serialized NSURLSession suspend/resume with the buffer state lock. These state calls
  enqueue work; neither waits for the delegate queue. The reader cannot now resume
  before the producer suspends. `IosHttpRangeSource.kt` remains bounded to 4 MiB high /
  1 MiB low water, with cancellation and credential checks preserved.
- `IosResponseByteRange.kt` validates both range endpoints and total, continues complete
  partial bodies at the next byte, retries truncation, and rejects changing resource lengths.
- `IosAudioFormatPolicy.kt` prefers MP4/AAC in primary, NewPipe mechanism and Echo;
  InnerTubeX requests MP4 except LOW, now honoring request-specific quality. Existing
  SABR/HLS/bounded-range exclusions and the four-tier chain remain intact.
- The shared progress watchdog now resets on resume and rearms after pause, matching
  Metrolist's `expectingProgress` transition; zero/unknown duration cannot suppress stalls.
  The 12 s threshold, end tolerance and bounded source-aware recovery stay unchanged.
- Added simulator format/range policy tests and extended common watchdog tests. Native
  scheduling, network starvation and physical output still require simulator/device QA;
  these fixes identify code defects, not a proven diagnosis of the supplied recording.

## Implementation: shared back navigation

- `BottomSheetPlayer.kt` now reserves a 48 dp top control area and exposes Back on
  every phone/tablet style, including landscape and queue/lyrics panels. Fullscreen
  lyrics keep their existing back control. The shared root collapses only after the
  inner panel is closed; it retains the underlying tab and route.
- `PlayerInteractionState.backOrCollapse` is shared by the visible control and host
  back dispatch. The host collector reads the latest handler. Added common tests for
  fullscreen → panel → player → route order and independent restored tab stacks.
- Inspection found no UIKit navigation controller/swipe-pop stack in this single
  Compose host. The fix supplies an explicit shared control; it does not claim a
  native edge-swipe implementation. Verify touch targets and all styles on device.

## Implementation: search editing

- `hostSearchPage` now saves query/scope with the navigation entry. Previously plain
  `remember(route.value)` lost drafts when that entry left composition (tabs/routes).
- `SearchScreen` owns a saved `TextFieldValue` above top/bottom field placement, carrying
  selection and iOS marked-text composition through result/playback updates. Text alone
  is published to catalogue bindings; external query changes move the caret intentionally.
  Submission dismisses the keyboard before opening the results route. Search results now
  expose Back as other detail screens do.
- No debounce was found on this path: results are submitted explicitly and fetched by
  the existing cancellable presenter. Network latency is separate from editor state.
  These state-lifetime fixes need continuous typing/autocorrect/CJK and tab-return device
  verification; the recording alone cannot prove an IME framework defect.
