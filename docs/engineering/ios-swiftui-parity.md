# iOS SwiftUI boundary and Metrolist comparison

## Metrolist 14.0.0-rc8 binary evidence

Inspection covers the distributed `metrolist-ios-14.0.0-rc8.ipa`, not recovered source or
runtime UI. IPA SHA-256: `d443b0a0f8d1f949c142dc55c008ce8725847566b704638691a26d6a629a55af`.
The arm64 Mach-O executable has SHA-256
`8cfadadce3490d916d424bcc3d4b9050c9f46074bd52fbc196312a99a295d648`, encryption id 0
and 1,860 nlist symbols. Reading `LC_LOAD_DYLIB`/`LC_LOAD_WEAK_DYLIB`, `LC_SYMTAB`,
printable strings and the binary plist confirms a direct SwiftUI framework dependency.

All 61 SwiftUI-related nlist symbols belong to these families (including protocol
requirements, default witnesses, associated types, metadata and opaque return descriptors):

- `App`, its `Body`/`main`/initializer, `Scene`, `SceneBuilder.buildBlock`,
  `WindowGroup` content and lazy-content initializers/Scene conformance. The lazy-content
  initializer's signature mentions optional `Text`; it does not establish a rendered label.
- `View`, `Body`, `_makeView`, `_makeViewList`, `_viewListCount`, and `Never: View`.
- `UIViewControllerRepresentable`, `Coordinator`, `UIViewControllerType`, make/update/
  dismantle, `sizeThatFits`, `_layoutOptions`, `_identifiedViewTree`, and View witnesses.
  `MainViewController` strings identify the Kotlin/Compose controller it hosts.
- `State` initialization and wrapped-value getter/setter.
- `View.onOpenURL`, `View.onContinueUserActivity`, and `View.statusBar(hidden:)`.
- `SafeAreaRegions.all`, `Edge.Set.all`, `_SafeAreaRegionsIgnoringLayout` and its
  ViewModifier conformance (the `ignoresSafeArea` shell operation).
- `ModifiedContent`, `SubscriptionView` and `_AppearanceActionModifier`: shell/modifier
  support. The latter two extend the minimal inventory beyond the visible URL/status-bar
  modifiers; stripped symbols do not identify their exact subscription/appearance closures.

Other SwiftUI strings are Sentry redaction/tracing helpers and internal view class names
(`SwiftUI.ImageLayer`, `_UIGraphicsView`, shape hit-testing and CG display-list views), not
imported SwiftUI content controls. The bundled Sentry framework has no SwiftUI symbol/string
family. Neither binary has `UIHostingController`, WidgetKit, ActivityKit or AVAudioEngine
strings. No `.appex` is packaged. AVPlayer/AVPlayerItem imports and selectors are present.
This supports a Compose app with a small SwiftUI shell, not a SwiftUI-screen implementation;
absence in a stripped binary is evidence for this build, not a universal upstream guarantee.

The plist specifies iOS **17.0**, phone/tablet family **[1, 2]**, `UILaunchScreen = {}`,
`UIBackgroundModes = [audio]` and `UIApplicationSupportsMultipleScenes = false`.

## Natune boundary

Before this refactor, all three app Swift sources imported SwiftUI:
`ArchiveTuneApp.swift` (142 lines), `NativeChrome.swift` (255) and `SystemLyrics.swift` (745).
Chrome rendered stacks/buttons/artwork with SwiftUI Liquid Glass; Enhanced lyrics used
`UIHostingController`, `TimelineView`, scrolling stacks, context/share menus, `Canvas` and
`TextRenderer` with SwiftUI state/environment/animations. None requires SwiftUI.

After the refactor, **only** `iosApp/ArchiveTune/ArchiveTuneApp.swift` imports SwiftUI. It
contains the App/WindowGroup entry, Compose `UIViewControllerRepresentable`, safe-area
handling and URL/browsing-activity forwarding. Status-bar style/visibility are delegated to
the original Compose child by the UIKit container; no fixed hidden value changes behavior.
The shell uses no app content controls, `ObservableObject`, or `UIHostingController`.

- `ArchiveTuneSession.swift` retains the application-owned `IosHost`, foreground/background
  delivery, crash capture, final shutdown and identity-checked controller re-presentation.
- `NativeChrome.swift` uses UIKit `UIGlassEffect`/`UIGlassContainerEffect`, with the same
  home/library capsule, separate Search circle and mini-player. Kotlin still owns every
  command and visibility gate. Only glass surfaces take touches; keyboard presentation
  does not lift the bar. A centered **520-point** width cap and real safe-area/window geometry
  preserve phone/iPad/Fold/resizing behavior, including the Compose rail fallback.
- `SystemLyrics.swift` and `SystemLyricsRendering.swift` use a UIKit table, TextKit glyph
  shaping and Core Graphics bright masks, plus public Core Image blur. They retain word
  sweep/completion/emphasis math from Kotlin, duet/RTL alignment, interlude dots, Dynamic
  Type, reading/translation reveals, machine-made badges, writers, reduced motion, tap-to-
  seek, native text sharing, a **35%** anchor and **350 ms** cubic follow. The shared manual
  scroll hold and discontinuity/reanchor rules remain distinct. Hidden/background panes
  stop their display link/timer; held positions poll at **250 ms**, without a Swift clock.

The existing native-interface toggle and iOS **26+** availability gates remain; iOS 16–25
stay Compose-only. Natune's **iOS 16.0** deployment target, app identifier
`moe.rukamori.archivetune`, orientations, URL schemes, single-scene plist and background-audio
mode are unchanged. No package definition, project.yml, WidgetKit target or entitlement is
present; the checked-in Xcode project includes both new UIKit Swift files.

## Lock-screen feature gap and SwiftUI exceptions

**No feature must remain SwiftUI in the current tree.** The audited baseline has no
lock-screen lyrics or Live Activity implementation: `SystemLyrics` is inside the player.
The independent `IosNowPlaying` publisher supplies MediaPlayer metadata/artwork/elapsed
rate and queue-owned remote controls, not lyrics; it is unchanged by this refactor.

Adding real lock-screen/Dynamic Island lyrics would require a WidgetKit extension with
SwiftUI `ActivityConfiguration`/Dynamic Island views and an ActivityKit owner. UIKit cannot
replace those system-rendered views. That would be an explicit system-only exception and a
separate feature implementation, not something silently removed or claimed by shell parity.

## Validation boundary

The Python repository check and iOS source-contract suite are lightweight architecture/
wiring evidence only. `.github/workflows/ios_compile.yml` compiles Kotlin/Native, links the
simulator framework and type-checks all app Swift against Xcode 26.4; it does not run the
app, fully link the native audio closure, sign/install it or validate physical output.
Device/simulator interaction, glass sampling, Dynamic Type/RTL, Fold/iPad resize, manual
scroll/seek/share, background/foreground and lock-screen transport still need runtime QA.
