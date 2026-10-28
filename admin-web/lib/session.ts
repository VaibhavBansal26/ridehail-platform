/** Cookie names and rules shared by the proxy (route gate) and the session route handlers. */
export const ACCESS_COOKIE = "rh_at";
export const REFRESH_COOKIE = "rh_rt";
/** Sent on every state-changing call to the BFF; a cross-site form cannot set a custom header. */
export const CSRF_HEADER = "x-ridehail-admin";
export const CSRF_VALUE = "1";
/** The refresh token lives 7 days on the backend; the cookies outlive the (15 min) access JWT and get refreshed on 401. */
export const SESSION_MAX_AGE_SECONDS = 7 * 24 * 60 * 60;

export const PUBLIC_PATHS = ["/login"];

export function safeNextPath(raw: string | null | undefined): string {
  // only same-site absolute paths: "//evil.com" and "/\evil.com" are protocol-relative tricks
  if (!raw || !raw.startsWith("/") || raw.startsWith("//") || raw.startsWith("/\\")) return "/";
  return raw;
}
