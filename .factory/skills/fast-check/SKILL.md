---
name: fast-check
description: Run bounded pre-build feedback for ArchiveTune changes without treating structural checks as compilation, LSP or runtime proof.
license: GPL-3.0-only
---

# Fast bounded checks

Use this before a local or remote build, then keep the final exact-candidate gates.

1. Read `AGENTS.md`; record the candidate head and a meaningful full base OID.
   `check_repository.py` accepts one of `--worktree`, `--staged` or `--base <oid>`
   (mutually exclusive). Use `--worktree` while editing and `--staged` after staging;
   a committed candidate needs a recorded full base OID, never `HEAD` as its own base.
2. Run the cheap repository and diff checks:

   ```sh
   python3 scripts/check_repository.py --worktree
   git diff --check
   # after staging: git diff --cached --check
   ```

3. Run only the relevant Python fixture tests, `bash -n` for changed shell files and
   `node --check` for changed JavaScript. `actionlint` is optional: use an already
   available/configured binary, otherwise report it UNRUN. Do not install a parser or
   start a server just to make a check available.
4. The pinned structural quality entrypoint spawns Gradle/JVM and is **CI or an
   authorized build host only**; it is not a local cheap-edit check on this host:

   ```sh
   python3 scripts/check_code_quality.py --base <full-40-char-base-oid>
   ```

   This uses the repository's existing ktfmt/Detekt/CPD project over the Android and
   KMP structural roots. It is formatting/structural analysis with no type resolution;
   it is not a compiler, LSP, native, Swift, linker or runtime result. The optional
   `--classpath-file` is legacy Android-GMS-only and does not validate native/KMP
   expect/actual semantics. The independent `Build Pull Request` / `Kotlin quality`
   job is a **manual diagnostic trial only**: dispatch with the quality-only
   `quality_base` input. It is not an automatic PR/dev gate until split and
   method-rename debt lineage is proven. Manual quality-only mode does not run the
   normal Android build or desktop compile; the job has a 20-minute bound and no
   build dependency. A non-zero finding report is diagnostic evidence, not proof
   that quality is clean.
5. Compile or test the changed module normally when the bounded change needs it.
   Batch platform compilation (including iOS native/Swift/iosTest compile) for shared
   or iOS changes. Spend native closure, APK, desktop package or IPA work only on
   relevant native/toolchain/packaging or final-candidate changes.
6. Do not substitute these checks for the final exact-candidate BUILD/lint and TESTS
   gates, or for native expect/actual/link validation. No live Kotlin LSP, Apple SDK
   or Swift toolchain is available in this workspace; a connected IDE may add
   diagnostics but no such live session is claimed here.

Report passed, failed and UNRUN checks explicitly, with the exact base/head used.
