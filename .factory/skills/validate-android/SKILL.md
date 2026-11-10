---
name: validate-android
description: Validate a Natune Android change with lightweight checks and remote build/test evidence on the exact head. Use before declaring Kotlin, dependency, native or playback changes ready.
license: GPL-3.0-only
---

# Validate an Android change

1. Read `AGENTS.md`; record the commit under test and any uncommitted changes. Do not
   move submodule gitlinks.
2. Run `python3 scripts/check_repository.py --worktree` and, when changing its checks,
   `python3 -m unittest discover -s scripts -p 'test_*.py'`.
3. On a machine set up for Android builds, confirm JDK 21, SDK 37, the pinned NDK and
   the checked-in submodule revisions.
4. Run the narrow relevant test first, for example
   `./gradlew --rerun-tasks :app:testGmsMobileUniversalDebugUnitTest --tests moe.rukamori.archivetune.logcat.DiagnosticRedactionTest`.
   A cached, skipped or compile-only task is not test evidence.
5. Before merge, get the separate `Build Pull Request` build/lint and `Unit tests`
   results on the exact head.
6. Use `docs/android-qa.md` for device checks. Record the real target and interaction;
   an assembled APK does not establish UI, login, playback or USB behavior. Verify the
   installed APK hash instead of assuming it matches the checkout.
7. Keep complete failure output. Fix failures without weakening tests or retrying flaky
   assertions until they pass.

Report passed, failed and skipped checks explicitly. Never claim an upload or release
without confirmed evidence.
