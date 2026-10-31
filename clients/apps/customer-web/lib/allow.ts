/**
 * The only backend calls the customer app's browser may make, by method and path. The proxy forwards nothing else, so a
 * stolen session cookie or a bug in the page cannot reach admin or rider endpoints through this app.
 * Paths are without the leading "/api/".
 */
const UUID = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";

const ALLOWED: [method: string, path: RegExp][] = [
  ["GET", /^customers\/me$/],
  ["POST", /^customers\/me\/payment-method\/checkout$/],
  ["POST", /^fares\/quote$/],
  ["POST", /^trips$/],
  ["GET", /^support$/],
  ["POST", /^support\/(messages|read)$/],
  ["GET", /^trips\/active$/],
  ["GET", new RegExp(`^trips/${UUID}$`)],
  ["GET", new RegExp(`^trips/${UUID}/rider-location$`)],
  ["POST", new RegExp(`^trips/${UUID}/cancel$`)],
];

/**
 * Saving a card by sending the server a token is a development shortcut that a real payment provider refuses. It is only
 * forwarded when this deployment says it is a development one, so it can never be reached from a production build.
 */
const DEV_ONLY: [method: string, path: RegExp][] = [["PUT", /^customers\/me\/payment-method$/]];

/** Only these characters may appear in a path segment we forward: no dots (so no "..") and no encoded separators. */
const SEGMENT = /^[A-Za-z0-9_-]+$/;

/** The backend path for a proxied call, or null if the customer app may not make it. `segments` are after /api/backend/. */
export function backendPath(method: string, segments: string[], devShortcuts = process.env.DEV_CARD_SHORTCUT === "true"): string | null {
  if (segments.length < 2 || segments[0] !== "api" || !segments.every((s) => SEGMENT.test(s))) return null;
  const rest = segments.slice(1).join("/");
  const allowed = devShortcuts ? [...ALLOWED, ...DEV_ONLY] : ALLOWED;
  return allowed.some(([m, re]) => m === method && re.test(rest)) ? "/api/" + rest : null;
}
