# PotHelper and microG RE PO tokens: not supported

Assessed on 2026-11-09 against PotHelper v1.1.1, MorpheApp/morphe-patches PR #2618 and the
`potokens` service in MorpheApp/MicroG-RE. Natune integrates neither app. Read this before
revisiting the idea.

## Why not

Each of these blocks an integration on its own.

1. **PotHelper does not return a finished PO token.** It returns an integrity-token blob. The
   official YouTube or YouTube Music app then mints the PO token it actually sends from that blob,
   with its own in-app native library (PotHelper's author in PR #2618). Natune cannot ship or load
   Google's library, and rebuilding it would mean reverse engineering obfuscated proprietary code
   that changes with YouTube releases. This was not confirmed on a device or in YouTube's code; it
   rests on the author's description.
2. **PotHelper stopped working for most users.** Failures began around 2026-10-05. Morphe's
   maintainers call it broken and say they are working on a fix (PotHelper #10, closed 2026-10-23). The
   newest release is still v1.1.1 from 2026-08-28, and Morphe patches now offer to switch back to
   stream spoofing when it fails. It may also not work on devices with hardware-backed key
   attestation, such as Pixels (morphe-patches #2644).
3. **microG RE's own service gives no valid tokens.** Since DroidGuard changes in early 2024, microG
   forks no longer receive valid tokens (PR #2618); only the original microG on a device that
   passes Play Integrity does. Its tokens are also bound to the calling app's package
   name and SHA-256 signing-certificate digest, so a token requested by Natune would carry Natune's
   own package name, not an official YouTube app's.

## How the Morphe setup works

- Morphe-patched YouTube and YouTube Music use microG RE for Google services such as sign-in.
- The "PoToken provider" patch hooks the app's GMS binding code (`ServiceBindIntentUtils`) and
  sends only the `.potokens.service.START` action, and its chimera content-provider authority, to
  `app.morphe.pot.helper`. It adds a `<queries>` entry for that package and only acts while "Spoof
  video streams" is off.
- PotHelper runs offline (no INTERNET permission). Its exported `.potokens.PoTokenService` (action
  `app.morphe.pot.helper.potokens.service.START`) is a GMS-style `IGmsServiceBroker` for service
  ID 285. `getService` hands back an `IPoTokensService` binder, and
  `responseStatusToken(callbacks, int, input)` answers with `PoToken { byte[] data }`.
- `data` is a `PoTokenResult { IntegrityToken { encryptData, tokenData } }` message, the same shape
  as microG's `PoTokenResultWrap`. `encryptData` is AES-GCM ciphertext with a Tink-style key
  prefix, built from material that PotHelper's closed-source `libpot.so` mints with a keybox-backed
  attestation. The key never leaves PotHelper and Google's servers.

## What an integration would take

| Piece | Status |
| --- | --- |
| IPC to PotHelper or microG RE | Feasible: hand-written Binder transactions with no Play Services dependency, so the gms and foss flavors could both use it, plus a manifest `<queries>` entry. |
| Licensing | IPC only. `libpot.so` must never be bundled: PotHelper's README forbids distributing its native code with GPLv3 code. Morphe talks to PotHelper over IPC for the same reason (PotHelper #9). |
| Turning the blob into a PO token | Not feasible (blocker 1). |
| Client policy | An Android token path would change the client policy that `YTPlayerUtils` and `AutoChoosePlaybackClientKey` own. InnerTubeX already has the hook: a public `TokenProvider` (in both the Android pin v0.11.2 and the desktop's v0.11.5) and a playback catalog that accepts `ANDROID_DROIDGUARD` or `EXTERNAL` tokens for Android clients. `playback/stream/InnerTuneXStreamRepository.kt` builds `InnerTubeExtractor` without a provider, so the library's `UnavailableTokenProvider` default applies. |

Natune's PO tokens stay web BotGuard tokens minted through QuickJS in `YTPlayerUtils`, with the
InnerTubeX, NewPipe and Echo tiers behind it (see [extraction](../extraction.md)).

## When to revisit

- PotHelper ships a release that works again **and** gives third-party apps a finished PO token
  for a given content binding (video ID or visitor data), or
- a documented way appears to mint Android PO tokens without Google's code.

Either way, keep any integration IPC-only and never bundle `libpot.so`.

## Sources

- PotHelper source and license note: https://github.com/MorpheApp/PotHelper
- "PoToken provider" patch: https://github.com/MorpheApp/morphe-patches/pull/2618
- PotHelper failures: https://github.com/MorpheApp/PotHelper/issues/10,
  https://github.com/MorpheApp/PotHelper/issues/5 and
  https://github.com/MorpheApp/morphe-patches/issues/3587
- Devices with hardware-backed attestation: https://github.com/MorpheApp/morphe-patches/issues/2644
- License incompatibility: https://github.com/MorpheApp/PotHelper/issues/9
- microG RE token binding: `play-services-core/src/main/kotlin/org/microg/gms/potokens/PoTokenHelper.kt`
  in https://github.com/MorpheApp/MicroG-RE
- microG on PoToken and DroidGuard: https://github.com/microg/GmsCore/issues/2253
