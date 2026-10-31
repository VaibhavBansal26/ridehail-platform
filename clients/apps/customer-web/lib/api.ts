import { cookieAuth, createApi } from "@ridehail/api";
import { CSRF_HEADER, CSRF_VALUE } from "@/lib/session";

function toLogin() {
  if (typeof window !== "undefined" && window.location.pathname !== "/login") {
    const here = window.location.pathname + window.location.search;
    // a full page load on purpose: it drops every cached query along with the dead session
    // eslint-disable-next-line @next/next/no-location-assign-relative-destination
    window.location.assign("/login?next=" + encodeURIComponent(here));
  }
}

/** Browser-side client for this app's own same-origin proxy. The session is in httpOnly cookies; nothing to store here. */
export const api = createApi({
  baseUrl: "/api/backend",
  auth: cookieAuth({ extraHeaders: { [CSRF_HEADER]: CSRF_VALUE }, onSignedOut: toLogin }),
});

/** Posts to this app's session routes (sign-in, registration, sign-out), which are not part of the proxied API. */
export async function sessionCall(path: string, body?: unknown): Promise<{ ok: boolean; status: number; detail?: string; retryAfter?: number }> {
  let res: Response;
  try {
    res = await fetch(path, {
      method: "POST",
      credentials: "same-origin",
      headers: { "Content-Type": "application/json", [CSRF_HEADER]: CSRF_VALUE },
      body: JSON.stringify(body ?? {}),
    });
  } catch {
    return { ok: false, status: 0, detail: "Cannot reach the server. Check your connection." };
  }
  if (res.ok) return { ok: true, status: res.status };
  const data = (await res.json().catch(() => ({}))) as { detail?: string; retryAfter?: number };
  return { ok: false, status: res.status, detail: data.detail ?? `Request failed (${res.status})`, retryAfter: data.retryAfter };
}

/** A short-lived token and the WebSocket address for the realtime connection. Null if there is no session. */
export async function fetchRealtimeToken(): Promise<{ token: string; url: string } | null> {
  let res: Response;
  try {
    res = await fetch("/api/session/realtime", { credentials: "same-origin", headers: { [CSRF_HEADER]: CSRF_VALUE }, cache: "no-store" });
  } catch {
    return null; // offline: the connection retries on its own
  }
  if (res.status === 401) {
    toLogin();
    return null;
  }
  if (!res.ok) return null;
  const body = (await res.json().catch(() => null)) as { token?: unknown; url?: unknown } | null;
  return typeof body?.token === "string" && typeof body.url === "string" ? { token: body.token, url: body.url } : null;
}
