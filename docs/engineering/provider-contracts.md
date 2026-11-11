# Built-in provider contracts

The first provider-removal change separates ordinary provider policy from the
installed module manager. Playback behavior and persisted source order stay the
same during this step.

The plain contracts in
[`ProviderContracts.kt`](../../shared-domain/src/commonMain/kotlin/moe/rukamori/archivetune/shared/domain/provider/ProviderContracts.kt)
name a finite set of compiled provider IDs, purposes, platform availability and
settings destinations. A descriptor describes compiled code; it cannot discover,
install or execute an implementation. Composition advertises only capabilities
its host actually supports. Android currently adapts its three native Spotify
entries from these descriptors to the existing registry UI.

Profiles retain an independent ID, compiled provider ID, own-account/instance
mode, endpoint, opaque credential reference, enabled choice, provider quality
name and credential ordering. The profile port is an additive boundary here;
this change does not persist or migrate installed profiles. Platform credential
owners continue to hold secrets.

[`SourceAttemptPlan.kt`](../../shared-domain/src/commonMain/kotlin/moe/rukamori/archivetune/shared/domain/source/SourceAttemptPlan.kt)
contains the data-only attempt-plan port and its default adapter over the existing
source chain. The shared resolver consumes this adapter. Native attempts carry a
null profile and the DEFAULT credential lane, leaving existing account/Pool
sequencing with each provider. The legacy MODULES attempt keeps its current
position. MODULES is absent from the finite compiled provider IDs; later migration
will expand that slot into ordered built-in profile attempts rather than move
those profiles to an earlier native source slot.

YouTube keeps its separate terminal owner. Disabled and rejected sources are
skipped, sources below YouTube are unreachable unless explicitly pinned, and a
rejected pin yields to the permitted chain. Download admission, low-data policy,
matching, deadlines and cancellation remain with their current owners.

[`SpotifyProviderPolicy.kt`](../../shared-domain/src/commonMain/kotlin/moe/rukamori/archivetune/shared/domain/source/SpotifyProviderPolicy.kt)
uses only enable choices, session-presence flags, stored engine and observed
products. Android's Spotify preference adapter retains the serialized
`extension.spotify.metadata.enabled`, `extension.spotify.alternate.enabled` and
`extension.spotify.librespot.enabled` keys. Absent choices are inferred once from
the old sessions and selected engine; explicit choices survive repeated migration
and later account changes. The old NativeExtensionPolicy and availability helper
names remain compatibility aliases. Ordinary Spotify callers use the new owner.

Premium degradation still reads the stored engine's bound account product.
Session availability does not substitute for native engine availability or token
admission. The unofficial engine still refuses the catalog account; Librespot
continues to bind the library account. The catalog, alternate account and native
grant retain their existing separate lifecycle and secure storage.

The contracts contain no platform preferences, resources, JSON objects, runtime
handles or HTTP clients. Their input/output records can later be adapted behind
a Rust boundary without moving native audio engines or creating another vault.
