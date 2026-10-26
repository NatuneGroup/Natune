# Update feed contract (client ↔ CI)

This is the contract the CI session's release workflows (`_release-artifacts.yml`,
`nightly.yml`, `release.yml`) must emit for the KMP updater. The client code that
consumes it lives in `shared-domain/src/commonMain/.../update/` and
`shared-data/.../update/SharedUpdateChecker.kt`. It mirrors the Android app's
`UpdateFeedManifest.kt` exactly — do not change the schema, the field names, or the
channel values.

## Channels

| Channel | Source branch | Tag shape | Manifest asset |
|---|---|---|---|
| `stable` | `main` | `vX.Y.Z` (semver, optional `-suffix`) | `stable.json` |
| `nightly` | `dev` | `N<yyyyMMdd>` optionally `N<yyyyMMddHHmm>` | `nightly.json` |

Canary is retired. The client treats a stored `CANARY`/`DAILY_NIGHTLY` selection
as `NIGHTLY` and drops the `canary_*` cache keys once (see
`UpdateCheckPolicy.canaryChannelMigration`); CI must not emit a `canary.json`.

## Manifest document (schema 1)

One JSON object per channel, uploaded as a release asset on the fixed
`update-feed` release (replaced in place each run — the URL is stable):

```json
{
  "schema": 1,
  "channel": "nightly",
  "tag": "N20261027",
  "versionName": "N20261027",
  "versionCode": 36300,
  "commit": "abc1234",
  "publishedAt": "2026-10-27T00:00:00Z",
  "releaseUrl": "https://github.com/NatuneGroup/ArchiveTune/releases/tag/N20261027",
  "notes": "…release body…",
  "assets": [
    { "name": "app-gms-mobile-arm64-nightly.apk", "url": "…/download/N20261027/app-gms-mobile-arm64-nightly.apk", "size": 123, "sha256": "…" }
  ]
}
```

Hard requirements the parser enforces (`UpdateFeedManifest.parseUpdateFeedManifest`):

- `schema` must equal `1` exactly.
- `channel` is compared **case-insensitively** to the channel name
  (`stable`/`nightly`); a mismatch or blank channel discards the manifest.
- `tag` must be non-blank; `versionCode` must be a positive number
  (`<= 0` discards the whole manifest).
- `releaseUrl` may be blank — the client falls back to
  `<releases>/tag/<tag>`; `notes` may be blank — the UI then shows
  "release notes unavailable".
- `assets[]` entries need `name` + `url`; `size`/`sha256` are optional.
- Asset selection is **exact-name first, then the host's matcher**
  (`selectUpdateFeedAssetUrl`). Names therefore must keep the documented
  shapes: `app-gms-mobile-<arch>-{release|nightly}.apk`,
  `app-foss-mobile-universal-release.apk`, `app-gms-tv-universal-release.apk`,
  `natune-<os>-<arch>-<version>.<ext>`, `natune-ios-<version>.ipa`.

## versionCode semantics (the allocation edge)

The manifest's `versionCode` is compared **strictly `>`** against the installed
build's code (`updateEligibility`: equal or lower codes are `NOT_NEWER`; missing
or `<= 0` codes are `UNKNOWN_BUILD_CODE` and produce no offer). A manifest entry
with no installable asset for the host is surfaced as "newer but not
installable", never as an offer.

Allocation: nightly codes are `NIGHTLY_FLOOR + <minutes since the dev epoch>`;
stable codes follow the Android app's versioning. Two CI builds landing inside
the same minute therefore share one `versionCode`. Documented tie-break
(implemented in `UpdateReleaseSelection.buildNumberOrNull` and the eligibility
path):

- Equality is identity: a client already on a build with that code is never
  re-offered the twin build.
- Candidate ordering is the feed's: manifests/releases are deduplicated by tag
  and ordered newest first (`mergeManifestRelease`, `findLatestRelease`),
  so the later build wins for anyone older.
- CI should prefer bumping the tag's minute-suffix (`N<yyyyMMddHHmm>`) when two
  runs land in one window so both builds keep distinct, ordered identities even
  though the code is shared.

## What the client does with it

1. `SharedUpdateChecker` fetches `…/download/update-feed/<channel>.json` before
   every `api.github.com` call (CDN-served, not rate-limited). On a manifest
   hit it merges the manifest release into the cached release list and writes
   both caches; on a miss it falls back to the releases API with ETag
   conditional requests and 403/429 reset-aware backoff.
2. A newer-code manifest entry with a matching artifact becomes the offer;
   `downloadUrl` is the direct asset URL for `DIRECT_INSTALL` hosts and the
   release page for `OPEN_URL` hosts.
3. `latestRelease`/`allReleases`/`allPreReleases` keep the same caching TTLs as
   the app: stable 6 h, nightly 15 min, commit history 15 min.

## Stamping the desktop build identity

Desktop builds read `DesktopBuildIdentity.VERSION_CODE`, generated from
`-PdesktopVersionCode` (or `VERSION_CODE_OVERRIDE`) in
`desktopApp/build.gradle.kts`. Release workflows must pass the same allocated
code as the manifest's `versionCode`; a local `runDistributable` keeps `0` and
the updater reports itself unavailable — that is deliberate, not a bug.

`desktopDisplayVersion`/`VERSION_NAME_OVERRIDE` produce the tag-shaped
`VERSION_NAME` (`N<yyyyMMdd>` for dev builds); `isNightlyBuild` is derived from
`VERSION_NAME.startsWith("N")`.

## Feed publication mechanics (for the CI session)

The existing `_release-artifacts.yml` + `.github/scripts/update_manifest.py`
already implement this contract: run the script per channel with the release's
signed artifacts, then upload `<channel>.json` to the `update-feed` release's
assets. Keep the runner labels on the org's blacksmith pools
(`blacksmith-*vcpu-ubuntu-2404`, `blacksmith-6vcpu-macos-*` for iOS/macOS).
Never publish secrets into the manifest; it is fetched unauthenticated.
