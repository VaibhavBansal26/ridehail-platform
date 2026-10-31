import "server-only";
import { NextRequest, NextResponse } from "next/server";
import { env } from "@/lib/env";
import { ACCESS_COOKIE, CSRF_HEADER, CSRF_VALUE, REFRESH_COOKIE, SESSION_MAX_AGE_SECONDS } from "@/lib/session";

/** Talking to the Spring API on behalf of the signed-in admin. Tokens never leave the server: the browser only holds httpOnly cookies. */

const BACKEND_TIMEOUT_MS = 15_000;

export type Tokens = { accessToken: string; refreshToken: string };

export function cookieOptions(maxAge: number, path: string) {
  return { httpOnly: true, sameSite: "strict" as const, secure: env.secureCookies, path, maxAge };
}

export function setSession(res: NextResponse, tokens: Tokens) {
  res.cookies.set(ACCESS_COOKIE, tokens.accessToken, cookieOptions(SESSION_MAX_AGE_SECONDS, "/"));
  // only the API routes ever need the refresh token, so the browser sends it nowhere else
  res.cookies.set(REFRESH_COOKIE, tokens.refreshToken, cookieOptions(SESSION_MAX_AGE_SECONDS, "/api"));
}

export function clearSession(res: NextResponse) {
  res.cookies.set(ACCESS_COOKIE, "", cookieOptions(0, "/"));
  res.cookies.set(REFRESH_COOKIE, "", cookieOptions(0, "/api"));
}

export function json(body: unknown, status = 200) {
  return NextResponse.json(body, { status, headers: { "Cache-Control": "no-store" } });
}

/**
 * Blocks cross-site writes: the request must come from this site's own origin and carry the custom header. SameSite=Strict
 * already keeps the cookies off cross-site requests; this is the second lock, and also covers a browser that ignores it.
 */
export function rejectCrossSite(req: NextRequest): NextResponse | null {
  if (req.method === "GET" || req.method === "HEAD") return null;
  const origin = req.headers.get("origin");
  const host = req.headers.get("host");
  let sameOrigin = false;
  try {
    sameOrigin = !!origin && !!host && new URL(origin).host === host;
  } catch {
    sameOrigin = false;
  }
  if (!sameOrigin || req.headers.get(CSRF_HEADER) !== CSRF_VALUE) return json({ detail: "Cross-site request refused" }, 403);
  return null;
}

async function backend(path: string, init: RequestInit): Promise<Response> {
  return fetch(env.backendUrl + path, {
    ...init,
    cache: "no-store",
    redirect: "manual",
    signal: AbortSignal.timeout(BACKEND_TIMEOUT_MS),
  });
}

export async function backendLogin(phone: string, password: string): Promise<{ status: number; tokens?: Tokens; role?: string; retryAfter?: number }> {
  const res = await backend("/api/auth/login", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ phone, password }),
  });
  if (!res.ok) {
    const wait = Number(res.headers.get("retry-after"));
    return { status: res.status, retryAfter: Number.isFinite(wait) && wait > 0 ? Math.ceil(wait) : undefined };
  }
  const body = (await res.json()) as { accessToken: string; refreshToken: string; role: string };
  return { status: 200, tokens: { accessToken: body.accessToken, refreshToken: body.refreshToken }, role: body.role };
}

async function backendRefresh(refreshToken: string): Promise<Tokens | null> {
  const res = await backend("/api/auth/refresh", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ refreshToken }),
  });
  if (!res.ok) return null;
  const body = (await res.json()) as { accessToken: string; refreshToken: string; role: string };
  return body.role === "ADMIN" ? { accessToken: body.accessToken, refreshToken: body.refreshToken } : null;
}

/** Only these characters may appear in a path segment we forward: no dots (so no "..") and no encoded separators. */
const SEGMENT = /^[A-Za-z0-9_-]+$/;

export function backendPath(segments: string[]): string | null {
  if (segments.length === 0 || !segments.every((s) => SEGMENT.test(s))) return null;
  return "/api/admin/" + segments.join("/");
}

/**
 * Forwards one admin API call. If the access token has expired the refresh token is used once, the call is repeated, and
 * the new cookies ride back on the response. A session that cannot be refreshed ends here: cookies cleared, 401 returned.
 */
export async function forward(req: NextRequest, path: string): Promise<NextResponse> {
  const access = req.cookies.get(ACCESS_COOKIE)?.value;
  const refresh = req.cookies.get(REFRESH_COOKIE)?.value;
  if (!access && !refresh) return json({ detail: "Not signed in" }, 401);

  const body = req.method === "GET" || req.method === "HEAD" ? undefined : await req.text();
  const search = req.nextUrl.search;
  const call = (token: string) =>
    backend(path + search, {
      method: req.method,
      headers: {
        Authorization: `Bearer ${token}`,
        ...(body ? { "Content-Type": "application/json" } : {}),
        Accept: "application/json",
      },
      body: body || undefined,
    });

  let renewed: Tokens | null = null;
  let upstream: Response;
  try {
    upstream = access ? await call(access) : new Response(null, { status: 401 });
    if (upstream.status === 401 && refresh) {
      renewed = await backendRefresh(refresh);
      if (!renewed) {
        const out = json({ detail: "Session expired" }, 401);
        clearSession(out);
        return out;
      }
      upstream = await call(renewed.accessToken);
    }
  } catch {
    return json({ detail: "The API is not reachable" }, 502);
  }
  if (upstream.status === 401) {
    const out = json({ detail: "Session expired" }, 401);
    clearSession(out);
    return out;
  }

  // bytes, not text: an identity document is a picture or a PDF and would be corrupted by a round trip through a string
  const bytes = await upstream.arrayBuffer();
  const passthrough: Record<string, string> = {};
  // keep the headers that make a document safe to show: no sniffing, no caching, and a name
  for (const h of ["content-disposition", "x-content-type-options", "content-security-policy"]) {
    const v = upstream.headers.get(h);
    if (v) passthrough[h] = v;
  }
  const out = new NextResponse(bytes.byteLength > 0 ? bytes : null, {
    status: upstream.status,
    headers: { ...passthrough, "Content-Type": upstream.headers.get("content-type") ?? "application/json", "Cache-Control": "no-store" },
  });
  if (renewed) setSession(out, renewed);
  return out;
}
