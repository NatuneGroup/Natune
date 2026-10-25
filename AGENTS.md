# ArchiveTune private full-port operational guidance

This is independently authored KMP foundation code, not an imported legacy baseline
or a finished player. Development belongs on `dev`; `main` is Stable/default.
Never write to the original public repository or publish assets, logs or notifications.

## Source of record and complete scope
Read `/home/factory-user/outputs/archivetune-kmp-planning/requirements-and-decisions.md`,
especially the 2026-10-25 continuation, before work. Its decisions and the complete
`feature-surface-map.json` / `.md`, `dev-investigation.md`,
`submodule-ui-supplement.md`, `metrolist-reference-analysis.md`,
`native-adapter-design.md`, `dependency-inventory.json`,
`upgrade-and-workflow-plan.md` / `.json` and `infrastructure-research.md` in that
directory are authoritative. References preserve **all** requirements; absent
implementation is not permission to omit a feature. The map's 569 action templates,
30 menu definitions and 219 menu action templates are not unique-feature counts.

Read-only reference source: `/home/factory-user/work/ArchiveTune`, dev SHA
`b7e4dd444fea202a5c32e96f7b1199a74fe3ae70`; blocked staged snapshot:
`/home/factory-user/work/ArchiveTune-KMP`. Read their AGENTS/CLAUDE and applicable
area guidance before tracing behavior. Preserve all four gitlink pins. Trace actual
callers, navigation/resources/manifests/variants/generated/native entry points and
downstream effects before editing an area; imports alone are insufficient.
No dead code is confirmed. Characterization and bug fixes stay distinguishable.

## Invariants, not optional backlog trimming
- Full Android, Windows/Linux/macOS desktop and iOS shared domain/data/CMP UI port.
  iOS may remain explicitly unverified; other targets require native-host evidence.
- Preserve current settings, every menu/overflow action, source/account gates,
  providers, library/downloads/podcasts/Telegram, DSP/USB/bit-perfect/transitions,
  social/Listen Together/scrobbling/backup and all Android system integrations.
- Keep all 17 saved styles and their order/meaning/selections: V1,V2,V3,V4,V5,V6,V7,V8,V9,
  APPLE_MUSIC,V10,BITCHORD,TIKTOK,SIMPMUSIC,SPATIALFLOW,LOOPER,FLAMINGO.
  V4 remains player default; Material 3 Expressive remains interface default.
  Preserve liquid glass and the user's settings/navigation design. Add Metrolist
  as another selectable style, never replace styles. Improve Enhanced lyrics
  without changing other modes, Unicode/timing/translation/phonetics/duet/seek/
  scroll/accessibility/user-choice behavior.
- Preserve Android Media3/native host until real shared replacements are proven.
  Native FFmpeg/miniaudio engine, platform adapters and migration are implementation
  work, not upstream turnkey support. No fake clocks, no-op parity or bundled Python.
- Keep catalog/streaming Spotify accounts separate; preserve terminal auth failures,
  source order/refusal, provider-local credentials, Pool separation, source-bound
  caches, permitted Listen Together servers and Discord OAuth-only presence.
- Preserve Room schema/legacy queue readers, preferences/defaults/storage formats,
  cancellation/generation ownership and Android applicationId `moe.rukamori.archivetune`.
- Upgrade must retain historical signer/legitimate lineage, data and monotonic
  absolute versionCode above a verified release floor. No new key/rotation,
  committed keystore or removed protected files. Required future secret names:
  KEYSTORE, KEY_ALIAS, KEYSTORE_PASSWORD, KEY_PASSWORD. Never read/log values.
  Debug `.debug` installation is not release upgrade evidence.
- Retain GPL/copyright/attribution and all library/artwork notices; keep Metrolist
  reference private. Resolve distribution-rights gaps, never erase notices.
- Measure size/performance/battery against matched baselines, not unsupported 3x claims.

## Security and execution boundaries
The whole-legacy-source commit was denied. Do not retry, disable scanning, change
history/transport/submodules to evade it, copy flagged content or waive the finding.
Only genuinely independent secret-free source is eligible for this repository.
Human/security disposition remains required for legacy import. No source deletion.
No production credentials, accounts, key literals, tokens or signing material here.

Local isolated probes only: one Gradle worker, 1536 MiB Gradle / 768 MiB Kotlin
ceilings; no old full-app build or unbounded indexing, no VPS changes. Keep generated
artifacts out of commits. No external build scans. GCS Maven Central mirror must
precede Maven Central; do not suppress Kotlin metadata checks.

Use KMP-capable semantic tooling plus real compiler/test evidence, not syntax-only
grep as a claimed semantic equivalent. This foundation's compiler checks do not
prove full interactive KMP IDE/LSP readiness. New Kotlin files need GPL headers;
user-visible strings belong in Compose resource XML, not inline Kotlin.

CI readiness is manual-only and private, not final workflow parity. Final adaptation
must retain useful original workflow set, independent exact-head BUILD and TESTS,
main/dev roles, Stable/Nightly and historical signing-secret names. Do not activate
unused Telegram upload, public notifications or paid extras. Reserve from reported
3,000 free equivalent minutes; serialize batches, no retries/schedules/cache writes.
Maximum runner multipliers: Linux32=16, Windows32=32, macOS12=40. Native-host build
is not physical audio/peripheral/account QA. No more routine questions while the
user sleeps; report concrete human-only blockers without inventing passes.
