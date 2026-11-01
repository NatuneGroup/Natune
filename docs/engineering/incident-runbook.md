# Playback and release incident runbook

## Triage and containment

1. Record the installed channel/version/build, device/API/ABI, source, reproduction
   steps and whether the failure affects a local file, one provider or every source.
2. Check [recent workflow runs](https://github.com/NatuneGroup/ArchiveTune/actions)
   and [published releases](https://github.com/NatuneGroup/ArchiveTune/releases).
   Match the installed build to the actual commit. Do not substitute an older green run.
3. For a bad candidate, stop promotion/publication and keep Stable unchanged.
   Do not rotate the signing key, publish another Canary, disable integrity checks,
   downgrade users' version codes or rewrite branch history as a recovery shortcut.
4. Open/link an issue with sanitized evidence, affected version/source and severity.
   A green APK build or automatically closed issue is not incident resolution.

## Gather safe evidence

Use the app's diagnostic export and capture the failure time, resolver tier/status,
queue transition, decoder and cache state. Preserve the original privately; publish
only redacted extracts. Never upload cookies, authorization headers, provider URLs,
account identifiers, private media, databases or signing material.

Explicit diagnostic exports also summarize recognized exceptions from at most the
500 most recent retained error calls, showing up to eight groups. The local signature
uses the exception class and first app stack frame, not messages or line numbers.
Use it to correlate reports across builds alongside their revision/variant context.
Counts describe retained log calls, not crash rates or a root-cause diagnosis.
Nothing is uploaded or turned into an issue automatically; review exports before sharing.

- A single-provider failure: inspect its login validity, bounded timeout, rate-limit
  and cooldown state before changing source order or retry policy.
- A YouTube login failure: follow [source-login boundaries](../source-logins.md).
  Do not stage credentials in the global playback client or clear unrelated cookies.
- A second/third-track stall: capture transition diagnostics and test ordinary
  playback separately from crossfade/automix, Cast and output-route changes.
- A local-file failure: use an owned synthetic fixture; distinguish decoder, seek,
  permission and source-selection failures from remote-provider errors.
- Listen Together: distinguish JSON/protobuf host restrictions and stale connection
  generations; never bypass server hosting restrictions.

## Reproduce, fix and verify

### Launch failures without adb

Reopen the app from its launcher icon. The mobile icon aliases and TV launcher
enter a small platform-UI activity in `:crash` before starting the main process.
If the previous startup never stayed resumed for five seconds, this activity
shows Safe mode rather than automatically retrying Hilt, Room, preferences,
playback or native initialization. An interrupted startup is not proof of a
crash: force-stop, reboot, background-only starts and memory pressure can also
leave the marker. **Continue normally** clears recovery state and explicitly
retries; **Close** dismisses after clearing and acknowledging too, so the report
cannot reappear every launch. The recovery screen never resets settings, accounts
or the library; normal startup's existing database repair/reset policy is unchanged.
An unreviewed incomplete attempt from this install survives main-process
service/provider restarts; only its own healthy callback or the explicit recovery
action clears it. A marker whose recorded build/install identity differs from the
current one never enters safe mode: it is reported once for diagnosis, and the new
build's first healthy launch retires it. Identity is checked from fields stamped
at marker write time, not the wall clock, so a backward clock correction cannot
hide a current-build failure; markers written before identity was recorded still
fall back to comparing the install time and version label.

Java exceptions are synchronously saved under `noBackupFilesDir/startup_recovery`
(16,000 characters, timestamp and thread included), starting before providers in
`attachBaseContext` and reinstalled before Hilt in `App.onCreate`. The existing
DebugActivity/opt-in CrashFeedback handler remains the later delegate.
Android 11+ exit history contributes Java/native crash, ANR, initialization
failure and low-memory reasons. Android 12+ native tombstones are protobuf:
only a bounded signal/abort/backtrace summary is rendered, not raw memory or log
dumps. Native input is capped at 256 KiB, ANR text at 64 KiB, the combined
redacted report at 80,000 characters. Copy/Share are explicit, text-only actions;
review the report for private information first. This recovery report is not
uploaded automatically; any existing opt-in CrashFeedback behavior is unchanged.
Every Java crash, native crash, ANR, initialization failure and low-memory exit is kept
in one bounded store, `noBackupFilesDir/startup_recovery/records` (the newest 20, each
capped at 24,000 characters). Developer > Crash logs lists them newest first with the
app version and process, and opens a record for copy or share. Only failures of the
installed build interrupt startup; records from an earlier build are labelled as such,
and low-memory kills are listed for diagnosis only. Continue, opening the Crash logs
page or opening a record acknowledges the failures shown so far; DebugActivity
acknowledges the crash it displays. Clearing the Crash logs deletes the store and
moves the exit-import watermark forward, so cleared exits do not return.

Exit records/traces can be absent or overwritten by Android. Death before
application attachment has no saved Java stack; exit history is unavailable
below API 30. Deep links, Recents and service starts still use their existing
main-process entry points; use the launcher icon to reach the independent
recovery gate. These mechanisms require exact-head CI and device validation,
including a signed update without clearing app data.

Use [Android QA](../android-qa.md) and the pinned toolchain on an authorized host.
Add a focused regression only when it exercises the failing behavior. Obtain the
separate exact-candidate build/lint and unit-test gates; repeat the actual interaction.
Preserve caller cancellation, playback position, personal credentials and source policy.

If verification is blocked by a suspended host, missing toolchain or unavailable
provider/account, record the blocker. Do not label the fix verified or close the incident.

## Release impact and recovery

Follow the [release procedure](../claude/RELEASES.md). After publication, verify the
successful publish run, tag/commit, APK variants/signature and the matching
`nightly.json` or `stable.json` update-feed asset. Then reproduce the original symptom
on that installed build. Compare fresh sanitized reports and issue activity with
the previous release; there is no claim of an existing production telemetry dashboard.

For recovery, prefer a reviewed forward fix with a newer version code and the
historical signing identity. Pause promotion while uncertain. Restoring an earlier
implementation still requires migration review, a new build and separate release gates.

Record the cause, fix commit, verification evidence, affected releases and prevention
work in the issue. Update this runbook only when the incident exposed a missing step.
