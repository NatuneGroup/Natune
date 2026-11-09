# Android APK size reduction

This pass starts from `origin/dev` including merged PR #103 (`646aa8b4`).
It preserves that PR's iOS-only launcher previews and N20261109 baseline pin.
The supplied post-#103 candidate is 22,753,582 bytes; the final signed target is
strictly below 20,000,000 bytes. Required savings are 2,753,583 bytes.

## Implemented reductions and estimates

| Change | Estimated compressed APK savings | Evidence and limits |
| --- | ---: | --- |
| Generated icon-pack PNGs: 384px plus guarded indexed color | about 644,000 bytes | Existing Batik 1.19 has `IndexImage.getIndexedImage`; buildSrc has no WebP encoder. A Pillow median-cut proxy over the 20 pinned 256px previews resized to 384px totals 675,938 bytes, including a truecolor fallback for icon 692860, versus the supplied approximately 1,320,000 stored bytes. This is not Batik output or a measured APK. |
| Static STL for the two standard-package JNI audio libraries; unused Oboe removed | 350,000–420,000 bytes | Supplied attribution identifies only analysis/DSP as shared-STL consumers. Their C++ objects/allocators and caught exceptions stay inside each library; JNI exchanges primitives/arrays or handles returned to the same library. `--exclude-libs,ALL` prevents static archive-symbol interposition. Prebuilt Discord/ONNX configurations conservatively retain `c++_shared`. Exact linking/size requires CI. |
| Librespot `opt-level="z"`, fat LTO | 120,000–250,000 bytes | Attribution estimate only; panic remains `unwind`, codegen-units remains 1, and symbol stripping remains enabled. `catch_unwind` stays intact. |
| One Poppins Regular asset for shared UI, legacy Android Compose and Markwon | 70,901 bytes | The removed Android TTF and retained shared TTF are byte-identical (SHA-256 `707fdc5c8bab57a90061c6a8ed7b70d5ffb82fc810e994e79f90bace890c255a`). Python raw DEFLATE level 6 is 70,901 bytes. The Android blocking font loader and TextView load those exact bytes through the generated Compose asset URI. |
| `natune_logo.png` to lossless WebP | 42,815 bytes | 105,291 to 62,476 source bytes; Pillow verified identical decoded RGBA pixels. Existing shared WebP support and resource name are retained. Both image formats are normally stored in the APK. |
| Retained full Oboe license in shared About licenses | **costs about 4,062 bytes** | Full upstream 1.11.0 LICENSE stays bundled and accessible after removal of the unused dependency; raw DEFLATE level 6 estimate. No existing notice/license is removed. |
| Lossless DEFLATE level 9 before candidate alignment/signing | **unmeasured** | Python's standard-library ZIP writer keeps every entry, payload, ordering, storage method and notice; stored arsc/native entries remain stored. Tests verify round trips. It never enlarges the unsigned ZIP. Rewriting invalidates signatures, so this runs only before the existing historical signing and verification step. Final signing/alignment overhead must be measured separately. |

The estimates before ZIP recompression total approximately **1.22–1.42 MB**.
They do **not** establish the requested 2.75 MB minimum or 3 MB headroom.
Recompression would need roughly another 1.33–1.53 MB to meet the hard limit.
Attempts to download the published baseline APK for a real compression/ELF audit
failed with HTTP/service errors, so no recompression saving or final APK success
is claimed. CI must measure the actual signed bytes; the existing hard gate is
preserved and may still reject the candidate.

The recompression step currently applies to the explicit artifact-only signed
release candidate, not ordinary AGP-produced Nightly APKs. A release promoted
from another workflow must use the same validated packaging sequence before
claiming the candidate's final size.

## Rendering and compatibility bounds

The icon picker uses the generated drawable resource name from the JSON catalog;
activity aliases use the generated adaptive mipmaps. Both still receive the same
artwork, catalog identities, authors and license. The existing 18dp adaptive
foreground insets leave a 72dp foreground, or 288px at xxxhdpi. A 384px raster
retains 33% scaling headroom; legacy 48dp icons need 192px at xxxhdpi.

Only fully opaque rasters receive a palette: Batik's palette generator ignores
alpha, so translucent PNG bytes are retained. Four reserved palette entries keep
corner pixels exact, and adaptive background inspection happens before conversion.
Palettes exceeding RGB mean squared error 16 (PSNR below approximately 36 dB)
are rejected. Encoded palettes are used only if smaller. Rasterization/launcher
visual QA and font rendering remain CI/device validation work, not local proof.

The new signed-candidate ELF audit reads `DT_NEEDED` via program headers even
when section tables are stripped. Standard candidates must neither package nor
require `libc++_shared.so`; optional shared packages can be audited without
`--expect-static`, requiring the runtime in the same ABI. The audit also requires
`resources.arsc` to stay stored. No blanket STL packaging exclusion is used to
hide an unexpected prebuilt dependency.

## Skipped candidates

- **Resource-name collapsing:** AGP is already 9.2.1 with release
  `isShrinkResources=true`, so optimized resource shrinking is already enabled
  by default. Adding `android.r8.optimizedResourceShrinking=true` saves nothing.
  `AppIconRepository` calls `getIdentifier` with catalog names; Coil's framework
  artwork bounds and MiPlay's SystemUI lookup target other packages.
  `MediaLibrarySessionCallback` builds external drawable URIs from resource
  package/type/entry names. `--collapse-resource-names` belongs to aapt2's optimize
  stage; it is not a safe additional link parameter, and the public APK transform
  happens after signing. A new internal-artifact/signing transform would need
  separate compiled validation and name-preservation configuration. No speculative
  flag or ineffective keep.xml was added.
- **SimpMusic Poppins Medium:** the Android font identifies as **Fz Poppins Medium**
  (508,884 bytes), whereas the shared font is **Poppins Medium** (158,576 bytes).
  Their cmap, glyph, hinting and GSUB/GPOS tables differ. Family/coverage/rendering
  equivalence is not established, so substitution would risk typography/locales.
- **Spotify icon duplicate:** both WebPs are byte-identical, but Android consumers
  require a real `R.drawable`, including Android Auto/media-library resource URIs,
  whereas shared Android/iOS/desktop consumers decode a Compose asset. Neither
  resource system can safely alias the other's file without a new bridge.
  Lossless re-encoding was also larger (20,144 versus 19,686 bytes each). Both stay.
- **Broad ProGuard keeps:** left intact because TDLib JNI, Rhino reflection,
  Jaudiotagger and persisted/serialized structures need their contracts. This pass
  provides no compiled/reflection evidence for safely narrowing them.

QuickJS, Rhino, locales, stored arsc, provider order
`YTPlayerUtils → InnerTubeX → NewPipe → Echo`, signing identity and application id
`moe.rukamori.archivetune` remain intact.

## Local validation

- `python3 -m unittest discover -s scripts -p 'test_*.py'`: 257 passed.
- `python3 -m unittest discover -s iosApp/tests -p 'test_*.py'`: 39 passed.
- `python3 scripts/check_repository.py --worktree`: no findings.
- `git diff --check`: clean.

The Python coverage includes synthetic 32/64-bit ELF closures, malformed inputs,
same-ABI STL requirements, stored arsc, ZIP payload/metadata preservation,
recompression-before-alignment-before-signing order, retained notices/fonts,
and the existing 19,999,999 / 20,000,000 / 20,000,001 signed-size boundaries.
No local Gradle/JVM/Kotlin build, lint or test, actual APK signing, emulator/device
QA or manual workflow dispatch was performed. Kotlin/native compilation and the
signed size remain CI gates.
