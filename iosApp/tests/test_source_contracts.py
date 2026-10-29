"""Host/source relationships only; these tests do not compile or execute Apple APIs."""
from pathlib import Path
import plistlib
import re
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
IOS = ROOT / "iosApp"
KOTLIN = ROOT / "shared-data/src/iosMain/kotlin/moe/rukamori/archivetune/shared/data/platform/ios"


class IosSourceContractsTest(unittest.TestCase):
    def test_host_resources_and_scheme_reference_real_target(self):
        info = plistlib.loads((IOS / "ArchiveTune/Info.plist").read_bytes())
        self.assertEqual(["audio"], info["UIBackgroundModes"])
        self.assertTrue(info["LSSupportsOpeningDocumentsInPlace"])
        self.assertNotIn("NSAppTransportSecurity", info)
        self.assertEqual(["archivetune"], info["CFBundleURLTypes"][0]["CFBundleURLSchemes"])
        project = (IOS / "ArchiveTune.xcodeproj/project.pbxproj").read_text()
        scheme = ET.parse(IOS / "ArchiveTune.xcodeproj/xcshareddata/xcschemes/ArchiveTune.xcscheme")
        for target in scheme.findall(".//BuildableReference"):
            self.assertIn(target.attrib["BlueprintIdentifier"] + " = {isa = PBXNativeTarget", project)
        for path in re.findall(r"path = ([^;]+); sourceTree = \"<group>\"", project):
            self.assertTrue((IOS / path).is_file(), path)
        self.assertIn("PBXResourcesBuildPhase", project)
        self.assertIn("InfoPlist.strings", project)
        self.assertIn(":shared-ui:embedAndSignAppleFrameworkForXcode", project)
        swift = (IOS / "ArchiveTune/ArchiveTuneApp.swift").read_text()
        self.assertIn("import ArchiveTuneShared", swift)
        self.assertIn("host.close()", swift)

    def test_release_selects_the_native_producers_exact_xcode(self):
        script = (ROOT / "scripts/build_ios_native_core.sh").read_text()
        expected = re.search(r'XCODE_EXPECTED="\$\{ARCHIVETUNE_XCODE_VERSION:-([\d.]+)\}"', script)
        self.assertIsNotNone(expected)
        release = (ROOT / ".github/workflows/_release-artifacts.yml").read_text()
        ios_job = release.split("\n  ios:\n", 1)[1].split("\n  publish-release:\n", 1)[0]
        self.assertIn(f"ARCHIVETUNE_XCODE_VERSION: '{expected.group(1)}'", ios_job)
        self.assertIn("uses: maxim-lobanov/setup-xcode@v1", ios_job)
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
