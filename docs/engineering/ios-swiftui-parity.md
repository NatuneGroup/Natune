# iOS SwiftUI boundary

## Natune boundary

The shared Compose tree owns every app screen, the shell chrome, the player and every lyric
look on iOS, exactly as on Android and desktop. An earlier refactor moved the tab bar, mini
player and Enhanced lyrics into UIKit; that second UI was removed and must not come back.

The app target has three Swift sources:

- `iosApp/ArchiveTune/ArchiveTuneApp.swift` is the only app source that imports SwiftUI. It
  holds the App/WindowGroup entry, the Compose `UIViewControllerRepresentable`, and URL and
  browsing-activity forwarding. It uses no app content controls, `ObservableObject` or
  `UIHostingController`.
- `ArchiveTuneSession.swift` retains the application-owned `IosHost`, foreground/background
  delivery, crash capture, final shutdown and identity-checked controller re-presentation.
  Its one UIKit job is `IosRootController`: it hosts the Compose controller full-bleed and
  reports `view.safeAreaInsets` to Kotlin. It draws no app content.
- `NatuneCarPlayScene.swift` is the CarPlay `CPTemplateApplicationScene` delegate. It shows
  bounded library and queue templates and dispatches selections to the shared player.

The widget extension (`iosApp/Widgets/NatuneControls.swift`) uses SwiftUI and WidgetKit for
home-screen playback controls fed by a credential-free shared-host snapshot. CarPlay
templates and widgets are the only system-surface exceptions; they never render ordinary
app content.

Safe-area handling has one owner:

- `IosHost.safeArea` (`shared-ui/src/iosMain/.../IosSafeArea.kt`) holds the insets as
  physical edges, and commonMain `SafeAreaPadding` (`Modifier.safeAreaPadding`) reserves them
  once for the whole app with `absolutePadding`, so a landscape notch stays on the side it
  physically occupies even under RTL. The same modifier consumes `WindowInsets.systemBars`,
  so a screen that still reads `statusBars` or `navigationBars` cannot reserve them a second
  time. The shell measures its own bar and mini player (`LocalShellContentPadding`, from
  `chromeHeight`), so no page sits under a control it drew.
- The padding is applied inside `ArchiveTuneTheme`, after that theme's full-bleed background,
  so the strips it leaves keep the app's own palette instead of the window's system colour;
  the UIKit root view is `.clear` for the same reason.

The deployment target is iOS 16.0 and the app identifier is `moe.rukamori.archivetune`.

Metrolist's iOS build, used as a behavior reference, follows the same shape: a Compose app
inside a small SwiftUI App/WindowGroup shell with a `UIViewControllerRepresentable`.

## Lock-screen lyrics

There is no lock-screen lyrics or Live Activity implementation. The Enhanced lyrics pane is
inside the player and drawn by Compose. `IosNowPlaying` supplies MediaPlayer
metadata, artwork, elapsed rate and queue-owned remote controls, not lyrics.

Real lock-screen or Dynamic Island lyrics would need SwiftUI `ActivityConfiguration` views
and an ActivityKit owner. UIKit cannot replace those system-rendered views. That would be a
new, explicitly documented system-only exception.

## Validation boundary

The Python repository check and iOS source-contract suite are lightweight wiring evidence
only. `.github/workflows/ios_compile.yml` compiles Kotlin/Native, links the simulator
framework and type-checks all app Swift against Xcode 26.4; it does not run the app, fully
link the native audio closure, sign or install it, or validate physical output.
Device/simulator interaction, glass sampling, Dynamic Type/RTL, iPad resize, manual
scroll/seek/share, background/foreground and lock-screen transport still need runtime QA.
