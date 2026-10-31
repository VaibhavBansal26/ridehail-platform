/**
 * The only backend calls the rider app's browser may make, by method and path. The proxy forwards nothing else, so a
 * stolen session cookie or a bug in the page cannot reach admin or customer endpoints through this app.
 * Paths are without the leading "/api/".
 */
const UUID = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";

const ALLOWED: [method: string, path: RegExp][] = [
  ["GET", /^riders\/me$/],
  ["POST", /^riders\/me\/(online|offline)$/],
  ["PUT", /^riders\/me\/location$/],
  ["GET", /^riders\/me\/offers$/],
  ["POST", new RegExp(`^riders/me/offers/${UUID}/(accept|decline)$`)],
  ["GET", /^riders\/me\/earnings$/],
  ["GET", /^riders\/me\/verification$/],
  ["PUT", /^riders\/me\/verification\/documents\/(DRIVING_LICENCE|VEHICLE_REGISTRATION|INSURANCE|SELFIE)$/],
  ["POST", /^riders\/me\/verification\/submit$/],
  ["GET", /^riders\/me\/payouts$/],
  ["POST", /^riders\/me\/payouts$/],
  ["POST", /^riders\/me\/payout-account\/onboarding$/],
  ["GET", /^trips\/active$/],
  ["GET", new RegExp(`^trips/${UUID}$`)],
  ["POST", new RegExp(`^trips/${UUID}/(arrive|start|complete)$`)],
];

/** Only these characters may appear in a path segment we forward: no dots (so no "..") and no encoded separators. */
const SEGMENT = /^[A-Za-z0-9_-]+$/;

/** The backend path for a proxied call, or null if the rider app may not make it. `segments` are after /api/backend/. */
export function backendPath(method: string, segments: string[]): string | null {
  if (segments.length < 2 || segments[0] !== "api" || !segments.every((s) => SEGMENT.test(s))) return null;
  const rest = segments.slice(1).join("/");
  return ALLOWED.some(([m, re]) => m === method && re.test(rest)) ? "/api/" + rest : null;
}
