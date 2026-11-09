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
| `nightly` | `dev` | `N<yyyyMMddHHmm>` (clients also accept legacy `N<yyyyMMdd>`) | `nightly.json` |

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
  "tag": "N202610270000",
  "versionName": "N202610270000",
  "versionCode": 1793066033,
  "commit": "abc1234",
  "publishedAt": "2026-10-27T00:00:00Z",
  "releaseUrl": "https://github.com/NatuneGroup/Natune/releases/tag/N202610270000",
  "notes": "…release body…",
  "assets": [
    { "name": "app-gms-mobile-arm64-nightly.apk", "url": "…/download/N202610270000/app-gms-mobile-arm64-nightly.apk", "size": 123, "sha256": "…" }
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
  CI emits `sha256` for published artifacts, and the desktop updater verifies it
  (see "Digest verification" below), so an asset published without one is
  installable but only length-checked.
- Asset selection is **exact-name first, then the host's matcher**
  (`selectUpdateFeedAsset`, which returns the whole asset so `size`/`sha256`
  survive selection; `selectUpdateFeedAssetUrl` is the URL-only shortcut). Names
  therefore must keep the documented shapes:
  `app-gms-mobile-<arch>-{release|nightly}.apk`,
  `app-foss-mobile-universal-release.apk`, `app-gms-tv-universal-release.apk`,
  `natune-<os>-<arch>-<version>.<ext>`, `natune-ios-<version>.ipa`.

## Digest verification

The selected asset's `sha256` travels with the offer
(`ReleaseInfo.sha256` → `UpdateOffer.sha256`) and the desktop download hashes the
stream with SHA-256 as it writes (`JvmUpdateInstaller`). On a mismatch the
`natune-update.download` staging file and its `.source`/`.code` sidecars are
deleted and the download ends in the existing Failed state with an
`Update digest mismatch: expected sha256:…, got sha256:…` message; a truncated
body still fails before the digest is compared. A staged artifact reused from an
earlier run is re-hashed against the digest before it is installed.

A digest is only enforced when the feed publishes one. The `api.github.com`
releases fallback carries no per-asset `sha256`, so that path keeps the
length-only check and logs a line instead of pretending the artifact was
verified. `sha256` may be bare hex or `sha256:<hex>`; both compare equal.

## versionCode semantics (the allocation edge)

The manifest's `versionCode` is compared **strictly `>`** against the installed
build's code (`updateEligibility`: equal or lower codes are `NOT_NEWER`; missing
or `<= 0` codes are `UNKNOWN_BUILD_CODE` and produce no offer). A manifest entry
with no installable asset for the host is surfaced as "newer but not
installable", never as an offer.

Allocation: Stable and Nightly both use `trustedUpgradeVersionCodeFloor` plus
Unix epoch **seconds**, independently of commit count and workflow run ID. Runs
prepared in different seconds get increasing codes. Eligibility stays strictly
higher even if two preparations happen within the same second.

Each Nightly build has an immutable `N<yyyyMMddHHmm>` tag. CI bumps occupied tags
forward by one minute, with a bounded allocation loop. A rerun may replace only
its own release (same commit and `at-build:` code); another build's release or
tag is never replaced. Nightly pruning protects the release referenced by the
published `nightly.json` and skips deletion when that manifest cannot be read.

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
4. Android and desktop treat download HTTP 404/410 as a missing build. They force
   a check of the offer's channel, bypassing both manifest and release-list TTLs,
   and retry once only if an eligible offer has a different identity or asset URL
   and the original channel-selection revision is still current. Otherwise they
   explain that the build was replaced or removed and ask the user to check again.

## Stamping the desktop build identity

Desktop builds read `DesktopBuildIdentity.VERSION_CODE`, generated from
`-PdesktopVersionCode` (or `VERSION_CODE_OVERRIDE`) in
`desktopApp/build.gradle.kts`. Release workflows must pass the same allocated
code as the manifest's `versionCode`; a local `runDistributable` keeps `0` and
the updater reports itself unavailable — that is deliberate, not a bug.

`desktopDisplayVersion`/`VERSION_NAME_OVERRIDE` produce the tag-shaped
`VERSION_NAME` (`N<yyyyMMddHHmm>` for dev builds); `isNightlyBuild` is derived from
`VERSION_NAME.startsWith("N")`.

## Feed publication mechanics (for the CI session)

The existing `_release-artifacts.yml` + `.github/scripts/update_manifest.py`
already implement this contract: run the script per channel with the release's
signed artifacts, then upload `<channel>.json` to the `update-feed` release's
assets. Keep the runner labels on the org's blacksmith pools
(`blacksmith-*vcpu-ubuntu-2404`, `blacksmith-6vcpu-macos-*` for iOS/macOS).
Never publish secrets into the manifest; it is fetched unauthenticated.
