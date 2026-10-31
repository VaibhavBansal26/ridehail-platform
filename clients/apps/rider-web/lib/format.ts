export function money(amount: number, currency = "USD"): string {
  try {
    return new Intl.NumberFormat(undefined, { style: "currency", currency }).format(amount);
  } catch {
    return `${amount.toFixed(2)} ${currency}`;
  }
}

export function km(distance: number): string {
  return distance < 1 ? `${Math.round(distance * 1000)} m` : `${distance.toFixed(1)} km`;
}

export function titleCase(s: string): string {
  return s.toLowerCase().replace(/_/g, " ").replace(/^\w/, (c) => c.toUpperCase());
}

export function dateTime(iso: string): string {
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleString(undefined, { day: "numeric", month: "short", hour: "2-digit", minute: "2-digit" });
}

/** Whole seconds left until `iso`, never negative. */
export function secondsUntil(iso: string, now: number): number {
  const t = new Date(iso).getTime();
  return Number.isNaN(t) ? 0 : Math.max(0, Math.ceil((t - now) / 1000));
}

/** A link that opens the rider's own navigation app at a point (Google Maps on most phones, Apple Maps via redirect). */
export function directionsUrl(lat: number, lon: number): string {
  return `https://www.google.com/maps/dir/?api=1&destination=${lat},${lon}&travelmode=driving`;
}
