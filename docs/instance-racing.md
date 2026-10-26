# Tidal public instances and the resolver race

Tidal playback can come from three places, in this order: the user's own signed-in account,
shared accounts from the community Source Pool (see the pool note in
[claude/ARCHITECTURE.md](claude/ARCHITECTURE.md)), and public HiFi/QQDL proxy instances. This
document covers the third: where the instance list comes from and how
`tidal/TidalAudioProvider.kt` resolves a track against it.

The instances are not bundled. `DEFAULT_DOWNLOAD_API_ENDPOINTS` is deliberately empty, so nothing
is ever baked into the APK or silently used, and a user who never adds an instance simply falls
through to the next audio source. When the active list is empty, `resolve()` throws
`TIDAL playback has no configured instance` rather than searching the catalogue for a stream.

## Where the list comes from

| Source | Where it lives | When it is used |
|---|---|---|
| The user's own entries | `TidalInstancesKey` (newline-separated), edited in Settings → Tidal | Always, and merged first |
| Health-verified discoveries | `TidalVerifiedInstancesKey`, written by `TidalInstanceHealthManager` | After the user's entries, as `healthyUrls()` |
| The pool's discovery feed | `$SOURCE_PROVIDER_URL/api/discovery/tidal` when `BuildConfig.SOURCE_PROVIDER_URL` is non-blank | During a scan that asks for discovery |

The callers do the merge (`MusicService`, and `LosslessStreamResolver` for the download path):
configured entries first, then every instance the last scan found healthy, deduplicated, into
`TidalAudioProvider.setInstances()`. Discovery only ever adds — a scan never removes a user entry.

There used to be a second discovery feed (`monochrome.tf`'s uptime list). It is left unset in
`INSTANCE_DISCOVERY_SOURCES`: the host is gone, and a fixed candidate list only cost startup time
and made installs look as if they were fetching instances from a site the user never asked for.

## The race

`requestDirectFlac` starts one coroutine per endpoint inside `supervisorScope` under
`runBlocking(Dispatchers.IO)` — `resolve()` itself is a plain blocking function, and callers run it
from an IO context. Each coroutine posts `endpoint to Result<Resolved>` to a `Channel`:

- The first **full-quality** result wins: it is returned immediately and the remaining coroutines
  are cancelled, so a slow mirror cannot delay playback that a fast one has already served.
- A downgraded AAC result does not win. It is held in `deferredAacFallback` while the race waits
  for a possible full-quality result from another mirror, and is returned only once every endpoint
  has answered.
- A non-rate-limited failure is recorded per endpoint (`name: message`) and puts that instance on a
  cooldown; a rate-limited one is counted instead. If every endpoint fails, the aggregated messages
  are what `TidalAudioResolutionException` reports.

## Ordering, health and cooldowns

`orderedEndpoints()` puts instances that are currently cooling down last, so healthy ones are
tried first and cooling ones are reached only when the whole list is cooling. A success clears the
instance's cooldown (`markInstanceHealthy`); failures start one:

- **Soft, 60 s** (`INSTANCE_SOFT_COOLDOWN_MS`) — transient failures: HTTP 5xx, timeouts, and any
  failure that is not a connection-level one.
- **Hard, 600 s** (`INSTANCE_HARD_COOLDOWN_MS`) — `UnknownHostException` / `ConnectException`,
  i.e. the host is unreachable or its DNS does not resolve.

Cooldowns live in the resolver's in-memory map (`instanceCooldownUntilMs`, a `ConcurrentHashMap`),
so they are per-process and do not survive a restart. That is separate from
`TidalInstanceHealthManager`, which probes the configured list and persists what it found:

- **HEALTHY** — reachable and served a full (non-preview) lossless manifest for the probe track.
- **PREVIEW_ONLY** — reachable, but the backing account is unsubscribed, so it only serves 30 s
  previews. The UI labels this "deprecated".
- **UNREACHABLE** — no usable response.

Scans are serialised (`scanMutex`, `scanInProgress`), probe the candidates one after another (the
startup scan spaces them by 350 ms so launch traffic does not burst), and skip the network entirely
when the merged candidate list is empty. Startup runs a staggered scan, a background worker re-runs
it, and Settings → Tidal probes on demand when the user taps **Test instances** — that screen never
probes by itself.

A PREVIEW response is rejected at play time as well as in the scan, so an unsubscribed instance
falls through to the next instance or source instead of playing a 30 s clip.

## Rate limiting

When every endpoint answered rate-limited, the longest `Retry-After` is kept and the resolver sets
a process-level cooldown (`resolverRateLimitedUntilMs`); until it expires, `resolve()` throws
`TidalRateLimitedException` immediately rather than re-racing the same list. `TidalDns` carries a
DoH fallback so login and streaming survive an ISP that DNS-blocks `tidal.com`; its endpoints are
hardcoded provider IPs, so on a network that blocks the DoH hosts too, a VPN or a Private DNS
resolver is the only route.

## What the settings screen exposes

Settings → Tidal lists the instances with a per-entry status and ping, and offers add, bulk paste,
remove, remove-everything-matching-a-status, and copy-online. The stored value is the user's list;
when it is empty the resolver's list is empty too — there is no hidden default to fall back on.

## Related code

- `tidal/TidalAudioProvider.kt` — resolver, race, cooldowns, discovery, preview rejection.
- `tidal/TidalInstanceHealthManager.kt` — probing, persistence, healthy-first feed.
- `tidal/TidalDns.kt` — DoH fallback.
- `ui/screens/settings/TidalSettings.kt` — instance management UI.
- `playback/MusicService.kt` (`resolveMultiSourceDataSpec` path) and
  `playback/LosslessStreamResolver.kt` — the merge into `setInstances()`.
