# TIDAL pool playback incident — 2026-11-08

The installed Nightly N20261108 (`08282d8f9d97`) fetched three TIDAL accounts,
but resolved no account stream and then called the community resolver with no
instances. Its terminal metadata-gate message also covered providers returning
no stream; it did not establish that valid TIDAL metadata had been rejected.

Live read-only verification used the deployed pool account and discovery feeds,
the app's Bearer client authentication and OkHttp user agent, and each account's
session country for search and playback. AES-256-GCM decryption with the
read-key-derived key succeeded. No credentials, account identifiers or manifest
URLs are retained here.

All three accounts were marked `premium=true` by the pool, but had valid,
unexpired tokens and successful TIDAL session/subscription responses reporting
`status=ACTIVE`, `subscription.type=FREE` and `premiumAccess=false`.

For the first two accounts, `HI_RES_LOSSLESS`, `LOSSLESS` and `HIGH` each returned
HTTP 401 / substatus 5003: “Requested quality is not allowed in user's
subscription”. The third returned HTTP 429 at all three qualities, without
Retry-After. Omitting playback countryCode, as the old app did, produced the same
LOSSLESS failure for each account. Exact searches returned “Shape of You” by
Ed Sheeran, duration 234000 ms; there was no observed metadata mismatch.

Both TIDAL and Qobuz discovery returned empty streaming/API lists. Qobuz, Deezer
and Apple Music account lists were also empty. Upstream rukamori `dev`
(`8f25a9e21f7b988d2f9b07a01f56457d15e651f4`) and `main`
(`ede84aeed68a39786000ece94abf83cf108363e0`) contain no corresponding account,
pool or lossless resolver implementation to port; their TIDAL canvas provider
does not address playback.

The client now distinguishes TIDAL subscription refusals before interpreting
HTTP 401, tries only lower supported full-stream qualities, preserves the real
token recovery path, cools down rate-limited accounts and skips absent instances.
The Settings refresh forces a fetch and observes credential-free loading,
cached counts, attempt time and error state. Pure Kotlin regressions cover
classification/report policy, quality fallback bounds, cancellation, cooldowns,
lossless preference across accounts and the verified metadata match.

Lossless is expected only with an account whose subscription and client grant
allow it, or a configured working lossless instance. None of these pooled
accounts demonstrated any playable full quality during verification. The pool
operator must validate actual premiumAccess/quality entitlements, correct its
premium flags, supply working paid TIDAL accounts and address rate limiting.
Qobuz requires contributed valid accounts or working instances. A client fix
cannot supply those server-side entitlements.

Validation is limited to the permitted repository checker and 26 iOS Python
fixture tests. Android/shared Kotlin compilation and unit-test execution, CI,
and installed-app playback/Settings checks remain unrun locally.
