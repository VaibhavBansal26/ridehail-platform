/** OpenStreetMap's public tile server: fine for a demo, but its usage policy forbids heavy use. Set EXPO_PUBLIC_MAP_TILE_URL (at build time) for production. */
export const MAP_TILE_URL = process.env.EXPO_PUBLIC_MAP_TILE_URL?.trim() || "https://tile.openstreetmap.org/{z}/{x}/{y}.png";
export const MAP_ATTRIBUTION = process.env.EXPO_PUBLIC_MAP_ATTRIBUTION?.trim() || "\u00a9 OpenStreetMap contributors";
