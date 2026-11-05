"""Host/source relationships only; these tests do not compile or execute Apple APIs."""
from pathlib import Path
import plistlib
import re
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
IOS = ROOT / "iosApp"
KOTLIN = ROOT / "shared-data/src/iosMain/kotlin/moe/rukamori/archivetune/shared/data/platform/ios"
UI_ROOT = ROOT / "shared-ui/src/iosMain/kotlin/moe/rukamori/archivetune/shared/ui"
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

    def test_shared_compose_screens_own_the_ios_app_ui(self):
        session = (IOS / "ArchiveTune/ArchiveTuneSession.swift").read_text()
        self.assertEqual(1, session.count("IosHost()"))
        self.assertIn("IosRootController(", session)
        self.assertIn("compose: host.viewController", session)
        self.assertIn("safeArea: host.safeArea", session)
        self.assertIn("presenter === token", session)
        self.assertIn("controller.willMove(toParent: nil)", session)
        self.assertIn("host.foregrounded()", session)
        self.assertIn("host.backgrounded()", session)
        self.assertIn("addChild(compose)", session)
        # No second renderer: the Swift host composes no app chrome and no lyric view.
        for removed in ["NativeChromeController", "setNativeLyricsFactory", "SystemLyrics",
                        "UIGlassEffect", "UIGlassContainerEffect", "UITableView", "CADisplayLink",
                        "UIActivityViewController"]:
            self.assertNotIn(removed, session)
        for gone in ["ArchiveTune/NativeChrome.swift", "ArchiveTune/SystemLyrics.swift",
                     "ArchiveTune/SystemLyricsRendering.swift"]:
            self.assertFalse((IOS / gone).exists(), gone)
        for gone in ["IosNativeChrome.kt", "IosNativeLyrics.kt", "ios/IosNativeLyricsSurface.kt",
                     "navigation/NativeShellChrome.kt"]:
            self.assertFalse((UI_ROOT / gone).exists(), gone)
        kotlin = (UI_ROOT / "IosHost.kt").read_text()
        self.assertNotRegex(kotlin, r"nativeChrome|IosNativeLyrics|NativeIosInterfaceKey")
        bindings = (UI_IOS / "IosHostBindings.kt").read_text()
        self.assertNotRegex(bindings, r"nativeChrome|IosNativeLyricsSurface|NativeIosInterfaceKey")
        # Every lyric look and every tab bar comes from the shared shell.
        self.assertIn("val fullLyrics = lyrics.full", bindings)
        shell = (UI_COMMON / "navigation/ArchiveTuneShell.kt").read_text()
        self.assertIn("FloatingNavigationToolbar(", shell)
        self.assertNotRegex(shell, r"nativeChrome|nativeYields|bottomInsetDp")
        self.assertNotRegex((UI_COMMON / "ArchiveTuneRoot.kt").read_text(), r"nativeChrome|NativeChromeLink")

        project = (IOS / "ArchiveTune.xcodeproj/project.pbxproj").read_text()
        build_files = dict(re.findall(r"(\w+) = \{isa = PBXBuildFile; fileRef = (\w+);", project))
        sources = re.search(r"isa = PBXSourcesBuildPhase;[^\n]+files = \(([^)]+)\)", project).group(1)
        for path in (IOS / "ArchiveTune").glob("*.swift"):
            file_id = re.search(rf"(\w+) = \{{isa = PBXFileReference;[^\n]+path = ArchiveTune/{path.name};", project).group(1)
            build_id = next(key for key, value in build_files.items() if value == file_id)
            self.assertIn(build_id, sources, path.name)
        # One object graph, one owner per ID: the audio bridge owns 0A/22/23, the app session owns 0C/24, and the
        # audio headers own 25/26. A copy-paste of either patch reusing one of these IDs fails here.
        for build_id, file_id in [
            ("A0000000000000000000000A", "A00000000000000000000022"),
            ("A0000000000000000000000C", "A00000000000000000000024"),
        ]:
            self.assertEqual(1, project.count(f"{build_id} = {{isa = PBXBuildFile; fileRef = {file_id}; }}"))
            self.assertIn(build_id, sources)
        for file_id, path in [
            ("A00000000000000000000022", "Native/ATAudioGraph.mm"),
            ("A00000000000000000000023", "Native/ATAudioGraph.h"),
            ("A00000000000000000000024", "ArchiveTune/ArchiveTuneSession.swift"),
            ("A00000000000000000000025", "Native/ATAudioException.h"),
            ("A00000000000000000000026", "Native/ATAudioFormat.h"),
        ]:
            self.assertEqual(1, project.count(f"path = {path};"))
            self.assertRegex(project, rf"{file_id} = {{isa = PBXFileReference;[^\n]*path = {path};")
        defined = re.findall(r"^        (A[0-9A-F]{23}) = {", project, re.M)
        self.assertEqual(sorted(defined), sorted(set(defined)))

    def test_safe_area_reaches_compose_once_and_pads_the_whole_app(self):
        session = (IOS / "ArchiveTune/ArchiveTuneSession.swift").read_text()
        self.assertIn("override func viewSafeAreaInsetsDidChange()", session)
        self.assertIn("safeArea.update(left: Double(insets.left), top: Double(insets.top)", session)
        safe_area = (UI_ROOT / "IosSafeArea.kt").read_text()
        self.assertIn("fun update(left: Double, top: Double, right: Double, bottom: Double)", safe_area)
        self.assertIn(".sanitized()", safe_area)
        root = (UI_IOS / "IosMountedRoot.kt").read_text()
        self.assertIn("host.safeArea.padding.collectAsState()", root)
        self.assertIn("safeArea = safeArea", root)
        owner = (UI_COMMON / "SafeAreaPadding.kt").read_text()
        # Physical edges, and the same bar types consumed: a screen cannot reserve them twice.
        self.assertIn("absolutePadding(", owner)
        self.assertIn("consumeWindowInsets(WindowInsets.systemBars)", owner)
        self.assertNotIn("start = insets.left.dp", owner)
        # The zero path must stay a real no-op for the hosts that own their own bar insets.
        self.assertIn("@Composable\nfun Modifier.safeAreaPadding(insets: SafeAreaPadding): Modifier =\n    if (!insets.needsRootPadding()) this", owner)
        self.assertIn("internal fun SafeAreaPadding.needsRootPadding(): Boolean = hasInsets", owner)
        self.assertIn("safeArea: SafeAreaPadding = SafeAreaPadding.None", (UI_COMMON / "ArchiveTuneRoot.kt").read_text())
        self.assertIn(".safeAreaPadding(safeArea)", (UI_COMMON / "ArchiveTuneRoot.kt").read_text())

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

    def test_audio_graph_canonical_source_and_hardware_boundary(self):
        audio = (IOS / "Native/ATNativeAudio.mm").read_text()
        self.assertNotIn("interleaved:YES", audio)
        self.assertIn("initStandardFormatWithSampleRate:AT_AUDIO_PCM_RATE channels:2", audio)
        self.assertIn("at_audio_canonical_format(hardware.sampleRate, hardware.channelCount", audio)
        self.assertIn("buffers->mNumberBuffers != 2", audio)
        self.assertIn("at_audio_deinterleave(p->scratch", audio)
        self.assertIn("connect:p->source to:p->converter format:pcm", audio)
        self.assertIn("connect:p->converter to:p->time_pitch format:canonical", audio)
        create = audio.split("static at_audio_output *create_output(", 1)[1].split("at_audio_output *at_audio_create(", 1)[0]
        self.assertIn("configure_playback_locked() && build_graph(p, plain)", create)
        self.assertIn("at_audio_destroy(p)", create)

    def test_apple_exception_boundary_and_fallback_are_shared_by_both_consumers(self):
        boundary = (IOS / "Native/ATAudioException.h").read_text()
        self.assertIn("@try", boundary)
        self.assertIn("@catch (NSException *exception)", boundary)
        self.assertNotIn("exception.reason", boundary)
        graph = (IOS / "Native/ATAudioGraph.mm").read_text()
        self.assertIn("audio_try(^BOOL", graph)
        blend = (KOTLIN / "IosBlendAudioGraph.kt").read_text()
        self.assertNotRegex(blend, r"engine\.(?:attachNode|connect|detachNode|prepare|startAndReturnError|pause|stop)\(")
        self.assertNotRegex(blend, r"node\.(?:scheduleBuffer|play|playAtTime|pause|stop)\(")
        self.assertIn("at_graph_connect(engine, node, converter, format)", blend)
        self.assertIn("at_graph_connect(engine, converter, pitch, canonical)", blend)
        factory = (KOTLIN / "IosAudioOutputFactory.kt").read_text()
        self.assertIn("primary = { at_audio_create(error, 256uL) }", factory)
        self.assertIn("plain = { at_audio_create_plain(error, 256uL) }", factory)
        self.assertIn("createIosAudioOutput", (KOTLIN / "IosBlendSource.kt").read_text())
        port = (KOTLIN / "IosPlaybackPort.kt").read_text()
        self.assertIn("createIosAudioOutput { outputCreationError = it }", port)
        self.assertIn("reason == PlaybackFailureReason.OUTPUT_FAILED", port)

    def test_route_rebuild_retires_native_callbacks_and_keeps_unplayed_blend_pcm(self):
        audio = (IOS / "Native/ATNativeAudio.mm").read_text()
        self.assertIn("output->graph_generation == generation", audio)
        self.assertIn("invalidate_graph(output)", audio)
        self.assertIn("p->render_active.load() == 0 && p->retired_source == nil", audio)
        self.assertIn("p->requires_recreation = true", audio)
        self.assertIn("rebuild_graph(p, true)", audio)
        blend = (KOTLIN / "IosBlendAudioGraph.kt").read_text()
        self.assertIn("engine === observedEngine", blend)
        self.assertIn("rebuildPreservingPcm()", blend)
        self.assertIn("current.reschedule()", blend)
        self.assertIn("canonical = checkNotNull(at_graph_output_format(engine))", blend)
        self.assertIn("chunk.span.positionUs(chunk.span.startFrame + skip)", blend)
        reschedule = blend.split("fun reschedule(): Boolean {", 1)[1].split("fun detachNodes()", 1)[0]
        self.assertIn("val replacements = ArrayDeque<Chunk>()", reschedule)
        self.assertIn("queued.addAll(replacements)", reschedule)
        self.assertLess(reschedule.index("at_graph_schedule(node"), reschedule.index("queued.clear()"))
        self.assertLess(reschedule.index("at_graph_schedule(node"), reschedule.index("playedFrame = 0L"))

    def test_native_bridge_is_built_and_tested_not_only_header_parsed(self):
        project = (IOS / "ArchiveTune.xcodeproj/project.pbxproj").read_text()
        self.assertIn("path = Native/ATAudioGraph.mm", project)
        self.assertIn("A0000000000000000000000A = {isa = PBXBuildFile;", project)
        sources = project.split("isa = PBXSourcesBuildPhase", 1)[1].split("};", 1)[0]
        self.assertIn("A0000000000000000000000A", sources)
        build = (ROOT / "shared-data/build.gradle.kts").read_text()
        self.assertIn('cinterops.create("archivetuneGraph")', build)
        self.assertIn("archivetuneGraph.def", build)
        workflow = (ROOT / ".github/workflows/ios_compile.yml").read_text()
        self.assertIn("iosApp/Native/ATNativeAudio.mm iosApp/Native/ATAudioGraph.mm", workflow)
        self.assertIn("-fsyntax-only", workflow)
        self.assertIn("iosApp/tests/audio_format_test.c", workflow)
        self.assertIn("iosApp/tests/audio_exception_test.mm", workflow)
        self.assertIn("python3 -m unittest discover -s iosApp/tests", workflow)

    def test_blend_failed_attachment_and_queued_resume_are_contained(self):
        blend = (KOTLIN / "IosBlendAudioGraph.kt").read_text()
        self.assertIn("Deck(output, 0f)", blend)
        self.assertIn("if (suspended) { dropIncoming(); return false }", blend)
        self.assertIn("epoch == resumeEpoch && current.nativeState().playWhenReady", blend)
        self.assertIn("if (graphDirty && !rebuildWithFallback())", blend)
        self.assertIn("if (!stopped || !currentDetached || !incomingDetached) return false", blend)
        self.assertIn("if (!stopped || !incomingDetached || !currentDetached) at_audio_manual_output_failed", blend)
        self.assertIn("incoming?.let { if (at_audio_play(it.output) == 0)", blend)
        self.assertIn("graph.suspended = false", blend)
        self.assertNotIn("if (deck.gainIsAudible) deck.start()", blend)
        downstream = (KOTLIN / "IosPlaybackBlend.kt").read_text()
        self.assertIn("if (!graph.promote())", downstream)

    def test_shared_preamp_and_now_playing_share_reach_the_ios_host(self):
        header = (IOS / "Native/ATNativeAudio.h").read_text()
        self.assertIn("void at_audio_preamp(at_audio_output *output, float gain_db)", header)
        audio = (IOS / "Native/ATNativeAudio.mm").read_text()
        self.assertIn("p->equalizer.globalGain = clamped(p->preamp_db, -24.0f, 24.0f)", audio)
        graph = (IOS / "Native/ATAudioGraph.mm").read_text()
        self.assertIn("unit.globalGain = bounded(gain_db, -24.0f, 24.0f)", graph)
        bands = (KOTLIN / "IosEqBands.kt").read_text()
        self.assertIn("if (preampEnabled) preampDb.toFloat() else 0f", bands)
        self.assertIn("at_audio_preamp(output, preampDb)", bands)
        blend = (KOTLIN / "IosBlendAudioGraph.kt").read_text()
        self.assertIn("at_graph_eq_gain(eq, preampDb)", blend)
        controls = (UI_IOS / "IosPlayerControls.kt").read_text()
        self.assertIn("PlayerAction.SHARE -> share(occurrenceId)", controls)
        self.assertIn("iosShareText(host, link)", controls)
        self.assertIn("NOTICE_SHARE_UNAVAILABLE", controls)
        menu = (UI_IOS / "IosSongMenu.kt").read_text()
        self.assertIn("import moe.rukamori.archivetune.shared.ui.resources.ios_share_link_unavailable", menu)
        self.assertIn("Res.string.ios_share_link_unavailable", menu)

    def test_ledger_does_not_turn_source_into_an_apple_pass(self):
        status = (ROOT / "docs/engineering/ios-status.md").read_text()
        self.assertIn("dictionary host/settings/root composition remains required", status)
        self.assertIn("Authored but UNRUN", status)
        self.assertIn("unverified-on-linux-host", status)
        self.assertIn("does not establish compilation against an actual Apple SDK", status)


if __name__ == "__main__":
    unittest.main()
