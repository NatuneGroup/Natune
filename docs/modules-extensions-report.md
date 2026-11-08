# R3 Natune extensions implementation report

Worktree: `/data/workspace/r3-spotify-ext`. Branch: `feat/r3-spotify-ext`.
Base: `integration/r3`, `e5f7aa66`. No PR, CI dispatch, identity change or
submodule pin change. The concurrent `fix/r3-review2` compile review was not
merged into this branch.

Implementation commits, each pushed before the next logical step:

- `38d33af6cebaac6a276303b73b1cc0b2e5276e88` — Natune kinds, bounded lyrics response contract, manifest/runtime/packager changes and parser tests.
- `b5f74c3219f49283f29ae034bad4a2da0b542108` — lyrics provider-chain integration, priority/settings entry and packaged LRCLIB example.
- `49b4ec26f93455888c63d08702fa40e1ee299008` — native extension registry, Spotify migration/gates, Modules filters and existing login/settings links.

The following documentation/fixture commit adds the bounded metadata runtime
entry point, separate-catalog regeneration support, synthetic LRCLIB fixtures,
distinct provider-cache identities for repeated installations, and this report.
Its SHA is supplied in the handoff.

## Result and scope

- Four kinds: audio source, metadata, lyrics and playback. The Modules screen
  filters them and retains installed-module enable, configuration and priority.
- Three stable Android-native Spotify registrations, with independent enable
  keys and settings routes. Existing credentials and source/engine/Premium
  policy remain; the native priority controls reuse the existing engine choice.
- Disabling metadata hides Spotify home/library/search/playlist surfaces and
  cached Android Auto folders; catalog reads/enrichment are gated. Account/login
  settings remain accessible. Disabling a playback extension gates its token
  owner and removes the selected path from the enabled Spotify source set.
- Migration writes only absent keys, deriving prior account/source state;
  explicit choices survive migration/account changes. It does not enable the
  audio source or alter either account. Fresh keys migrate off.
- Enabled JS lyrics modules join the existing provider chain. Their group can
  precede/follow built-ins within the same sync tier; per-extension order remains
  in Modules. Built-in providers and the fixed word-sync sweep remain. Registry
  revisions invalidate extension-related helper caches. Timeouts and provider
  misses leave built-in fallback available.
- One optional LRCLIB lyrics package and a separate Natune catalog. No embedded
  credentials, live requests, installed providers or native account bridge.

## File map

| Area | Files |
| --- | --- |
| Android module contract | `modules/.../ModuleKind.kt`, `ModuleLyrics.kt`, `ModuleModels.kt`, `ModulePackageReader.kt`, `ModuleRuntime.kt` |
| Android registry and state | `app/.../modules/ExtensionRegistry.kt`, `NativeExtensionPolicy.kt`, `ModuleLyricsProviders.kt`; `viewmodels/ExtensionSettingsViewModel.kt`, `ModuleSettingsViewModel.kt` |
| Lyrics chain/settings | `app/.../lyrics/LyricsHelper.kt`; `ui/screens/settings/LyricsProvidersSettings.kt` |
| Spotify account gates | `spotify/SpotifyStreamingAccount.kt`, `SpotifyLibrespotAccount.kt`, `SpotifyLibraryRepository.kt`, `SpotifyCatalogAuthentication.kt`, `SpotifyExtensionAvailability.kt` |
| Spotify visibility | `ui/screens/HomeSourceSwitcher.kt`, `library/LibrarySourceSelector.kt`, `LibrarySpotifyPlaylistsScreen.kt`, `playlist/SpotifyPlaylistScreen.kt`, `search/SearchScreen.kt`, `SpotifyOnlineSearchResult.kt`; `ui/component/SearchSourcePicker.kt`; `viewmodels/OnlineSearchViewModel.kt` |
| Android Auto | `playback/MediaLibrarySessionCallback.kt`, `SpotifyAutoFolders.kt` |
| Extension settings/login | `ui/screens/settings/ModulesSettings.kt`, `NativeExtensionSettings.kt`, `SpotifyStreamingSettings.kt`, `IntegrationScreen.kt`, `PlaybackSourceSections.kt`; `res/values/extensions.xml` |
| Audio-kind isolation | `app/.../modules/ModulePlaybackUseCases.kt` |
| Package/example/catalog | `addons/modules/natune/lrclib/{manifest.json,provider.js}`, `dist/lrclib.arch`, `natune-extensions.json`; `scripts/package_module.py`, `scripts/build_catalog.py` |
| CI fixtures | `modules/src/test/.../ModuleParsingTest.kt`, `ModuleLyricsTest.kt`; `app/src/test/.../modules/NativeExtensionPolicyTest.kt`; `addons/modules/tests/providers.test.js` |
| Documentation | `docs/modules.md`, `docs/modules-implementation-report.md`, this report, `addons/modules/README.md` |

Large existing callers stayed within their baseline line budgets by extracting
the affected catalog authentication, effective search-provider selection and
Android Auto liked-folder construction. Upstream notices remain unchanged.

## Compatibility

The upstream audio envelope, API v1, methods, settings, compatibility floor and
runtime APIs remain. The nine existing audio packages and `registry.json` are
unchanged. Legacy type labels remain audio labels; the three new wire types are
reserved Natune kinds. Natune-only packages should declare
`natuneExtensions@1` (and `lyrics@1` / `metadata@1` as applicable) so upstream
rejects their requirements. Upstream has no lyrics API; it rejects new
capabilities/features or may ignore unfamiliar kind labels when only old audio
methods are present. Native registrations are not `.arch` packages.

JS metadata calls are exposed by the bounded runtime; generic JS catalog
presentation is not added. Spotify metadata reuses the native screens. JS
playback remains URL-based in `MODULES`; native Spotify remains in `SPOTIFY`.
Existing engine selection chooses one Spotify playback path, without automatic
cross-account fallback. No shared, desktop or iOS extension dependency is added.

Apple Music remains skipped for portable playback extensions: full webPlayback
requires the native license exchange/CENC/DRM path, and previews are not songs.
The retained native Apple source is unaffected.

## Verified light checks

- `python3 scripts/check_repository.py --worktree`: zero findings.
- `python3 -m unittest discover -s iosApp/tests -p 'test_*.py'`: 26 passed.
- `node --check`: LRCLIB provider and updated JS fixture file passed.
- Packager `--check`: all nine audio source directories and the LRCLIB source passed.
- Packager `--inspect`: all ten `.arch` packages passed.
- LRCLIB package: 1,276 bytes, SHA-256
  `bc9e5e75ca80b948e46ec57198df9420bb077079f92495d505ed50e7fe058bf9`.

## Outstanding and unverified

**Official Android playback is not implemented in the supplied base.** There are
librespot OAuth/account/callback/settings files, but no Android librespot
playback dependency/binding. The existing unofficial resolver explicitly stands
down for a Premium librespot selection and falls through to the next source.
This task registers and gates the official extension and reuses its login UI;
the UI and docs disclose the absence. A native engine binding is required to
fulfill the requested working official playback. No working librespot audio is
claimed.

No local Gradle/JVM build, lint, JVM tests, JS runtime fixtures, compiler-backed
analysis, device/account/network playback test, cross-app installation, or
desktop/iOS compilation was performed. Parser, migration and LRCLIB runtime
fixtures await CI on the integrated head, including the separate compile-review
fixes. Module/native toggle changes, login routes, cancellation, cached lyrics,
source switches and real account separation need Android device validation.
The edited catalog builder was not executed; the sample package was created and
inspected with the packager directly. Branch catalog links need updating after
merge. Disk use remained small; no large dependency download occurred.
