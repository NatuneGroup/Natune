"""Host/source relationships only; these tests do not compile or execute Apple APIs."""
from pathlib import Path
import plistlib
import re
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
IOS = ROOT / "iosApp"
KOTLIN = ROOT / "shared-data/src/iosMain/kotlin/moe/rukamori/archivetune/shared/data/platform/ios"
UI_IOS = ROOT / "shared-ui/src/iosMain/kotlin/moe/rukamori/archivetune/shared/ui/ios"
UI_COMMON = ROOT / "shared-ui/src/commonMain/kotlin/moe/rukamori/archivetune/shared/ui"


class IosSourceContractsTest(unittest.TestCase):
    def test_host_resources_and_scheme_reference_real_target(self):
        info = plistlib.loads((IOS / "ArchiveTune/Info.plist").read_bytes())
        self.assertEqual(["audio"], info["UIBackgroundModes"])
        self.assertTrue(info["LSSupportsOpeningDocumentsInPlace"])
        self.assertNotIn("NSAppTransportSecurity", info)
        self.assertEqual(
            [
                "archivetune",
                "discord-1165706613961789445",
                "natune",
                "vnd.youtube",
                "$(PRODUCT_BUNDLE_IDENTIFIER)",
            ],
            info["CFBundleURLTypes"][0]["CFBundleURLSchemes"],
        )
        project = (IOS / "ArchiveTune.xcodeproj/project.pbxproj").read_text()
        scheme = ET.parse(IOS / "ArchiveTune.xcodeproj/xcshareddata/xcschemes/ArchiveTune.xcscheme")
        for target in scheme.findall(".//BuildableReference"):
            self.assertIn(target.attrib["BlueprintIdentifier"] + " = {isa = PBXNativeTarget", project)
        for path in re.findall(r"path = ([^;]+); sourceTree = \"<group>\"", project):
            self.assertTrue((IOS / path).exists(), path)
        self.assertIn("PBXResourcesBuildPhase", project)
        self.assertIn("InfoPlist.strings", project)
        self.assertIn(":shared-ui:embedAndSignAppleFrameworkForXcode", project)
        swift = (IOS / "ArchiveTune/ArchiveTuneApp.swift").read_text()
        self.assertIn("import ArchiveTuneShared", swift)
        session = (IOS / "ArchiveTune/ArchiveTuneSession.swift").read_text()
        self.assertIn("host.close()", session)

    def test_release_selects_the_native_producers_exact_xcode(self):
        script = (ROOT / "scripts/build_ios_native_core.sh").read_text()
        expected = re.search(r'XCODE_EXPECTED="\$\{ARCHIVETUNE_XCODE_VERSION:-([\d.]+)\}"', script)
        self.assertIsNotNone(expected)
        release = (ROOT / ".github/workflows/_release-artifacts.yml").read_text()
        ios_job = release.split("\n  ios:\n", 1)[1].split("\n  publish-release:\n", 1)[0]
        self.assertIn(f"ARCHIVETUNE_XCODE_VERSION: '{expected.group(1)}'", ios_job)
        self.assertIn("uses: maxim-lobanov/setup-xcode@v1", ios_job)
        self.assertEqual(1, ios_job.count("uses: maxim-lobanov/setup-xcode@v1"))
        self.assertEqual(1, ios_job.count("ARCHIVETUNE_XCODE_VERSION:"))
        self.assertIn("xcode-version: ${{ env.ARCHIVETUNE_XCODE_VERSION }}", ios_job)
        self.assertLess(ios_job.index("setup-xcode@v1"), ios_job.index("bash scripts/build_ios_native_core.sh"))

    def test_only_portable_pcm_core_drives_audio_and_native_callback(self):
        audio = (IOS / "Native/ATNativeAudio.mm").read_text()
        callback = audio.split("renderBlock:", 1)[1].split("}];", 1)[0]
        self.assertIn("ae_engine_render_realtime(p->core", callback)
        self.assertNotRegex(callback, r"\bae_engine_render\s*\(")
        self.assertNotRegex(callback, r"\b(?:malloc|calloc|new|sleep|dispatch_sync)\s*[\[(]")
        self.assertIn("p->consuming.store(decoded > 0)", callback)
        self.assertIn("!p->consuming.load()", audio)
        self.assertIn("AVAudioSessionInterruptionNotification", audio)
        self.assertIn("AVAudioSessionRouteChangeNotification", audio)
        adapter = (KOTLIN / "IosPlaybackPort.kt").read_text()
        self.assertIn("EnginePlaybackMapper()", adapter)
        self.assertNotRegex(adapter, r"positionMs\s*(?:\+=|\+\+)")
        self.assertIn("at_audio_destroy", adapter)

    def test_platform_ports_are_the_m7_interfaces(self):
        for filename, interface in [
            ("IosKeychainCredentialStore.kt", "SecureCredentialStore"),
            ("IosBrowserAuth.kt", "BrowserAuthPort"),
            ("IosPlatformFiles.kt", "PlatformFiles"),
        ]:
            source = (KOTLIN / filename).read_text()
            self.assertIn(": " + interface, source)
            self.assertNotIn("interface " + interface, source)
        credentials = (KOTLIN / "IosKeychainCredentialStore.kt").read_text()
        self.assertIn("kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly", credentials)
        self.assertIn("key.provider, key.scope.name, key.name", credentials)
        self.assertNotIn("NSUserDefaults", credentials)

    def test_now_playing_uses_change_projection_and_bounded_cancellable_artwork(self):
        controls = (KOTLIN / "IosNowPlaying.kt").read_text()
        self.assertIn("next.changesFrom(projection)", controls)
        self.assertIn("if (!changes.publishInfo && !artworkChanged) return", controls)
        self.assertIn("MPNowPlayingInfoMediaTypeAudio", controls)
        self.assertIn("MPMediaItemPropertyArtwork", controls)
        self.assertIn("MPMediaItemArtwork(boundsSize = image.size", controls)
        self.assertIn("artworkLoad?.cancel()", controls)
        self.assertIn("generation != artworkGeneration || key != artworkKey", controls)
        artwork = (KOTLIN / "IosNowPlayingArtwork.kt").read_text()
        self.assertIn("MAX_ARTWORK_BYTES.toULong() - bytes.length", artwork)
        self.assertIn("ARTWORK_EDGE_PIXELS = 512", artwork)
        self.assertIn("CGImageSourceCreateThumbnailAtIndex", artwork)
        self.assertIn("continuation.invokeOnCancellation", artwork)
        self.assertIn("session.invalidateAndCancel()", artwork)
        self.assertIn("HTTPCookieStorage = null", artwork)

    def test_long_form_playback_restores_the_previous_route_sharing_policy(self):
        audio = (IOS / "Native/ATNativeAudio.mm").read_text()
        self.assertEqual(2, audio.count("routeSharingPolicy:AVAudioSessionRouteSharingPolicyLongFormAudio"))
        self.assertIn("g_snapshot_policy = session.routeSharingPolicy", audio)
        self.assertIn("routeSharingPolicy:g_snapshot_policy", audio)
        self.assertIn("routeSharingPolicy:AVAudioSessionRouteSharingPolicyDefault", audio)

    def test_airplay_picker_is_a_native_control_in_the_ios_output_slot(self):
        picker = (UI_IOS / "IosAudioRoutePicker.kt").read_text()
        self.assertIn("fun createIosAudioRoutePicker(): UIView", picker)
        self.assertIn("AVRoutePickerView(frame", picker)
        self.assertIn("prioritizesVideoDevices = false", picker)
        self.assertIn("factory = ::createIosAudioRoutePicker", picker)
        self.assertIn("UIKitInteropInteractionMode.NonCooperative", picker)
        self.assertIn("isNativeAccessibilityEnabled = true", picker)
        bindings = (UI_IOS / "IosHostBindings.kt").read_text()
        self.assertIn("outputButton = { modifier, tint, enabled -> IosAudioRoutePicker(modifier, tint, enabled) }", bindings)
        style = (UI_COMMON / "playerstyles/PlayerStyleParts.kt").read_text()
        self.assertIn("if (action == PlayerAction.OUTPUT && outputButton != null)", style)
        self.assertIn("outputButton = base.outputButton", (UI_COMMON / "player/PlayerHandoffArtwork.kt").read_text())
        self.assertIn("-framework AVKit", (IOS / "Configuration/Native.xcconfig").read_text())

    def test_recognition_allows_airplay_and_only_device_removal_pauses(self):
        audio = (IOS / "Native/ATNativeAudio.mm").read_text()
        options = audio.split("recording_options(void)", 1)[1].split("\n}", 1)[0]
        self.assertIn("AVAudioSessionCategoryOptionAllowAirPlay", options)
        self.assertIn("AVAudioSessionCategoryOptionAllowBluetoothA2DP", options)
        route = audio.split("p->route =", 1)[1].split("}];", 1)[0]
        self.assertIn("if (reason == AVAudioSessionRouteChangeReasonOldDeviceUnavailable) at_audio_pause(output)", route)
        self.assertEqual(1, route.count("at_audio_pause(output)"))

    def test_reported_latency_reaches_only_the_lyric_offset(self):
        audio = (IOS / "Native/ATNativeAudio.mm").read_text()
        latency = audio.split("double at_audio_output_latency(", 1)[1].split("\n}", 1)[0]
        self.assertIn("[AVAudioSession sharedInstance].outputLatency", latency)
        self.assertIn("p->graph.outputNode.presentationLatency", latency)
        self.assertIn("std::max(latency, presentation)", latency)
        adapter = (KOTLIN / "IosPlaybackPort.kt").read_text()
        self.assertIn("outputLatencyMillis(at_audio_output_latency(native))", adapter)
        clock = (UI_COMMON / "lyrics/LyricsClock.kt").read_text()
        self.assertIn("feed - outputDelayMs", clock)
        self.assertIn("change != LyricsAnchorChange.STALE", clock)
        timeline = (UI_COMMON / "lyrics/NativeLyricsFeed.kt").read_text()
        self.assertIn("private val lyricsClock = LyricsClock()", timeline)
        self.assertIn("val feed = lyricsClock.feedMs.longValue", timeline)
        self.assertNotIn("outputLatencyOffsetMs(", timeline)
        bindings = (UI_IOS / "IosHostBindings.kt").read_text()
        self.assertNotIn("rememberIosLyricsSyncOffsetMs", bindings)

    def test_swiftui_is_only_the_scene_and_compose_controller_shell(self):
        sources = {path.name: path.read_text() for path in (IOS / "ArchiveTune").glob("*.swift")}
        self.assertEqual(["ArchiveTuneApp.swift"], sorted(
            name for name, source in sources.items() if re.search(r"^import SwiftUI$", source, re.MULTILINE)
        ))
        app = sources["ArchiveTuneApp.swift"]
        for entry in ["WindowGroup", "UIViewControllerRepresentable", ".ignoresSafeArea()", ".onOpenURL",
                      ".onContinueUserActivity(NSUserActivityTypeBrowsingWeb)", "activity.webpageURL"]:
            self.assertIn(entry, app)
        for source in sources.values():
            self.assertNotRegex(source, r"\b(?:UIHostingController|TextRenderer|GlassEffectContainer|TimelineView)\b")
            self.assertNotRegex(source, r"@(?:StateObject|ObservedObject|Environment|Published)\b")
        self.assertNotRegex(app, r"\b(?:Text|Button|ZStack|HStack|VStack|NativeChromeHost)\s*[({]")

    def test_native_uikit_chrome_and_lyrics_share_one_kotlin_host(self):
        session = (IOS / "ArchiveTune/ArchiveTuneSession.swift").read_text()
        self.assertEqual(1, session.count("IosHost()"))
        self.assertIn("if #available(iOS 26.0, *)", session)
        self.assertIn("NativeChromeController(compose: host.viewController, chrome: host.nativeChrome)", session)
        self.assertIn("host.setNativeLyricsFactory", session)
        self.assertIn("SystemLyrics.makeController(lyrics)", session)
        self.assertIn("controller.willMove(toParent: nil)", session)
        self.assertIn("if presenter === token", session)
        self.assertIn("host.foregrounded()", session)
        self.assertIn("host.backgrounded()", session)
        project = (IOS / "ArchiveTune.xcodeproj/project.pbxproj").read_text()
        build_files = dict(re.findall(r"(\w+) = \{isa = PBXBuildFile; fileRef = (\w+);", project))
        sources = re.search(r"isa = PBXSourcesBuildPhase;[^\n]+files = \(([^)]+)\)", project).group(1)
        for path in (IOS / "ArchiveTune").glob("*.swift"):
            file_id = re.search(rf"(\w+) = \{{isa = PBXFileReference;[^\n]+path = ArchiveTune/{path.name};", project).group(1)
            build_id = next(key for key, value in build_files.items() if value == file_id)
            self.assertIn(build_id, sources, path.name)

    def test_uikit_glass_keeps_geometry_touch_and_kotlin_command_ownership(self):
        chrome = (IOS / "ArchiveTune/NativeChrome.swift").read_text()
        for expected in ["UIGlassEffect(style: .regular)", "UIGlassContainerEffect()", "effect.spacing = 14",
                         "effect.isInteractive = true", "addChild(compose)", "compose.didMove(toParent: self)",
                         "childForStatusBarHidden", "childForStatusBarStyle", "min(520, view.bounds.width - 36)",
                         "glass.isUserInteractionEnabled = next.visible", "override func hitTest",
                         "chrome.setBottomInset(points:", "chrome.setSupported(value: true)",
                         "chrome.setSupported(value: false)", "chrome.togglePlayback()", "chrome.expandPlayer()",
                         "chrome.selectTab(index: 0)", "chrome.selectTab(index: 1)", "chrome.selectTab(index: 2)"]:
            self.assertIn(expected, chrome)
        self.assertIn("artwork.isUserInteractionEnabled = false", chrome)
        self.assertIn("UIAccessibility.isReduceMotionEnabled", chrome)

    def test_native_lyrics_motion_dynamic_type_and_completion(self):
        swift = (IOS / "ArchiveTune/SystemLyricsRendering.swift").read_text()
        for expected in ["UIFontMetrics(forTextStyle:", "scaledValue(for:",
                         "LyricsSweepGeometry.shared.progressAt(", "LyricsEmphasis.shared.bumpAt(",
                         "LyricsSweepGeometry.shared.sweepComplete(", "LyricsSweepGeometry.shared.dropAt(",
                         "wordEndMs: word.endMs", "reduceMotion: reduceMotion", "NSLayoutManager()",
                         ".destinationIn", "wrappedLetters", "CIGaussianBlur", "line.secondVoice != line.rightToLeft"]:
            self.assertIn(expected, swift)
        self.assertIn("changed: old?.phonetic != line.phonetic", swift)
        self.assertIn("changed: old?.translation != line.translation", swift)
        self.assertIn("SystemLyricsMetrics.revealSeconds", swift)

    def test_uikit_lyrics_lifecycles_follow_hold_seeking_sharing_and_credits(self):
        swift = (IOS / "ArchiveTune/SystemLyrics.swift").read_text()
        for expected in ["CADisplayLink(target: displayTarget", "frame.advancing && !reduceMotion",
                         "displayed && bridgeVisible", "displayLink?.invalidate()", "heldTimer?.invalidate()",
                         "heldPollSeconds = 0.25", "lyrics.frame()", "frame.discontinuity", "frame.reanchor",
                         "document?.manualScrollHoldMs", "resumeGeneration == generation", "lyrics.seekTo(index:",
                         "lyrics.shareText(index:", "UIActivityViewController(activityItems: [text]",
                         "popoverPresentationController?.sourceView", "UIAccessibilityCustomAction(name: shareLabel)",
                         "contextMenuConfigurationForRowAt", "credits.writtenBy", "credits.autoCreatedLabel"]:
            self.assertIn(expected, swift)
        self.assertNotRegex(swift, r"positionMs\s*(?:\+=|=\s*.*CACurrentMediaTime)")
        surface = (UI_IOS / "IosNativeLyricsSurface.kt").read_text()
        self.assertIn("stringResource(Res.string.lyrics_share)", surface)
        self.assertIn("UIKitInteropInteractionMode.NonCooperative", surface)
        self.assertIn("isNativeAccessibilityEnabled = true", surface)

    def test_security_lifetimes_and_bounds_remain_present(self):
        auth = (KOTLIN / "IosBrowserAuth.kt").read_text()
        self.assertIn("prefersEphemeralWebBrowserSession = true", auth)
        self.assertIn("WKWebsiteDataStore.nonPersistentDataStore()", auth)
        self.assertIn("singleOrNull()?.value", auth)
        self.assertIn("withTimeout(120_000)", auth)
        files = (KOTLIN / "IosPlatformFiles.kt").read_text()
        self.assertIn("finally { url.stopAccessingSecurityScopedResource() }", files)
        self.assertIn("remaining + 1uL", files)
        self.assertIn("chunk.length <= remaining", files)
        self.assertIn("NSDataWritingAtomic", files)
        self.assertIn("NSFileCoordinator", files)
        self.assertNotIn("NSURLBookmarkCreationWithSecurityScope", files)

    def test_ledger_does_not_turn_source_into_an_apple_pass(self):
        status = (ROOT / "docs/engineering/ios-status.md").read_text()
        self.assertIn("dictionary host/settings/root composition remains required", status)
        self.assertIn("Authored but UNRUN", status)
        self.assertIn("unverified-on-linux-host", status)
        self.assertIn("does not establish compilation against an actual Apple SDK", status)


if __name__ == "__main__":
    unittest.main()
