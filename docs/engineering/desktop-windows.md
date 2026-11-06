# Desktop window layer (Windows/Linux/macOS)

The part of the desktop app outside the shared Compose UI: window chrome, saved geometry, renderer diagnostics,
typography and DPI. The shared shell, settings and player layouts are described by their own pages; nothing here changes
the Compose composition tree.

## Window chrome

- `DesktopWindowIcon` (`desktopApp/.../desktop/DesktopWindowIcon.kt`) renders the PNG in the application jar
  (`desktopApp/src/jvmMain/resources/archivetune.png`) at 16–256 px and assigns it to the AWT window's `iconImages`.
  Without it the title bar, taskbar hover and Alt-Tab show the JDK's default window icon while the launcher's `.ico`
  brands only the Start-menu entry. Linux reads the same list for `_NET_WM_ICON`; macOS takes the dock icon from the
  bundle and ignores it.
- `WindowsWindowChrome` (`desktopApp/.../desktop/integration/windows/`) sets
  `DWMWA_USE_IMMERSIVE_DARK_MODE` (20, falling back to 19 on Windows 10 1809–1909) from the app's effective dark theme and
  `DWMWA_WINDOW_CORNER_PREFERENCE` (Windows 11 22000+) through JNA's `DwmSetWindowAttribute`. Each apply writes one INFO
  line (`window chrome: dark=1 darkAttribute=applied roundedCorners=true`); an older Windows that rejects the attribute
  keeps the stock frame and logs it once.
- The caption colour (`DWMWA_CAPTION_COLOR`) is deliberately not set. The user's palette is resolved inside the shared
  theme in `ArchiveTuneRoot`; the window layer only knows the light/dark reading, so a caption colour picked there would
  disagree with a custom palette.

## Saved geometry

`DesktopWindowBounds` keeps size, position and maximized state in `window-bounds.properties`. Saved values are dp;
`GraphicsConfiguration.bounds` and the work area are device pixels. `DesktopScreenSpace` carries both the screens and the
display scale, and every rule is a pure function over it, so the unit boundary is tested instead of assumed:

- `fitToWorkArea` clamps the default 1024×720 dp to the work area in dp. At 150 % on 1920×1080 the 1040 px work area is
  693 dp, so the transport strip no longer opens under the taskbar; at 125 % on 1366×768 the 728 px work area is 582 dp.
- `positionIsUsable` converts the saved dp position and size with the display scale before intersecting it with any
  screen. A window must land a 96 dp wide, 48 dp tall strip on a monitor, so a position that is only visible because dp
  were compared as pixels is now dropped.
- `minimumWindowSize` applies the 800×600 dp minimum in AWT device pixels once and caps it at the work area, so a 150 %
  laptop can still resize the window to fit.
- Residual risk: whether `Window.minimumSize` is interpreted in AWT user space or device pixels by the Windows peer has
  not been confirmed on Windows hardware. The work-area cap keeps the window usable under either reading; the exact
  minimum still needs one Windows run.

## Renderer diagnostics and blur

- `DesktopRendererDiagnostics` logs `renderer: requested=…` before the first window (from `DesktopRuntime.prepare`) and
  `renderer: active=…` once the window reports the backend Skiko settled on. The active value is read from the layer
  component's `renderApi` property by reflection, so a Compose/Skiko update can only cost an "unknown" line, never a
  startup failure. Skiko's own `[SKIKO]` standard-error lines are captured by the diagnostic log under their own `Skiko`
  tag, and the crash/diagnostic report's device info carries a `Renderer` row.
- `NATUNE_RENDER_API` is the support escape hatch: it is mapped onto `skiko.renderApi` only when that property was not
  already set for this process, and it is never a default.
- Blur is offered only while a GPU backend draws. `SOFTWARE_FAST` and `SOFTWARE_COMPAT` flip the capability to false and
  the root recomposes into crisp surfaces; before the probe the value stays true, because a GPU backend is the normal
  case and an unknown value must not degrade a working window.

## Typography

- The bundled Poppins family carries real Regular (400), Medium (500), SemiBold (600) and Bold (700) faces, mapped in one
  `FontFamily` in `ArchiveTuneTheme`. The single Regular file it replaced made every Material 3 Medium/Bold style flat or
  synthesised. The family is OFL-1.1; the license text in
  `shared-ui/src/commonMain/composeResources/files/m4_poppins_OFL.txt` covers every face of the family.
- Poppins covers Latin and Devanagari only. CJK, Cyrillic, Arabic, Hangul and emoji are drawn by the platform font stack
  (DirectWrite, CoreText, fontconfig), which is what Skia's paragraph engine asks for a missing glyph; no CJK or emoji font
  is bundled. `SystemFontFallback.report()` logs the family the OS resolves per script at startup (`fonts: system fallback
  …`); a `none` entry means the host has no font for that script and the glyph box is the OS's, not an app omission.

## Per-monitor DPI

- jpackage writes the JDK launcher manifest, which declares **system** DPI awareness: the process gets one scale for all
  monitors, so on a 100 % + 150 % desktop the window is mis-scaled on one screen and does not re-lay out when dragged
  across. `docs/engineering/toolchain.md` records the JDK/jpackage versions this relies on.
- `desktopApp/packaging/windows/launcher.manifest` declares `PerMonitorV2, PerMonitor` and `longPathAware`.
  `:desktopApp:stampWindowsLauncherManifest` embeds it into `build/compose/binaries/main/app/Natune/Natune.exe` with
  `mt.exe`, and `packageMsi` depends on that task, so the MSI and the EXE/ZIP built from the same app image install the
  stamped launcher.
- The stamp is **opt-in**: the release/packaging command must pass `-PdesktopWindowsLauncherManifest=per-monitor-v2`
  (and `-PmtExe=<path>` when the Windows SDK is not in its default location). It is off by default because AWT's
  per-monitor relayout and the Compose window sizing that follows it can only be signed off on real mixed-DPI hardware;
  enabling it unreviewed risks a worse layout than the current single-scale behaviour.
- `WindowsDpiAwareness.describe()` logs `display: dpiAwareness=…` on Windows (read-only; awareness can only be set before
  the toolkit starts and the launcher manifest owns it).

### What still needs a Windows machine

1. `mt.exe -nologo -inputresource:Natune.exe;#1 -out:check.manifest` on a stamped app image contains `PerMonitorV2`.
2. Dragging between a 100 % and a 150 % monitor re-scales the whole window once, keeps the dp size and leaves no blurred
   bitmap scaling; the log then reads `display: dpiAwareness=per-monitor-v2`.
3. The title bar follows the app theme (dark client area ⇒ dark title bar), the window icon shows in the title bar,
   taskbar and Alt-Tab, and `window chrome: … darkAttribute=applied` appears in the instance log.
4. The minimum window size at 150 % still lets the window be resized until the transport strip is visible above the
   taskbar (confirms the `Window.minimumSize` unit convention).
