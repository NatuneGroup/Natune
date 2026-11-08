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

## Implementation: Solar vectors and controls

- `PlayerComponents.PlayerGlyph`, volume/sleep glyphs in `PlayerStyleParts`, and shell
  `TabMark` now load the Android Solar vector paths, retaining shared animation, tint,
  selected semantics and control sizes. Repeat-one has its actual icon; liked hearts
  use the filled asset.
- Shared m4/m6 aliases were reconciled with Android originals. Every shared vector has
  literal colours and every stroke-only path an explicit transparent fill. No theme or
  colour references were found in the original shared set; repeated placeholder paths
  were not found either. Missing-fill portability and hand-drawn controls were concrete
  issues; the claim that every XML file was broken is not established by source checks.
- `iosApp/tests/test_shared_vectors.py` checks all vectors, finite path coordinates,
  command arity/arc flags and exact Android player paths. This validates portable input,
  not iOS pixels; simulator rendering of every resource still needs CI/device verification.

## Implementation: artist credits and artwork palette

- `YouTubeArtistCredits.kt` is the common linked-credit policy used by both wire and
  iOS InnerTubeX parsers. It inspects every subtitle group, preserves artist order,
  deduplicates IDs and excludes album/type/duration runs. Two-row tiles and the iOS
  playback byline now project those credits too. An absent artist no longer becomes
  the literal subtitle `Song`.
- `QueueMetadataProjection` already preserves user overrides and publishes the same
  item to shared player/mini player and native metadata; it needed no rewrite. Existing
  persisted queue rows with bad provider metadata need reloading, not a destructive migration.
- `ArtworkPalette.kt` moves the desktop palette algorithm into common UI; desktop keeps
  a forwarding adapter. iOS computes it off the main dispatcher from its existing bounded
  artwork loader, observes cover overrides, cancels obsolete work and rejects stale covers.
- Added common parser-policy and actual shelf-fixture tests. Verify artist/Lock Screen
  refresh and artwork-tinted styles on device, including custom cover replacement.

## Implementation: player and mini-player geometry

- `NumberedPlayers.NumberedArtwork` now fits one square pager to the smaller available
  dimension; it no longer paints a stretched blurred copy behind the square. This removes
  the rectangular bars visible in `natune-ios-player.jpg`, retains double-tap/swipe/canvas
  behaviour, and keeps all existing style selections and background preferences.
- `ArchiveMiniPlayer` now uses an artwork/title/artist row and a Solar play/pause pill,
  with a real buffering indicator and replay state. Below 300 dp (the optional one-row
  shell), smaller insets/artwork and omission of the secondary Next button leave room for
  metadata. Main play/pause and normal-width Next keep 48 dp touch targets.
- This reuses the shared player and shell, as the Android reference requires. Remaining
  on-device visual acceptance includes short/landscape screens, large text, all style
  selections and liquid-glass/compact navigation; no screenshot parity claim is made.

## Implementation: launch readiness

- Traced `IosSessionGraph.assemble → queue.resume → PortableQueueOwner.restore →
  requestFor → IosQueueLocatorResolver` to network source resolution of the saved song.
  This was awaited before `Ready`, making first paint wait on provider latency.
- `IosHost` publishes the valid graph before `startAfterMount` restores playback.
  Local/download owners finish local initialization before UI commands can reach them. `TypedDelegatingQueueController.resumeIfPristine`
  checks the live queue under the same mutation lock, so a queue selected first wins.
  Shutdown cancels/joins startup before closing owners. Local imports are indexed before
  restoring a local song (the original order attempted resolution before their paths loaded).
- Database/settings/backup recovery remain before readiness; recovery mode blocks deferred
  startup. Queue checkpoints remain protected against an untouched empty session. Once
  restore holds the queue lock, later queue mutations still serialize behind its bounded
  source resolution; browsing/search can render while that occurs.
- The opening boundary is now shared Compose branding plus `Opening your library…` and
  a small progress indicator. Optional language-pack foreground consideration starts after
  graph readiness. Cold/warm/offline launch and restored local/remote queues need device QA.

## Source-review follow-up

- Kept mutable local/download owner initialization before Ready to avoid racing their
  `start()` with newly enabled UI commands. Only saved-song provider resolution is deferred.
- Preserved the results Back header when the search field is placed at the bottom.
- The mini-player Replay action now shares `togglePlayPause` with the full player and
  seeks to zero before Play; removed a duplicate layout import found during source review.
- Inspected the cached InnerTubeX v0.11.2 JAR in place: its AudioQuality enum contains
  MP4 and its Format bitrate accessor returns Int, matching the new iOS selection calls.
  No archive was extracted and no JVM was launched.

## Pushed commit map and final validation

The initial worktree was clean at audit commit `28eab547`; there was no uncommitted
WIP to recover. Each implementation step below was committed and pushed to
`origin/feat/r3-ios-port`. No PR was created and no workflow was dispatched.

| Defect / root cause | Primary fix commit |
| --- | --- |
| Byte-source suspend/resume inversion; complete partial bodies treated as truncated files; WebM-first iOS policy; watchdog resume window | `ab9af533` |
| No shared phone player dismiss target; preserve inner-panel/player/route order | `bccfb9c5` |
| Unsaved search entry draft; selection/composition not explicitly owned across field placement | `8e5ddf28` |
| Hand-drawn transport/navigation symbols; implicit fills in shared XML | `92b1171e` |
| Artists read only from first subtitle group; iOS palette always empty | `84b893d3` |
| Tall blurred artwork frame; text-only transport crowded the mini player | `024feb65` |
| Graph readiness awaited saved-song network resolution | `44eadd61` |
| Source-review corrections: startup owner ordering, bottom Search Back, shared Replay action, duplicate import, Solar attribution | `86c70c30` |

Current source locations (after the implementation commits):

- transport: `shared-data/src/iosMain/kotlin/moe/rukamori/archivetune/shared/data/platform/ios/IosHttpRangeSource.kt:158`
- format: `shared-data/src/iosMain/kotlin/moe/rukamori/archivetune/shared/data/provider/IosAudioFormatPolicy.kt:4`
- watchdog: `shared-data/src/commonMain/kotlin/moe/rukamori/archivetune/shared/data/playback/PlaybackProgressWatchdog.kt:30`
- back: `shared-ui/src/commonMain/kotlin/moe/rukamori/archivetune/shared/ui/player/BottomSheetPlayer.kt:154`
- search: `shared-ui/src/commonMain/kotlin/moe/rukamori/archivetune/shared/ui/screens/SearchScreen.kt:89`
- icons: `shared-ui/src/commonMain/kotlin/moe/rukamori/archivetune/shared/ui/player/PlayerComponents.kt:130`
- credits: `shared-data/src/commonMain/kotlin/moe/rukamori/archivetune/shared/data/provider/YouTubeArtistCredits.kt:6`
- palette: `shared-ui/src/iosMain/kotlin/moe/rukamori/archivetune/shared/ui/ios/IosPlayerControls.kt:84`
- cover: `shared-ui/src/commonMain/kotlin/moe/rukamori/archivetune/shared/ui/playerstyles/NumberedPlayers.kt:155`
- mini: `shared-ui/src/commonMain/kotlin/moe/rukamori/archivetune/shared/ui/player/ArchiveMiniPlayer.kt:90`
- startup: `shared-ui/src/iosMain/kotlin/moe/rukamori/archivetune/shared/ui/ios/IosSessionGraph.kt:348`
- restore: `shared-data/src/commonMain/kotlin/moe/rukamori/archivetune/shared/data/repository/TypedDelegatingQueueController.kt:85`

Validation on the final implementation:

- `python3 scripts/check_repository.py --worktree`: **0 findings**.
- `python3 -m unittest discover -s iosApp/tests -p 'test_*.py'`: **28 passed**.
- Added common tests for watchdog pause/resume and unknown duration, linked artist policy
  and a typed shelf fixture, player/route back order, and deferred queue restore precedence.
  Added iosTest format-selection and partial-range policy tests. Kotlin tests were **not
  run locally**; compilation/execution remain for orchestrator-controlled GitHub CI.
- The Python vector checks cover all shared XML vectors, including syntax/command shape;
  they do **not** prove iOS rendering. No local Gradle/JVM, lint, build, simulator or device
  test was run. Existing JAR inspection read selected entries in memory without extraction.
- The supplied recording's exact stall cause remains unproven. Device verification must
  cover full-track/seek/background/interrupted playback, IME composition and fast edits,
  all player/panel Back paths, all style and compact/safe-area layouts, custom covers and
  Lock Screen artist metadata, and cold/warm/offline launch with saved local/remote queues.
  iOS native edge-swipe navigation is not implemented by these commits; explicit shared
  Back controls are. Old saved provider metadata refreshes when its content is reloaded.
