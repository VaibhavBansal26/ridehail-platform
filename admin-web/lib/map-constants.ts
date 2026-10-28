/** Shared by the map and its side panel. Kept apart from components/live-map.tsx, which loads Leaflet and so may only run in a browser. */
export const RIDER_COLOR: Record<string, string> = { AVAILABLE: "#1a9d55", ON_TRIP: "#2557d6", OFFLINE: "#8a93a3" };
export const TRIP_COLOR: Record<string, string> = { REQUESTED: "#d98200", ACCEPTED: "#2557d6", ARRIVED: "#2557d6", IN_PROGRESS: "#7a3fd1" };
/** An online rider whose last position is older than this has stopped reporting. */
export const STALE_MS = 2 * 60_000;

export type Layers = { riders: boolean; trips: boolean };
export type Selection = { kind: "rider" | "trip"; id: string } | null;
