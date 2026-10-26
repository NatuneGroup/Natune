import { Redis } from "@upstash/redis";
import type { StoredListener } from "./aggregate";

/** A listener drops off the map this long after its last playing heartbeat (2.5 times the 60 second interval). */
export const TTL_SECONDS = 150;
/** One playing heartbeat per listener within this window. A normal client sends one per minute. */
export const RATE_LIMIT_SECONDS = 20;
/** Upper bound on live entries read in one summary, so a flood cannot make a single response unbounded. */
export const READ_LIMIT = 10000;

const INDEX_KEY = "lp:index";
const LIVE_PREFIX = "lp:live:";
const RATE_PREFIX = "lp:rate:";
const encoder = new TextEncoder();

export function redisFromEnv(): Redis | null {
  const url = process.env.UPSTASH_REDIS_REST_URL;
  const token = process.env.UPSTASH_REDIS_REST_TOKEN;
  if (!url || !token) return null;
  // Raw strings in and out: stored records are our own JSON and must not be reinterpreted by the client.
  return new Redis({ url, token, automaticDeserialization: false });
}

/** The server-side secret for keyed listener hashes. Anything shorter than 32 characters counts as unconfigured. */
export function idHashSecret(): string | null {
  const secret = process.env.ID_HASH_SECRET;
  return secret && secret.length >= 32 ? secret : null;
}

/** HMAC-SHA256 of the client identifier. Only this keyed hash is ever stored; a database dump reveals no raw ID. */
export async function keyedListenerHash(listenerId: string, secret: string): Promise<string> {
  const key = await crypto.subtle.importKey(
    "raw",
    encoder.encode(secret),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const mac = await crypto.subtle.sign("HMAC", key, encoder.encode(listenerId));
  return Array.from(new Uint8Array(mac), (byte) => byte.toString(16).padStart(2, "0")).join("");
}

/** Records a playing heartbeat. Returns false when the same listener already sent one inside the rate-limit window. */
export async function recordPlaying(
  redis: Redis,
  hash: string,
  record: StoredListener,
  nowMs: number,
): Promise<boolean> {
  const first = await redis.set(RATE_PREFIX + hash, "1", { nx: true, ex: RATE_LIMIT_SECONDS });
  if (first !== "OK") return false;
  await redis
    .pipeline()
    .set(LIVE_PREFIX + hash, JSON.stringify(record), { ex: TTL_SECONDS })
    // Expired index members are cleared on writes as well as reads. The index itself expires one TTL after its last
    // write; every member's score is at most that moment, so an idle index cannot keep an expired keyed hash.
    .zremrangebyscore(INDEX_KEY, 0, nowMs)
    .zadd(INDEX_KEY, { score: nowMs + TTL_SECONDS * 1000, member: hash })
    .expire(INDEX_KEY, TTL_SECONDS)
    .exec();
  return true;
}

/** Removes a listener at once on pause, stop or opt-out. The rate-limit key goes too, so a quick resume is accepted. */
export async function recordStopped(redis: Redis, hash: string): Promise<void> {
  await redis
    .pipeline()
    .del(LIVE_PREFIX + hash)
    .del(RATE_PREFIX + hash)
    .zrem(INDEX_KEY, hash)
    .exec();
}

/** The live count plus the entries read for the public breakdown, which is capped at READ_LIMIT. */
export interface ActiveRead {
  readonly total: number;
  readonly listeners: StoredListener[];
}

/**
 * Live entries whose TTL has not passed. Expired index members are dropped on each read. The total is counted
 * before the cap, so the headline number stays exact even when the breakdown is a sample.
 */
export async function readActive(redis: Redis, nowMs: number): Promise<ActiveRead> {
  await redis.zremrangebyscore(INDEX_KEY, 0, nowMs);
  const total = await redis.zcard(INDEX_KEY);
  if (total === 0) return { total, listeners: [] };
  const hashes = await redis.zrange<string[]>(INDEX_KEY, 0, READ_LIMIT - 1);
  if (hashes.length === 0) return { total, listeners: [] };
  const values = await redis.mget<(string | null)[]>(...hashes.map((hash) => LIVE_PREFIX + hash));
  const listeners: StoredListener[] = [];
  for (const raw of values) {
    if (typeof raw !== "string") continue;
    try {
      listeners.push(JSON.parse(raw) as StoredListener);
    } catch {
      // A malformed entry is skipped rather than failing the whole public summary.
    }
  }
  return { total, listeners };
}
