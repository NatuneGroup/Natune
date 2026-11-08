# Natune module catalog

Nine readable ArchiveTune API-v1 packages; no preinstalled providers or credentials.
See [module architecture, comparisons and limits](../../docs/modules.md).

- `upstream/extension/`: unchanged ArchiveTuneModules reference sources and icons.
- `extension/`: active provider manifests, sources and generated single-file scripts.
- `lib/`: common request/validation helpers and ArchivePool crypto, bundled at build time.
- `scripts/package_module.py`: original upstream packager, notices preserved.
- `scripts/build_catalog.py`: deterministic source bundling, validation and catalog generation.
- `dist/`: distributable `.arch` packages; `registry.json`: checksummed catalog.
- `tests/providers.test.js`: synthetic CI fixtures (no live credentials/requests).

Build with `python3 addons/modules/scripts/build_catalog.py`.
Update its `BRANCH` and the in-app catalog suggestion after merge.
Original LGPL/GPL notices are retained in `NOTICE.md`, `LICENSE.md`, `COPYING.md`,
upstream manifests and the packager; Natune additions retain their stated GPL terms.
