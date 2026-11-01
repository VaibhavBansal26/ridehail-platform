import type { VehicleType } from "./schemas";

export type LatLon = { lat: number; lon: number };

const rad = (d: number) => (d * Math.PI) / 180;

/** Compass bearing from one point to another, in degrees (0 = north, 90 = east). */
export function bearing(from: LatLon, to: LatLon): number {
  const f1 = rad(from.lat);
  const f2 = rad(to.lat);
  const dl = rad(to.lon - from.lon);
  const y = Math.sin(dl) * Math.cos(f2);
  const x = Math.cos(f1) * Math.sin(f2) - Math.sin(f1) * Math.cos(f2) * Math.cos(dl);
  return ((Math.atan2(y, x) * 180) / Math.PI + 360) % 360;
}

/** Straight-line distance in metres (haversine); good enough to tell "has the car moved". */
export function metres(a: LatLon, b: LatLon): number {
  const dLat = rad(b.lat - a.lat);
  const dLon = rad(b.lon - a.lon);
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(rad(a.lat)) * Math.cos(rad(b.lat)) * Math.sin(dLon / 2) ** 2;
  return 2 * 6_371_000 * Math.asin(Math.sqrt(h));
}

/*
 * The pictures of the vehicles and map pins, drawn as SVG strings from presentation attributes only: the app's
 * Content-Security-Policy forbids inline style="" attributes, and Leaflet puts this markup in the page as HTML.
 * Every vehicle is drawn from above and pointing up (north); the map rotates it to the direction of travel.
 */
const PAINT: Record<VehicleType, string> = { BIKE: "#7c3aed", AUTO: "#d97706", SEDAN: "#2557d6", SUV: "#0f766e" };
const GLASS = "#cfe3ff";
const TYRE = "#1f2937";

const BODY: Record<VehicleType, (c: string) => string> = {
  SEDAN: (c) =>
    `<rect x="12" y="6" width="3" height="7" rx="1.5" fill="${TYRE}"/><rect x="25" y="6" width="3" height="7" rx="1.5" fill="${TYRE}"/>` +
    `<rect x="12" y="27" width="3" height="7" rx="1.5" fill="${TYRE}"/><rect x="25" y="27" width="3" height="7" rx="1.5" fill="${TYRE}"/>` +
    `<rect x="13" y="3" width="14" height="34" rx="6" fill="${c}" stroke="#fff" stroke-width="1.5"/>` +
    `<rect x="15" y="11" width="10" height="6" rx="1.5" fill="${GLASS}"/><rect x="15" y="25" width="10" height="5" rx="1.5" fill="${GLASS}"/>` +
    `<rect x="16" y="18" width="8" height="6" rx="1" fill="#fff" opacity=".35"/>`,
  SUV: (c) =>
    `<rect x="11" y="6" width="3" height="8" rx="1.5" fill="${TYRE}"/><rect x="26" y="6" width="3" height="8" rx="1.5" fill="${TYRE}"/>` +
    `<rect x="11" y="27" width="3" height="8" rx="1.5" fill="${TYRE}"/><rect x="26" y="27" width="3" height="8" rx="1.5" fill="${TYRE}"/>` +
    `<rect x="12" y="2" width="16" height="37" rx="5" fill="${c}" stroke="#fff" stroke-width="1.5"/>` +
    `<rect x="14" y="10" width="12" height="6" rx="1.5" fill="${GLASS}"/><rect x="14" y="29" width="12" height="5" rx="1.5" fill="${GLASS}"/>` +
    `<rect x="14" y="18" width="2" height="9" rx="1" fill="#fff" opacity=".5"/><rect x="24" y="18" width="2" height="9" rx="1" fill="#fff" opacity=".5"/>`,
  AUTO: (c) =>
    `<rect x="18" y="2" width="4" height="7" rx="2" fill="${TYRE}"/><rect x="11" y="27" width="3" height="8" rx="1.5" fill="${TYRE}"/><rect x="26" y="27" width="3" height="8" rx="1.5" fill="${TYRE}"/>` +
    `<path d="M20 6 L28 14 L28 34 Q28 37 25 37 L15 37 Q12 37 12 34 L12 14 Z" fill="${c}" stroke="#fff" stroke-width="1.5"/>` +
    `<rect x="14" y="14" width="12" height="5" rx="1.5" fill="${GLASS}"/><rect x="14" y="21" width="12" height="12" rx="2" fill="#1f2937" opacity=".85"/>`,
  BIKE: (c) =>
    `<rect x="18" y="2" width="4" height="9" rx="2" fill="${TYRE}"/><rect x="18" y="29" width="4" height="9" rx="2" fill="${TYRE}"/>` +
    `<rect x="17" y="8" width="6" height="26" rx="3" fill="${c}" stroke="#fff" stroke-width="1.5"/>` +
    `<rect x="9" y="10" width="22" height="3" rx="1.5" fill="${TYRE}"/><circle cx="20" cy="22" r="3.5" fill="#fff" opacity=".9"/>`,
};

/** A vehicle as an <svg> string, `size` pixels wide, rotated `heading` degrees clockwise from north. */
export function vehicleSvg(type: VehicleType, size = 40, heading = 0): string {
  const paint = PAINT[type] ?? PAINT.SEDAN;
  const body = (BODY[type] ?? BODY.SEDAN)(paint);
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${size}" height="${size}" viewBox="0 0 40 40" aria-hidden="true"><g transform="rotate(${heading} 20 20)">${body}</g></svg>`;
}

const PIN: Record<"pickup" | "drop", string> = { pickup: "#16a34a", drop: "#dc2626" };

/** A map pin for the pickup point or the drop-off. */
export function pinSvg(kind: "pickup" | "drop"): string {
  const c = PIN[kind];
  const mark = kind === "pickup" ? `<circle cx="14" cy="13" r="5" fill="#fff"/>` : `<rect x="9.5" y="8.5" width="9" height="9" rx="1.5" fill="#fff"/>`;
  return `<svg xmlns="http://www.w3.org/2000/svg" width="28" height="36" viewBox="0 0 28 36" aria-hidden="true"><path d="M14 35 C14 35 2 21 2 13 A12 12 0 0 1 26 13 C26 21 14 35 14 35 Z" fill="${c}" stroke="#fff" stroke-width="2"/>${mark}</svg>`;
}
