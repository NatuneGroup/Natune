import type { StoredListener } from "../lib/aggregate";
import { readGeo } from "../lib/geo";
import { empty, json } from "../lib/http";
import { parseHeartbeat } from "../lib/payload";
import { idHashSecret, keyedListenerHash, recordPlaying, recordStopped, redisFromEnv } from "../lib/store";

// The Edge runtime speaks Web Request and Response directly, so the handler needs no Node adapter.
export const config = { runtime: "edge" };

export default async function handler(request: Request): Promise<Response> {
  if (request.method !== "POST") {
    return json({ error: "Method not allowed" }, 405, { allow: "POST" });
  }

  const secret = idHashSecret();
  const redis = redisFromEnv();
  if (secret === null || redis === null) {
    // An unconfigured deployment refuses writes rather than store listeners without a keyed hash.
    return json({ error: "Listener presence is not configured" }, 503);
  }

  const parsed = await parseHeartbeat(request);
  if (!parsed.ok) {
    return json({ error: parsed.error }, parsed.status);
  }
  const heartbeat = parsed.value;
  const hash = await keyedListenerHash(heartbeat.id, secret);

  if (heartbeat.state === "stopped") {
    await recordStopped(redis, hash);
    return empty(204);
  }

  // Only the platform and coarse geography from Vercel's headers are kept. The raw ID, the IP address and the app
  // version are never written to storage.
  const record: StoredListener = { platform: heartbeat.platform, ...readGeo(request.headers) };
  const accepted = await recordPlaying(redis, hash, record, Date.now());
  if (!accepted) {
    return json({ error: "Too many heartbeats" }, 429, { "retry-after": "20" });
  }
  return empty(204);
}
