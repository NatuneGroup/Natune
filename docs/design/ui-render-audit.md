# Natune UI render audit

Full pixel and behavior parity is not established. The render harness uses fake data. Native iOS captures are Skia frames with injected safe areas, not UIKit window or simulator screenshots. Linux Skia captures at 393 × 852 dp are viewport proxies and do not validate iOS fonts, gestures, or lifecycle.

## Published evidence

| Platform | CI evidence | Scope and limits |
| --- | --- | --- |
| Native iOS Skia | [Run 42949329927](https://github.com/NatuneGroup/Natune/actions/runs/42949329927), head `2008b225`, 30 PNGs | Covers player styles, queue, fullscreen lyrics, settings, and shell scenes. Predates the SimpMusic portrait-fit and Cinematic collapse fixes. `home.png` / `mini-player.png` and `player.png` / `player-style-V4.png` are duplicate scene pairs. |
| Android emulator | [Run 42880704145](https://github.com/NatuneGroup/Natune/actions/runs/42880704145), head `3b6fefad` | Offline API 36 QA. Home, Search, and Library contain empty or error states. Style captures show Appearance settings rather than the player, so they cannot validate SimpMusic or Cinematic player layouts. The viewport predates the alignment change. |

Download available artifacts from the linked runs. These captures document their recorded revisions; they are not visual verification of subsequent changes. Intermediate desktop proxy PNGs have been removed because their controls and layout differ from the current implementation.

## Implemented fixes and coverage

| Area | Result | Verification |
| --- | --- | --- |
| Settings | Missing or blank search anchors start at the first group, with Account and Appearance visible. | `SettingsBackTest`; phone heading and description tests. |
| Cinematic phone player | The portrait header has a visible, labelled collapse button that dispatches `COLLAPSE`. Other orientations retain the shared close control. | `CinematicPhoneControlsTest` checks dismissal and every saved style in both orientations. |
| Phone sheet | A labelled accessibility collapse action dismisses the player. Derived visibility keeps root chrome and player-slot owners stable during intermediate animation frames. | `PhonePlayerSheetTest` checks accessibility dismissal, animation visibility observations, and vertical drag behavior. |
| Fullscreen lyrics | The visible lyrics surface retains optical motion, including MOVING_BLUR drift, lossless shimmer, and transport tap feedback. | `FullscreenLyricsChromeTest` advances the Compose clock and checks artwork movement and the animations-disabled stop. |
| SimpMusic | The square phone sleeve yields height to keep transport and footer controls visible, with one shared collapse control. | `SimpMusicPortraitFitTest` checks 393 × 852 and 360 × 640 viewports, including injected iOS safe areas. |
| Queue, mini player, and stream quality | Phone queue chrome and dismissal, the raised mini card, and centred shared quality badges are implemented. | Queue, mini-player, and quality-badge Compose suites; historical captures in the linked runs. |

The [UI gap inventory](ios-android-ui-gap.md) records remaining platform differences. CI on [PR #118](https://github.com/NatuneGroup/Natune/pull/118) compiles Android, desktop, and iOS and runs app and shared tests. The native render audit remains opt-in through the `render_audit` input of the iOS compile workflow.

## Unverified

- Fresh native renders of the SimpMusic and Cinematic fixes.
- UIKit safe areas, accessibility interaction on a device, gestures, lifecycle, and Dynamic Type.
- Loaded Android and iOS screens with matched data, artwork, palettes, and viewports.
- Complete pixel comparisons of player styles, queue chrome, lyrics glyphs, mini cards, and settings font weights.

The home fire emoji follows the Android source reference. Linux proxy font coverage does not establish native iOS glyph availability.
