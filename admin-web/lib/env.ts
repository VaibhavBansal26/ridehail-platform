import "server-only";

/** Server-side configuration, read once and validated so a bad deployment fails loudly at the first request. */
function backendUrl(): string {
  const raw = process.env.BACKEND_URL ?? "http://localhost:8080";
  const url = new URL(raw);
  if (url.protocol !== "http:" && url.protocol !== "https:") throw new Error(`BACKEND_URL must be http(s): ${raw}`);
  return url.origin;
}

export const env = {
  get backendUrl() {
    return backendUrl();
  },
  get tileUrl() {
    return process.env.MAP_TILE_URL || "https://tile.openstreetmap.org/{z}/{x}/{y}.png";
  },
  get tileAttribution() {
    return process.env.MAP_TILE_ATTRIBUTION || "© OpenStreetMap contributors";
  },
  get defaultView(): [number, number, number] {
    const parts = (process.env.MAP_DEFAULT_VIEW || "12.9716,77.5946,12").split(",").map(Number);
    return parts.length === 3 && parts.every(Number.isFinite) ? [parts[0], parts[1], parts[2]] : [12.9716, 77.5946, 12];
  },
  /** Cookies are Secure unless this is explicitly turned off (and never in production builds without it). */
  get secureCookies() {
    return process.env.ALLOW_INSECURE_COOKIES !== "true";
  },
};

/** The origin of the tile server, for the Content-Security-Policy img-src. */
export function tileOrigin(): string {
  try {
    return new URL(env.tileUrl.replace("{s}", "a").replace("{z}", "0").replace("{x}", "0").replace("{y}", "0")).origin;
  } catch {
    return "";
  }
}
