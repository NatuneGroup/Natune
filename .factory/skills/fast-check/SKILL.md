---
name: fast-check
description: Run bounded pre-build feedback for Natune changes without treating structural checks as compilation, LSP or runtime proof.
license: GPL-3.0-only
---

# Fast bounded checks

Use this before a build, then still run the full build and test gates on the final head.

1. Read `AGENTS.md`. `check_repository.py` takes one of `--worktree`, `--staged` or
   `--base <oid>`. Use `--worktree` while editing and `--staged` after staging; for a
   committed change pass a full base OID, never `HEAD` itself.
2. Run the cheap repository and diff checks:

   ```sh
   python3 scripts/check_repository.py --worktree
   git diff --check
   # after staging: git diff --cached --check
   ```

3. Run the relevant Python tests (`scripts/`, `iosApp/tests/`), `bash -n` for changed
   shell files and `node --check` for changed JavaScript. `actionlint` is optional: use
   it if installed, otherwise report it as not run.
4. `python3 scripts/check_code_quality.py --base <full-base-oid>` runs ktfmt, Detekt and
   CPD through Gradle, so it belongs in CI or on a machine meant for builds. It is
   structural analysis without type resolution, not a compile. In CI it is the manual
   `Kotlin quality` job of `Build Pull Request` (dispatch with `quality_base`).
5. Compile or test the changed module when the change needs it. Batch platform
   compilation, including iOS, for shared or iOS changes.
6. None of these replace the PR build/lint and unit-test gates or native link checks.

Report passed, failed and skipped checks explicitly, with the base and head used.
