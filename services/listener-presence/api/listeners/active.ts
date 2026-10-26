import { summarize } from "../../lib/aggregate";
import { json } from "../../lib/http";
import { readActive, redisFromEnv, TTL_SECONDS } from "../../lib/store";

export const config = { runtime: "edge" };

export default async function handler(request: Request): Promise<Response> {
  if (request.method !== "GET") {
    return json({ error: "Method not allowed" }, 405, { allow: "GET" });
  }

  const redis = redisFromEnv();
  if (redis === null) {
    return json({ error: "Listener presence is not configured" }, 503);
  }

  const now = new Date();
  const { total, listeners } = await readActive(redis, now.getTime());
  // The aggregate holds no per-listener data, so a short shared-cache lifetime is safe and keeps polling cheap.
  return json(summarize(listeners, total, now, TTL_SECONDS), 200, {
    "cache-control": "public, s-maxage=15, stale-while-revalidate=30",
  });
}
