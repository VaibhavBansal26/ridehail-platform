export type MapSettings = { tileUrl: string; attribution: string };

/** OpenStreetMap's public tile server: fine for a demo, but its usage policy forbids heavy use. Set MAP_TILE_URL for production. */
export const DEFAULT_TILE_URL = "https://tile.openstreetmap.org/{z}/{x}/{y}.png";
export const DEFAULT_ATTRIBUTION = "\u00a9 OpenStreetMap contributors";

/** Read on the server for every request, so one build serves every deployment. */
export function mapSettingsFromEnv(env: Record<string, string | undefined> = process.env): MapSettings {
  return {
    tileUrl: env.MAP_TILE_URL?.trim() || DEFAULT_TILE_URL,
    attribution: env.MAP_TILE_ATTRIBUTION?.trim() || DEFAULT_ATTRIBUTION,
  };
}

/** The origin the Content-Security-Policy must allow for map tiles ("{s}" subdomains become a wildcard); "" if the URL is unusable. */
export function tileOrigin(tileUrl: string): string {
  const m = /^(https?:\/\/[^/]+)/.exec(tileUrl.replace("{s}", "*"));
  return m ? m[1]! : "";
}
