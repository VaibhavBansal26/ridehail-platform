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
