/**
 * Approximate location from the Vercel geolocation headers, read on the server. The client never sends a location, and
 * the IP address itself is never read into storage. Coordinates are rounded to 0.01 degrees, about one kilometre.
 */
export interface Geo {
  readonly country?: string;
  readonly city?: string;
  readonly latitude?: number;
  readonly longitude?: number;
}

const COUNTRY_CODE = /^[A-Z]{2}$/;
const MAX_CITY_LENGTH = 80;

export function readGeo(headers: Headers): Geo {
  const country = (headers.get("x-vercel-ip-country") ?? "").trim().toUpperCase();
  const city = decodeCity(headers.get("x-vercel-ip-city"));
  const latitude = boundedCoordinate(headers.get("x-vercel-ip-latitude"), 90);
  const longitude = boundedCoordinate(headers.get("x-vercel-ip-longitude"), 180);
  return {
    ...(COUNTRY_CODE.test(country) ? { country } : {}),
    ...(city !== undefined ? { city } : {}),
    ...(latitude !== undefined && longitude !== undefined ? { latitude, longitude } : {}),
  };
}

/** Vercel percent-encodes non-ASCII city names, so the header is decoded before it is cleaned up. */
function decodeCity(raw: string | null): string | undefined {
  if (!raw) return undefined;
  let decoded: string;
  try {
    decoded = decodeURIComponent(raw);
  } catch {
    return undefined;
  }
  const cleaned = decoded
    .replace(/[\u0000-\u001f\u007f]/g, " ")
    .replace(/\s+/g, " ")
    .trim()
    .slice(0, MAX_CITY_LENGTH);
  return cleaned.length > 0 ? cleaned : undefined;
}

function boundedCoordinate(raw: string | null, limit: number): number | undefined {
  if (raw === null || raw.trim() === "") return undefined;
  const value = Number(raw);
  if (!Number.isFinite(value) || Math.abs(value) > limit) return undefined;
  return Math.round(value * 100) / 100;
}
