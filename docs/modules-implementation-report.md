# R3 module implementation report

Branch: `feat/r3-modules`, base `cdaf5b79`. Upstream host: `8f25a9e21`.
Module-source snapshot: `38565cc0f92eb268eb4f031679f52fc76a79da40`.

Implementation commits:

- `f0fbd8c850915775700ae48e3981be220d75dbc6` — feat(modules): port Android v1 module host without gatekeeper or encrypted envelopes
- `77560de4e51cbe047b280aad248c4667b46b909a` — feat(modules): add source-priority playback and module download routing
- `cee3a831dfda1633f15432c2a71b12eac9ba9396` — feat(addons): publish compatible provider forks and ArchivePool modules
- `916cff70efad28c3402b1fa132af2ffed71bca92` — test(modules): cover package safety, provider fallback and source-order integration
- `be8520c100c0796f8d5c188afcf94646b5c8e42d` — fix(modules): keep manifest selection in dedicated media adapters

The documentation/resource completion commit follows these implementation commits;
its exact SHA is included in the handoff. No PR or workflow dispatch is created.

## File map

| Area | Files |
| --- | --- |
| Android library | `modules/build.gradle.kts`, `modules/src/main/kotlin/.../modules/*`, two JS host assets, Android manifest |
| App repositories/DI | `app/src/main/kotlin/.../modules/*`; Hilt constructor injection; no new DB schema |
| Playback/downloads | `ModuleMediaSource.kt`, `ModuleSelectingDownloader.kt`, `ModuleServiceIntegration.kt`, `MusicService.kt`, `DownloadUtil.kt`; extracted source request/default/label contracts |
| Settings/entry points | `ModulesSettings.kt`, `ModuleDetailsSettings.kt`, `ModuleSettingsViewModel.kt`, navigation/settings builders, app manifest, `res/values/modules.xml`, extension icon |
| Shared boundaries | shared `AudioSourceType`, default order and platform-disabled module preference; labels in shared UI; desktop unavailable result |
| Providers/catalog | `addons/modules/upstream/extension`, active `extension`, common/crypto sources, packager/catalog builder, nine `dist/*.arch`, `registry.json`, original licenses |
| Tests | `modules/src/test/.../ModuleParsingTest.kt`, source-order characterization updates, `addons/modules/tests/providers.test.js`, app CI test task dependency and JS fixture CI step |
| Docs | `docs/modules.md`, addon README, credits, AGENTS invariant and generated toolchain reference |

## Audit and module comparisons

The removed traps, minimum-version mapping, licensing discrepancy and complete
per-module comparison table are in [modules.md](modules.md). The removed code is
the NetworkGatekeeper interceptor and the hardcoded-key `8SM1.` envelope decoder.
No additional private endpoints, remote kill switches, package identity gate or
telemetry were found in the port. Publisher homepage links remain informational;
the upstream catalog is an explicit suggested user choice. User-configured signed
sessions and domain permissions remain intact.

## Evidence and remaining risks

- Nine packages passed upstream packager check, packaging, inspection and catalog SHA-256 verification.
- Provider JS and host assets passed syntax checks; no JS runtime fixtures ran locally.
- `python3 scripts/check_repository.py --worktree`: zero findings.
- `python3 -m unittest discover -s iosApp/tests -p 'test_*.py'`: 26 passed.
- No local Gradle/JVM/lint/test build, device/account test, cross-app installation or desktop/iOS compilation. CI/runtime fixtures remain pending.
- Apple Music has no portable non-DRM full-track path and is not packaged. Native Apple playback remains.
- Deezer and Amazon modules need plaintext user instances. QQ encrypted QMC remains native. Public-instance/queued dialect parity is incomplete and described in the comparison table.
- Module-enabled preparation can repeat earlier standard-provider attempts on fallback. Header/manifest selection stays with dedicated adapters. Offline/resumed downloads, source switches and cancellation need device validation.
- Rotated OAuth tokens, leases and cooldowns are memory-scoped under upstream API v1.
- Suggested Natune catalog/download URLs target the feature branch and must be updated after merge.

Disk use stayed small: the upstream module checkout and sources/packages are under a few MB;
no large download or signing/secret access occurred. Git identity was not changed.
