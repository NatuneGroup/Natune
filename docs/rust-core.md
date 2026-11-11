# Rust core: TTML first slice

`native/core` is an independent GPL-3.0-only Cargo workspace. `natune-lyrics`
owns bounded XML scanning, timing, rows, agents, background spans, supplements and
credits. `natune-ffi` only converts records, exposes cancellation and maps errors;
it is the only crate that depends on UniFFI. Neither crate has HTTP, caches, Tokio
or persistent threads. `shared-core` generates the binding and maps ordinary
domain models. UI code must never import generated bindings or Rust records.
Dependency direction is UI → domain/data → shared-core → shared-domain.

## Build and rollout

Use Rust 1.99.0 and the committed lockfile:

```sh
cd native/core
export CARGO_TARGET_DIR="$PWD/../../shared-core/build/cargo"
cargo test --locked --workspace
cargo clippy --locked --workspace --all-targets -- -D warnings
cargo fmt --all --check
cargo bench --locked -p natune-lyrics --bench ttml
cargo clean
```

Default Gradle builds use a small unavailable-reader facade with no native artifacts.
Build the experimental native artifacts with `-Pnatune.rustCore=true`; then enable
the persisted `rustTtmlParser` Boolean preference in the ordinary settings store
for the shared host reader. Both controls default off. There is intentionally no
user-facing toggle before performance and platform gates pass. Android's separate
retained BetterLyrics renderer/parser is not switched by this preference.

```sh
./gradlew -Pnatune.androidSdk=false -Pnatune.rustCore=true :shared-core:jvmTest :shared-core:jvmJar
./gradlew -Pnatune.rustCore=true :app:assembleGmsMobileUniversalDebug
./gradlew -Pnatune.rustCore=true :shared-core:iosSimulatorArm64Test :shared-ui:linkDebugFrameworkIosArm64
```

Install target toolchains/NDK in CI before cross-compilation; the build script
never installs them. Android uses NDK 30.0.16248370/API 24, all four delivered ABIs,
and 16 KiB ELF page alignment. JVM JARs embed only the selected job's host library
in JNA's resource prefix; `natune.rustJvmTarget` selects an already-installed target.
iOS cinterop embeds the static Rust archive in the existing framework dependency
graph. Swift does not separately build/link another Rust library.

Gobley v0.3.7 is the current GitHub release checked 2026-11-11. Its version table
requires UniFFI 0.29.4; this workspace pins that exact version, rather than latest
UniFFI 0.32.2. The released Cargo plugin's `CargoPlugin.requiredRustTargets`,
`checkKotlinTargets` and `configureBuildTasks` expect legacy `KotlinAndroidTarget`
and legacy AGP registration. Natune uses Kotlin 2.4.21/AGP 9.2.1/Gradle 9.6.1 and
the new Android KMP library plugin. We therefore use the plan's fallback:
unmodified Gobley CLI generation, with task/source/resource/cinterop wiring
confined to `shared-core` and `scripts/build_rust_core.py`. No dependency downgrade,
checksum disabling, generated-source patching or patched generator is used.

## Behavior and verification

The parser scans XML once into a bounded preorder arena and returns rows and
credits together. It preserves the Kotlin reader's suffix matching, sorted
qualified attributes, CDATA/text distinction, nested-division duplicate rows,
relative-word heuristic, stable sorting and empty word-array behavior. Malformed
or refused XML returns empty rows/default credits; it does not retry a more
permissive parser. Native availability is checked before parsing. Cancellation
is checked during scanning and row/metadata traversal, with at most two Kotlin
workers; tokens are destroyed after conversion. Existing host revision/occurrence
checks continue to reject stale documents.

The 2,000,000-unit input cap counts UTF-16 units, not UTF-8 bytes. Depth is 64 and
element count 100,000. DTD/entity declarations and non-predefined entities are
refused. There is no external entity resolver. Intended departure from the
future unified-model plan: first-slice times retain Kotlin Double arithmetic and
millisecond truncation instead of prematurely quantizing to integer microseconds.
No intended behavioral change from the shared Kotlin reader is accepted.

`scripts/extract_ttml_fixtures.py` extracts literal inputs from retained Kotlin
tests and adds lexical, Unicode, metadata and timing cases. Runtime huge/deep
inputs and bounded mutation tests are generated without committing huge files.
The JVM suite compares every field against the retained two-parse Kotlin API and
checks the optimized one-parse Kotlin baseline separately. CI exports Kotlin
goldens and tests those with the pure Rust crate; both sides share inputs and
goldens. Word tolerances are ≤1 microsecond with identical millisecond truncation.
The existing Kotlin suites remain unchanged and run in ordinary PR CI.

The benchmark corpus is synthetic TTML of realistic 5/50/500 KiB sizes, not a
claim about a particular provider's copyrighted document. Rust timing excludes
FFI; JVM timing includes normalization, suspension, native serialization and
final Kotlin models. It rotates Kotlin-two-parse/Kotlin-one-parse/Rust order,
consumes results, and reports warm p50/p95. The library probe may be warm because
test order is unspecified. It is not a cold-load or allocation/PSS measurement.
`rust_core_apk_report.py` reports per-ABI native ZIP contributions and the whole
universal Debug APK delta; it does not claim per-device installed size or Release
R8 behavior. Platform results and measured numbers belong in the PR report until
the CI evidence is available. No speed, APK, mobile load or memory result should
be inferred from source code alone.

## Add the next slice

Add a pure parser/model boundary in `natune-lyrics` with retained-oracle fixtures
first. Expose one batched document API through `natune-ffi`, regenerate bindings,
and convert to domain records in `shared-core`. Add its own default-off preference
in data composition. Keep providers, caches, persistence and UI policy in their
existing owners until their own slices pass parity and measured end-to-end gates.
Keep new files under the repository line/file budgets and carry dependency notices
in [credits](CREDITS.md); never stage unrelated submodule gitlinks.
