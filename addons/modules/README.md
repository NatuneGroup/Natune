# Natune module catalog

Nine portable ArchiveTune API-v1 audio packages plus one Natune-only LRCLIB lyrics
extension; no preinstalled providers or credentials.
See [module architecture, comparisons and limits](../../docs/modules.md).

- `upstream/extension/`: unchanged ArchiveTuneModules reference sources and icons.
- `extension/`: active provider manifests, sources and generated single-file scripts.
- `natune/`: Natune-only extension sources; `natune-extensions.json`: separate catalog.
- `lib/`: common request/validation helpers and ArchivePool crypto, bundled at build time.
- `scripts/package_module.py`: upstream audio validation plus Natune kind validation; notices preserved.
- `scripts/build_catalog.py`: deterministic source bundling, validation and catalog generation.
- `dist/`: distributable `.arch` packages; `registry.json`: checksummed catalog.
- `tests/providers.test.js`: synthetic CI fixtures (no live credentials/requests).

Build with `python3 addons/modules/scripts/build_catalog.py`.
Update its `BRANCH`, `NATUNE_BRANCH` and the in-app catalog suggestions after merge.
Native Spotify extensions are registered in the Android host and are not JS packages.
The official Android librespot playback binding is absent in the supplied base; see
the implementation report linked from the module documentation.
Original LGPL/GPL notices are retained in `NOTICE.md`, `LICENSE.md`, `COPYING.md`,
upstream manifests and the packager; Natune additions retain their stated GPL terms.

TIDAL and Qobuz each ship as one module. In module settings, choose **Signed-in
account** or **Public instance (no account)** and enter an HTTPS instance endpoint
for the latter. Account credentials are used only in account mode. Existing
`tidal-instance` and `qobuz-instance` installations upgrade offline to the unified
provider, preserving their installation ID, enablement, endpoint and audio quality.
Existing account installations retain their credentials and account mode. The small
APK migration assets upgrade existing installs only; they do not install or enable
modules on a fresh app. Catalog URLs follow `dev`.

The TIDAL and Qobuz source pages have independent **Use my own account**, **Use
ArchivePool** and **Try my own account before the pool** controls. Pool use defaults
on when ArchivePool is installed, including for sources installed afterwards. An
explicit source choice is retained through pool uninstall/reinstall; removing or
disabling the pool prevents leased execution. Source quality is passed unchanged
and a leased route searches only that service. Standalone ArchivePool playback
cannot bypass the pool choice of an installed source. The account/instance mode
remains on the unified page; public-instance mode needs no personal account.

ArchivePool's API-v1 module currently leases TIDAL/Qobuz only. The Amazon and
Deezer API-v1 modules use plaintext instances; their account/DRM/decryption paths
and pool accounts remain in native playback rather than being advertised as JS
module account support.
