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
                "discord-1569030685010821170",
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

    def test_interruption_is_published_paused_and_resumes_only_approved_intent(self):
        audio = (IOS / "Native/ATNativeAudio.mm").read_text()
        began = audio.split("AVAudioSessionInterruptionTypeBegan", 1)[1].split("} else {", 1)[0]
        self.assertIn("const bool resumeIntent = output->wants_play.load()", began)
        self.assertIn("at_audio_pause(output)", began)
        self.assertIn("output->resume_after_interruption.store(resumeIntent)", began)
        ended = audio.split("resume_after_interruption.exchange(false)", 1)[1].split("});", 1)[0]
        self.assertIn("AVAudioSessionInterruptionOptionShouldResume", ended)
        self.assertIn("if (resume) at_audio_play(output); else at_audio_pause(output);", ended)
        play = audio.split("int at_audio_play", 1)[1].split("\n}", 1)[0]
        self.assertIn("if (!reclaim_interrupted_session(p))", play)
        self.assertIn("p->resume_after_interruption.store(true)", play)
        self.assertIn("return AT_AUDIO_PLAY_DEFERRED;", play)
        self.assertIn("return start_output(p) ? 1 : 0;", play)
        self.assertNotIn("return 1;", play)
        pause = audio.split("void at_audio_pause", 1)[1].split("\n}", 1)[0]
        self.assertIn("p->resume_after_interruption.store(false)", pause)
        snapshot = audio.split("void at_audio_snapshot", 1)[1].split("\n}", 1)[0]
        self.assertIn("if (p->interrupted.load()) snapshot->flags &= ~AE_FLAG_PLAY_WHEN_READY;", snapshot)
        blend = (KOTLIN / "IosBlendAudioGraph.kt").read_text()
        interruption = blend.split("AVAudioSessionInterruptionNotification", 1)[1].split("observers +=", 1)[0]
        self.assertIn("dispatch_async(dispatch_get_main_queue())", interruption)
        self.assertIn("current.nativeState().playWhenReady", interruption)
        self.assertNotIn("ShouldResume != 0uL && current.nativeState().playWhenReady", interruption)

    def test_an_explicit_play_reclaims_a_session_whose_interruption_never_ended(self):
        audio = (IOS / "Native/ATNativeAudio.mm").read_text()
        reclaim = audio.split("static bool reclaim_interrupted_session", 1)[1].split("\n}", 1)[0]
        self.assertIn("configure_playback_locked()", reclaim)
        self.assertIn("p->interrupted.store(false)", reclaim)
        play = audio.split("int at_audio_play", 1)[1].split("\n}", 1)[0]
        self.assertIn("if (!reclaim_interrupted_session(p))", play)
        for loader in ("uint64_t at_audio_load(", "uint64_t at_audio_load_stream("):
            body = audio.split(loader, 1)[1].split("\n}", 1)[0]
            self.assertIn("reclaim_interrupted_session(p)) start_output(p)", body)
        ended = audio.split("AVAudioSessionInterruptionTypeBegan", 1)[1].split("} else {", 1)[1].split("});", 1)[0]
        self.assertIn("if (!output->interrupted.exchange(false)) return;", ended)
        blend = (KOTLIN / "IosBlendAudioGraph.kt").read_text()
        play = blend.split("fun play(): Boolean {", 1)[1].split("private fun startEngine", 1)[0]
        reclaim = play.split("if (interrupted) {", 1)[1].split("interrupted = false", 1)[0]
        self.assertIn("val reclaim = at_audio_play(current.output)", reclaim)
        self.assertIn("if (reclaim == 0) return false", reclaim)
        self.assertIn("if (reclaim == AT_AUDIO_PLAY_DEFERRED.toInt() || !current.nativeState().playWhenReady) return true", reclaim)
        self.assertIn("interrupted = false", play)
        header = (IOS / "Native/ATNativeAudio.h").read_text()
        self.assertIn("#define AT_AUDIO_PLAY_DEFERRED 2", header)
        blend_ended = blend.split("AVAudioSessionInterruptionTypeBegan", 1)[1].split("} else {", 1)[1]
        self.assertIn("if (!interrupted) return@addObserverForName", blend_ended.split("val options", 1)[0])

    def test_blend_keeps_the_prepared_successor_through_an_interruption(self):
        blend = (KOTLIN / "IosBlendAudioGraph.kt").read_text()
        self.assertIn("private var interrupted = false", blend)
        self.assertIn("private var heldAfterInterruption = false", blend)
        began = blend.split("AVAudioSessionInterruptionTypeBegan", 1)[1].split("} else {", 1)[0]
        self.assertIn("interrupted = true", began)
        self.assertIn("heldAfterInterruption = false", began)
        ended = blend.split("val approved = options", 1)[1].split("if (approved)", 1)[0]
        self.assertIn("heldAfterInterruption = true", ended)
        handoff = (KOTLIN / "IosPlaybackBlend.kt").read_text()
        poll = handoff.split("fun poll(nowNanos: Long): Read {", 1)[1].split("val source = next", 1)[0]
        self.assertIn("graph.isInterrupted || graph.isHeldAfterInterruption", poll)

    def test_host_close_makes_the_queue_durable_before_it_returns(self):
        host = (UI_IOS.parent / "IosHost.kt").read_text()
        close = host.split("fun close()", 1)[1].split("private fun runDurably", 1)[0]
        self.assertIn("closeLock.lock()", close)
        self.assertIn("runDurably(QUEUE_CHECKPOINT_TIMEOUT_MS)", close)
        self.assertIn("runDurably(GRAPH_CLOSE_TIMEOUT_MS)", close)
        self.assertIn("openedGraph.checkpointQueue()", close)
        self.assertIn("openedGraph.close()", close)
        self.assertNotIn("openedGraph?.close()", close)
        self.assertLess(close.index("runDurably(QUEUE_CHECKPOINT_TIMEOUT_MS)"), close.index("nowPlaying?.close()"))
        background = host.split("fun backgrounded()", 1)[1].split("fun openLink", 1)[0]
        self.assertIn("opened.checkpointQueue()", background)
        self.assertIn("runDurably(BACKGROUND_CHECKPOINT_TIMEOUT_MS)", background)
        graph = (UI_IOS / "IosSessionGraph.kt").read_text()
        self.assertIn("suspend fun checkpointQueue()", graph)
        helper = host.split("private fun runDurably", 1)[1]
        self.assertIn("CoroutineScope(SupervisorJob() + Dispatchers.Default).launch", helper)
        self.assertIn("runBlocking(NonCancellable)", helper)
        self.assertIn("withTimeoutOrNull(timeoutMs) { work.join() }", helper)

    def test_fatal_exception_record_is_a_prewarmed_bounded_byte_formatter(self):
        session = (IOS / "ArchiveTune/ArchiveTuneSession.swift").read_text()
        fatal = session.split("private enum IosFatalExceptionRecord", 1)[1].split("\n}\n", 1)[0]
        # The old shape used C-variadic snprintf, which Swift imports as unavailable, and its
        # static buffer was allocated lazily on the first fatal record. These checks pin the
        # bounded byte formatter and the launch-time initialization that replaces both.
        self.assertNotIn("snprintf(", fatal)
        self.assertNotIn("%lld", fatal)
        self.assertNotIn("String(format:", fatal)
        self.assertNotIn("withVaList", fatal)
        self.assertNotIn("strlen(", fatal)
        self.assertNotIn("abs(", fatal)
        record = fatal.split("static func record", 1)[1]
        self.assertNotIn("withCString", record)
        self.assertIn("import Darwin", session)
        self.assertIn("private static let capacity = 4096", fatal)
        prepare = fatal.split("static func prepare()", 1)[1].split("static func record", 1)[0]
        self.assertIn("buffer.initialize(repeating: 0, count: capacity)", prepare)
        self.assertLess(prepare.index("buffer.initialize"), prepare.index("open("))
        # The record sequence is epoch seconds, the fixed prefix, bounded name, bounded reason.
        self.assertIn("writeInt64(Int64(time(nil))", record)
        self.assertIn("private static let exceptionPrefix: StaticString", fatal)
        self.assertIn("writeStaticString(exceptionPrefix", record)
        self.assertIn("private static let nameByteLimit = 200", fatal)
        self.assertIn("private static let reasonByteLimit = 2000", fatal)
        self.assertIn("writeUTF8(exception.name.rawValue.utf8, limit: nameByteLimit", record)
        self.assertIn("writeUTF8(reason.utf8, limit: reasonByteLimit", record)
        self.assertIn("buffer[used] = 0", record)
        self.assertIn("write(descriptor, UnsafeRawPointer(buffer), used)", record)
        self.assertIn("guard descriptor >= 0 else { return }", record)
        self.assertIn("ftruncate(descriptor, 0)", record)
        self.assertIn("lseek(descriptor, 0, SEEK_SET)", record)
        # Bounds: the byte writers reset at NUL, at the field limit and before the final NUL slot.
        self.assertIn("if byte == 0 || copied >= limit || used >= capacity - 1 { return }", fatal)
        self.assertIn("guard used < capacity - 1 else { return }", fatal)
        self.assertIn("while index < value.utf8CodeUnitCount", fatal)
        # Signed epochs use magnitude, so Int64.min cannot overflow a negation.
        self.assertIn("let magnitude = value.magnitude", fatal)
        self.assertIn("while divisor <= magnitude / 10 { divisor *= 10 }", fatal)
        self.assertIn("divisor /= 10", fatal)
        # One existing launch-time descriptor/record route, prepared before the handler can fire.
        self.assertEqual(1, session.count("NSSetUncaughtExceptionHandler"))
        self.assertIn("IosFatalExceptionRecord.record(exception)", session)
        self.assertLess(session.index("IosFatalExceptionRecord.prepare()"), session.index("NSSetUncaughtExceptionHandler"))

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

    def test_remote_seeks_use_one_bounded_shared_policy(self):
        controls = (KOTLIN / "IosNowPlaying.kt").read_text()
        policy = (ROOT / "shared-data/src/commonMain/kotlin/moe/rukamori/archivetune/shared/data/playback/NowPlayingSeekPolicy.kt").read_text()
        # The scrubber sends an absolute position in seconds; the interval skip sends a relative one.
        # Both must go through the shared millisecond policy, not a raw * 1000 cast that can wrap, and
        # must share the on-screen controls' duration/overflow bounds.
        self.assertIn("nowPlayingSeekTargetMs", controls)
        self.assertEqual(2, controls.count("nowPlayingSeekTargetMs("))
        self.assertIn("fun nowPlayingSeekTargetMs(", policy)
        self.assertIn("deltaSeconds * 1_000.0", policy)
        self.assertIn("coerceIn(0.0, upper)", policy)
        self.assertIn("durationMs?.takeIf { it > 0L }", policy)
        # A live stream with no positive duration is unbounded, not clamped to zero.
        self.assertNotIn("coerceIn(0L, duration)", controls)

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
        self.assertIn("initStandardFormatWithSampleRate:p->pcm_rate channels:2", audio)
        self.assertIn("at_audio_canonical_format(hardware.sampleRate, hardware.channelCount", audio)
        self.assertIn("buffers->mNumberBuffers != 2", audio)
        self.assertIn("at_audio_deinterleave(p->scratch", audio)
        self.assertIn("connect:p->source to:p->converter format:pcm", audio)
        self.assertIn("connect:p->converter to:p->time_pitch format:canonical", audio)
        create = audio.split("static at_audio_output *create_output(", 1)[1].split("at_audio_output *at_audio_create(", 1)[0]
        self.assertLess(create.index("configure_playback_locked()"), create.index("accepted_pcm_rate()"))
        self.assertLess(create.index("accepted_pcm_rate()"), create.index("ae_engine_create("))
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

    def test_merged_android_lyrics_header_and_chrome_have_shared_counterparts(self):
        header = (UI_COMMON / "player/FullscreenLyricsChrome.kt").read_text()
        self.assertIn('val edge = text.height.coerceIn(0, available)', header)
        self.assertIn('Constraints.fixed(edge, edge)', header)
        self.assertNotIn('PlayerAction.LIKE', header)
        self.assertIn('PlayerSymbol.MORE', header)
        self.assertIn('PlayerSymbol.DOWN', header)
        player = (UI_COMMON / "player/BottomSheetPlayer.kt").read_text()
        self.assertIn('FullscreenLyricsChrome(c)', player)
        root = (UI_COMMON / "ArchiveTuneRoot.kt").read_text()
        self.assertIn('chromeVisible = !expanded', root)
        shell = (UI_COMMON / "navigation/ArchiveTuneShell.kt").read_text()
        self.assertIn('if (chromeVisible) Box', shell)
        self.assertIn('chromeVisible && frostedNavigationOnScreen', shell)

    def test_song_source_chooser_uses_persisted_pins_and_the_normal_resolver(self):
        source = (UI_IOS / "IosSongSourceDialog.kt").read_text()
        self.assertIn('PlayerQueueSongSourceDialog(track, state, actions', source)
        self.assertIn('SongSourceOverride.withOverride', source)
        self.assertIn('graph.retryAfterFailure(target.occurrenceId)', source)
        self.assertIn('searchableSources = setOf(AudioSourceType.YOUTUBE)', source)
        menu = (UI_IOS / "IosItemMenu.kt").read_text()
        self.assertIn('menu.sourceTarget?.let { IosSongSourceDialog', menu)
        resolver = (UI_IOS / "IosQueueLocatorResolver.kt").read_text()
        self.assertIn('if (explicitSource == null)', resolver)
        self.assertIn('recovery.pinned(pinned)', resolver)

    def test_player_actions_follow_the_playing_occurrence_and_have_hosts(self):
        controls = (UI_IOS / "IosPlayerControls.kt").read_text()
        self.assertIn('playerOccurrence(queue, playback)', controls)
        for action in ['TITLE', 'ARTIST', 'OUTPUT']:
            self.assertIn('PlayerAction.' + action + ' ->', controls)
        self.assertIn('shareLocalAudio(item.ref.id, null)', controls)
        menus = (UI_IOS / "IosSongMenu.kt").read_text()
        self.assertIn('if (ref.iosYouTubeSong()) add("speed_dial")', menus)
        self.assertIn('if (ref.iosYouTubeSong()) add("refetch")', menus)
        bindings = (UI_IOS / "IosHostBindings.kt").read_text()
        self.assertIn('IosPlayerNavigationDialogs(controls)', bindings)
        self.assertIn('itemMenu, openOwnRoute)', bindings)
        info = (UI_IOS / "IosFeaturePages.kt").read_text()
        self.assertIn('graph.library.findSong(mediaId, provider)', info)
        self.assertIn('playerOccurrence(queueState, playback)', info)
        self.assertIn('provider == null || it.item.ref.provider == provider', info)

    def test_existing_ios_editors_are_reachable_from_the_route_registry(self):
        lane = (UI_IOS / "IosLaneSettings.kt").read_text()
        routes = lane.split('internal val IOS_LANE_SETTINGS_ROUTES = setOf(', 1)[1].split('\n)', 1)[0]
        for path in ['settings/modules', 'settings/appearance/liquid_glass', 'settings/discord/experimental']:
            self.assertIn('"' + path + '"', routes)
            self.assertIn('"' + path + '" ->', lane)
        settings = (UI_IOS / "IosSettings.kt").read_text()
        self.assertIn('in IOS_LANE_SETTINGS_ROUTES -> IosLaneSettingsPage(', settings)

    def test_dsp_native_and_blend_keep_additional_filters_and_report_limits(self):
        header = (IOS / "Native/ATNativeAudio.h").read_text()
        self.assertIn('#define AT_AUDIO_EQ_MAX_BANDS 24', header)
        for name in ['ATNativeAudio.mm', 'ATAudioGraph.mm']:
            self.assertIn('AVAudioUnitEQFilterTypeHighPass', (IOS / "Native" / name).read_text())
        blend = (KOTLIN / "IosBlendAudioGraph.kt").read_text()
        self.assertIn('EQ_UNIT_BANDS = 24u', blend)
        port = (KOTLIN / "IosPlaybackPort.kt").read_text()
        self.assertIn('setTone(true, settings.toIosEqBands(), settings.iosInputGainDb)', port)
        settings = (UI_IOS / "IosPlaybackSettingsPage.kt").read_text()
        self.assertIn('"skip_silence", "audio_normalization", "audio_offload"', settings)
        self.assertIn('enabled = false', settings)
        self.assertIn('Res.string.ios_dsp_limits', settings)

    def test_widget_controls_embed_extension_and_use_authoritative_queue(self):
        project = (IOS / "ArchiveTune.xcodeproj/project.pbxproj").read_text()
        self.assertIn('productType = "com.apple.product-type.app-extension"', project)
        self.assertIn('name = "Embed App Extensions"', project)
        self.assertIn('dependencies = (B00000000000000000000036)', project)
        widget = (IOS / "Widgets/NatuneControls.swift").read_text()
        self.assertIn('.supportedFamilies([.systemSmall, .systemMedium])', widget)
        self.assertIn('natune://widget/', widget)
        self.assertIn('family == .systemSmall ? "natune://widget/toggle"', widget)
        self.assertIn('control("toggle"', widget)
        self.assertNotIn('ArchiveTuneShared', widget)
        session = (IOS / "ArchiveTune/ArchiveTuneSession.swift").read_text()
        self.assertIn('host.widgetCommand(action:', session)
        host = (UI_ROOT / "IosHost.kt").read_text()
        command = host.split('fun widgetCommand(action: String)', 1)[1].split('/** One line', 1)[0]
        self.assertIn('withTimeoutOrNull(15_000L)', command)
        self.assertIn('sessionState.first', command)
        self.assertIn('ready.graph.skipNext()', command)
        self.assertIn('ready.graph.player.pause()', command)
        workflow = (ROOT / ".github/workflows/ios_compile.yml").read_text()
        self.assertIn('-application-extension', workflow)
        self.assertIn('iosApp/Widgets/NatuneControls.swift', workflow)

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

    def test_apple_music_search_shares_the_playback_account_admission(self):
        discovery = (UI_IOS / "IosProviderDiscovery.kt").read_text()
        self.assertIn('"APPLE_MUSIC" -> ContentProvider.APPLE', discovery)
        self.assertIn("iosAppleMusicSearchRepository(graph.http, graph.accounts", discovery)
        self.assertIn("owners.lists.recordShelf(request, \"provider-search\", page.items)", discovery)
        resolver = (UI_IOS / "IosQueueLocatorResolver.kt").read_text()
        self.assertIn("internal suspend fun iosAppleMusicSearchRepository(", resolver)
        self.assertIn("accounts.admitPoolEntry(", resolver)
        self.assertIn("CredentialScope.PLAYBACK_POOL", resolver)
        self.assertIn("IosAppleDevToken(http, preferences).bearer()", resolver)

    def test_ledger_does_not_turn_source_into_an_apple_pass(self):
        status = (ROOT / "docs/engineering/ios-status.md").read_text()
        self.assertIn("dictionary host/settings/root composition remains required", status)
        self.assertIn("Authored but UNRUN", status)
        self.assertIn("unverified-on-linux-host", status)
        self.assertIn("does not establish compilation against an actual Apple SDK", status)


if __name__ == "__main__":
    unittest.main()
