# Natune listener presence service

Vercel Functions API and static public map for Natune's opt-in listener presence feature. The app keeps the feature
off until the user turns it on, and it contacts this service only while it is on and music is playing.

## What the service does

- `POST /api/heartbeat` accepts one presence payload from the app and keeps that listener for 150 seconds.
- `GET /api/listeners/active` returns the live aggregate: total, countries, cities with at least 3 listeners, and the
  top city.
- `GET /` serves the public live map from `public/`, which reads the aggregate endpoint.

There are no accounts, no per-listener lookups and no history endpoints.

## Heartbeat contract

Request: `POST /api/heartbeat` with `Content-Type: application/json` and a body of at most 1024 bytes. Exactly five
fields are accepted:

```json
{"schema":1,"id":"0123456789abcdef0123456789abcdef","platform":"android","app":"1.2.3","state":"playing"}
```

| Field | Rule |
| --- | --- |
| `schema` | must be `1` |
| `id` | 32 lowercase hex characters, random per opt-in |
| `platform` | `android`, `desktop` or `ios` |
| `app` | 1 to 32 characters from `0-9 A-Z a-z . _ + -` |
| `state` | `playing` refreshes the listener; `stopped` removes it at once |

Any other field is rejected. The app sends at most one `playing` heartbeat per minute, and a `stopped` message on
pause, stop or opt-out.

Responses:

| Status | Meaning |
| --- | --- |
| `204` | accepted (for both `playing` and `stopped`) |
| `400` | invalid body or field |
| `405` | wrong HTTP method |
| `413` | body larger than 1024 bytes |
| `415` | content type is not JSON |
| `429` | a second `playing` heartbeat from the same listener within 20 seconds |
| `503` | service not configured (a variable is missing) |

Coarse geography comes from Vercel's request headers (`x-vercel-ip-country`, `x-vercel-ip-city`,
`x-vercel-ip-latitude`, `x-vercel-ip-longitude`). The client never sends a location.

## Aggregate contract

`GET /api/listeners/active` returns JSON such as:

```json
{
  "activeListeners": 12,
  "updatedAt": "2026-10-26T12:00:00.000Z",
  "ttlSeconds": 150,
  "countries": [{"country": "DE", "count": 5}],
  "cities": [{"city": "Berlin", "country": "DE", "latitude": 52.52, "longitude": 13.41, "count": 4}],
  "topCity": {"city": "Berlin", "country": "DE", "latitude": 52.52, "longitude": 13.41, "count": 4}
}
```

- A city appears only with at least 3 active listeners. Smaller groups still count toward the total and their country.
- City coordinates are averages of listener coordinates that were rounded to 0.01 degrees.
- `activeListeners` is the full live count. The per-city and per-country breakdown reads at most 10,000 live entries.
- The response may be cached at the CDN for 15 seconds. It contains no per-listener data.

## Environment variables

Set these in the Vercel project for Production (and Preview, if you test previews). Never commit values.

| Name | Secret | Purpose |
| --- | --- | --- |
| `UPSTASH_REDIS_REST_URL` | no | Upstash Redis REST endpoint |
| `UPSTASH_REDIS_REST_TOKEN` | yes | Upstash Redis REST token |
| `ID_HASH_SECRET` | yes | Server-side key for the listener-ID HMAC. At least 32 random characters, for example from `openssl rand -base64 48` |

If any variable is missing, both API routes answer `503`, and the map reports that counts are unavailable. The app has
a separate switch: it stays inactive unless `ARCHIVETUNE_LISTENER_PRESENCE_URL` is set at build time.

Changing `ID_HASH_SECRET` changes every stored hash. Current entries expire within 150 seconds, and the next heartbeat
from each listener re-creates them, so the count recovers without any history being lost (none is kept).

## Deploy

1. **Upstash.** Create a Redis database in the region nearest the Vercel project. Copy its REST URL and REST token.
2. **Vercel.** Create a project from the Natune KMP repository. Set **Root Directory** to `services/listener-presence`
   and the framework preset to **Other**. No build command is needed: `vercel.json` serves `public/` as static files,
   and Vercel installs `@upstash/redis` from `package.json`.
3. **Environment.** Add the three variables above, then deploy.
4. **Check.** `https://<project>.vercel.app/` shows the map. `https://<project>.vercel.app/api/listeners/active` returns
   JSON; with no listeners it reports `activeListeners: 0`.
5. **App build.** Build the app with `ARCHIVETUNE_LISTENER_PRESENCE_URL=https://<project>.vercel.app` (no trailing
   slash). For local builds put it in the untracked `local.properties`; for CI, store it as a secret with the same name.
   Leave it empty to ship with listener presence inactive.
6. **Before a public release.** Fill in the operator name in `PRIVACY.md`, review the Vercel log retention for the
   project, and decide whether to add a custom domain.

## Operator privacy notes

- The code stores a keyed hash of each listener ID, the platform, and approximate country, city and coordinates from the
  platform headers. It does not store an IP address, the raw ID, the app version, the user agent, or any playback data.
- The service writes no logs of its own. Vercel's platform request logs are separate, can contain IP addresses, and
  follow the retention settings of the Vercel project.
- Entries expire 150 seconds after the last heartbeat. There is no history database.
- Rate limiting is per listener: one `playing` heartbeat per 20 seconds. No per-IP limit exists in code. Add one with
  Vercel's firewall if abuse appears.

## Development

- `npm install`, then `npm run typecheck` (TypeScript only). The service has no automated tests yet.
- Layout: `api/` holds the Vercel Functions, `lib/` holds validation, geography, aggregation and storage, and `public/`
  holds the static map.
