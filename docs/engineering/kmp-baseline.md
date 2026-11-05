# Private KMP baseline

## Source

- Repository: NatuneGroup/ArchiveTune
- Branch: dev
- Commit: b7e4dd444fea202a5c32e96f7b1199a74fe3ae70
- Source tree: `0aa5d7c8dcad8cf68d1cdaf0e29527ba32385f2d`

The source clone is shallow. This private repository uses a new root snapshot,
not a force-push or a rewrite of published history. Git fetched the exact source
commit locally, read its tree into a fresh index, and checked out tracked files.
The initial index tree matched the source tree exactly, including file modes,
symlinks, `.gitmodules` and four gitlinks. Only the appended AGENTS/README guidance,
this baseline document, the inactive-workflow README and the workflow moves differ.
Application source, resources, Gradle configuration and dependency versions are unchanged.

## Submodule pins

| Path | SHA |
| --- | --- |
| `IconPack` | `a19cab668320be194533964c0d0aac41ba70bf60` |
| `core` | `e2bf1df5010231ee4ab330ee5e6266a1c6279bec` |
| `lyrics` | `31705a8fa7d8c59f60d3eab74dfe76478272d075` |
| `morideobfuscator` | `4262067f238135dd5c74df3d8ea0dbaf15febe20` |

Initialize these exact pins from the public origins in the unchanged `.gitmodules`.
Children remain separate Git repositories, not vendored directories. No child
repository push or gitlink change is part of setup.

The table records the import snapshot. The local media-obfuscator path was later
renamed `morideobfuscator` → `deobfuscator` (Gradle module `:deobfuscator`) with the
same pinned commit; its `.gitmodules` origin stays `rukamori/morideobfuscator`.

## Workflow quarantine

Before the first private push, all 13 inherited files were moved unchanged from
`.github/workflows/` into `.github/inherited-workflows/`: `header.yml`,
`dependency_health.yml`, `codeql.yml`, `deploy_artifacts.py`, `tests.yml`,
`nightly.yml`, `test_stability.yml`, `release.yml`, `notify_user.yml`,
`build_pull_request.yml`, `build.yml`, `repository_quality.yml`, `issue_labels.yml`.
The only active workflow is `.github/workflows/readiness.yml`, which runs on manual
dispatch only, with no push, pull request or schedule trigger.

The foundation's manual matrix workflow from `c047a2002a7c35eb9ed30b6a8eec72951f7f2f48`
is kept byte-identical as `.github/inherited-workflows/foundation-readiness.yml`. It is
not active and was not dispatched. It covers the foundation's `:shared` tests and Linux,
Windows and macOS native app-image jobs, and it cannot pass in this tree because
`:shared` is not in the active settings. M10 may adapt its native-host matrix. Its
historical runs test the foundation only. The foundation's desktop `nativeDistributions`
settings (Deb, Msi and Dmg) are not in the port's `desktopApp` build either. M8 and M10
decide packaging.

The inherited `build.yml` handles main/dev pushes; `nightly.yml` handles dev
pushes and publishes releases/update feeds, then prunes older releases/tags.
`release.yml` manually builds/signs/publishes Stable and its update feed.
`notify_user.yml` consumes successful main-branch Build APKs completions through
`workflow_run`, or reusable calls, and labels/comments/closes referenced issues.
The deployment helper can send APKs to Telegram; no inherited workflow calls it.
PR, issue, manual, reusable and scheduled quality/security/test/header workflows
are also inactive. The test workflow's optional reusable dependency/security/
stability calls are retained as historical references, not executable wiring.
The unchanged update-manifest helper still contains public download URL defaults;
it must not be treated as private-publishing configuration.

Moving the workflows deliberately leaves inherited policy/documentation references
to their old active paths stale. Do not restore automation or weaken checks merely
to hide that mismatch; review private readiness contracts separately.

## Validation status

Setup checks (tree/index, pins, privacy and workflow population) are integrity checks,
not compilation or runtime evidence.

The KMP scaffold passes local JDK 21 checks with `--max-workers=1 --no-daemon`:
`:shared-domain:compileCommonMainKotlinMetadata :shared-data:compileCommonMainKotlinMetadata
:shared-ui:compileCommonMainKotlinMetadata :shared-domain:compileKotlinJvm :shared-data:compileKotlinJvm
:shared-ui:compileKotlinDesktop :desktopApp:compileKotlinJvm`, and
`:shared-domain:allTests :shared-data:jvmTest :shared-ui:desktopTest`. The readiness
workflow runs the same commands. Gradle reports the umbrella `compileKotlinMetadata` as
SKIPPED and compiles no common metadata under it, so the explicit common-metadata tasks
are named. `:shared-domain` tests cover the `PlaybackSnapshot` contract and clock
interpolation (`PlaybackClockTest`, `PlaybackSnapshotTest`, `MonotonicClockTest`).
`:shared-ui:desktopTest` renders `ArchiveTuneRoot` in a Compose UI test and writes
`outputs/screenshots/archivetune-root.png`, which is git-ignored. It passes with or
without a display. The `iosSimulatorArm64` compile of `:shared-domain` is part of the
`allTests` graph. iOS simulator tests skip on Linux. Android targets are configured only
when an Android SDK is present. This workspace has none, and the readiness workflow sets
empty SDK paths, so Android compilation is unverified.

The desktop window runs under Xvfb `:99`. `:desktopApp` renders the root surface in a
real `Window`, and `outputs/screenshots/desktop-foundation.png` holds the capture. The
JDK's X11 AWT library links `libXtst.so.6`, which the `libxtst6` package provides. This
workspace did not have that package, so the window run used a user-space copy. A host
that sets `DISPLAY` needs the package installed. Apple-host iOS builds, CI runs and
upgrade checks have not run.

Merge candidate check (2026-10-25, the private `dev` merge of the foundation and port
history, before any push): with JDK 21 and `--max-workers=1 --no-daemon`, the compile
tasks and the test tasks from `services.yaml` exited 0. The JUnit reports show 682
`:shared-domain` JVM tests, 225 `:shared-data` JVM tests and 8 `:shared-ui` desktop tests,
with zero failures, errors or skips. Some Gradle tasks were up to date from identical
earlier inputs. These checks cover only the approved port modules. No CI run was
dispatched for this merge, and the foundation's CI results are not port evidence.

## Privacy and attribution

Destination: **NatuneGroup/Natune**, private, branch `dev`. Keep it private
until the official Metrolist KMP release. Recovered Metrolist builds are references,
not the exact original KMP source. Preserve Metrolist and inherited contributors'
credit, all copyright banners, root/vendor licenses and notices. No license,
application identity, account/storage contract or signing identity is changed.
Do not add credentials, agent session state or recovered binaries to this repository.
The public source workspace/repository and its protected gates are outside setup's
write scope. Publishing and notifications remain disabled by workflow quarantine.

## Planned branch/workflow and upgrade parity

These are desired future outcomes, not active branches or completed validation.
After legitimate import-gate resolution and reviewed private setup, establish
`main` (Stable/default) and `dev` (integration), both initially based on the exact
approved dev-derived snapshot above; do not import source main. Remote `main` and `dev`
currently both point at the independent foundation commit
`c047a2002a7c35eb9ed30b6a8eec72951f7f2f48`, not at that snapshot. Changing remote `main`,
or replacing history, needs separate authorization.

Quarantine is temporary. Restore/adapt the original YAML workflow set and useful
build/test/quality/Nightly/Stable behavior, existing flavor/reproducibility coverage,
and independent exact-head BUILD and TESTS for the private destination. Keep
publication/assets/feed links private and public-repository gates untouched.
Do not newly activate the unused Telethon uploader or external notifications.
Documentation updates do not move, edit or enable workflows.

The pending Android upgrade goal uses the same historical release material through
authorized secret configuration with `KEYSTORE`, `KEY_ALIAS`, `KEYSTORE_PASSWORD`,
`KEY_PASSWORD`; no secret access, key generation/rotation or history recovery.
Preserve `moe.rukamori.archivetune`, certificate/legitimate lineage, schema/legacy
queue/settings/files and source/account/style/license invariants. Later allocate
one absolute increasing versionCode above the trusted upgrade floor across signed
workflows, not fallback/reset-history/per-workflow counters. A `.debug` APK or
matching certificate alone does not prove a non-destructive release upgrade.
Verify without uninstall, data clearing or downgrade flags.

Authorized manual private APK download suffices for the requested installation
test; no new GitHub-token updater, embedded PAT, server or credential-cloud service
is automatically authorized. Do not drop provider features or treat scanner
triage as a waiver. Preserve the full approved KMP/CMP scope and inherited credit.
Future heavy CI remains free-only, orchestrator-batched and high/max CPU within
the user-reported allowance, never CI per small edit; no routine questions while
the user sleeps.

## Current state and push gate

Current: nothing has been pushed from this workspace. Remote `dev` and `main` both point
at the independent foundation commit `c047a2002a7c35eb9ed30b6a8eec72951f7f2f48`
(parentless; observed with read-only `git ls-remote` on 2026-10-25). Local `main` is
unchanged at the approved baseline commit. Local `dev` merges that foundation with the
port history in a merge commit that keeps both histories. The merge is local only.
Before any push:

- The Droid-Shield-flagged inherited files, which are part of the baseline's history,
  need legitimate human/security disposition. The denied import is that gate: do not
  retry unchanged, bypass the scanner, use an alternate history, transport or
  source-upload route, or treat the findings as waived. A user merge approval is not a
  security waiver.
- The sync is a normal fast-forward of private `dev` from the then-current remote head.
  A moved remote head needs fresh integration, never a force-push. Remote `main` is not
  updated, and the sync involves no public ArchiveTune write, release or notification.
- Orchestrator decision: the foundation's `:shared` `PlaybackSnapshot` clock differs
  from the approved `:shared-domain` contract. It has a 1 s extrapolation cap, returns
  null on epoch mismatch, gates on visibility and saturates on overflow. The approved
  contract stays active, and the foundation's nine clock tests are not run. Reconciling
  the two needs an explicit decision, not a silent replacement.
- `python3 scripts/check_repository.py --worktree` reports five findings. Three
  documented CI tasks and two toolchain links name `.github/workflows/` files that
  are now quarantined. Clearing them needs a separately approved private-readiness
  contract review. The default mode stays strict and still exits 1 with those five.
  The readiness workflow uses `--quarantine-aware`, which still prints the five, marked
  `(expected quarantine)`, and fails on any other finding. A finding is accepted only when
  it names a parked inherited workflow that has no copy under `.github/workflows/`.

No port, CI, Android, iOS, signing or numeric upgrade validation is complete, and
no secrets or keys were accessed.
