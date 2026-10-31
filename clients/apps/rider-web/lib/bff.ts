import "server-only";
import { NextRequest, NextResponse } from "next/server";
import { env } from "@/lib/env";
import { ACCESS_COOKIE, CSRF_HEADER, CSRF_VALUE, REFRESH_COOKIE, SESSION_MAX_AGE_SECONDS } from "@/lib/session";

/**
 * Talking to the Spring API on behalf of the signed-in rider. The tokens live in httpOnly cookies and are only ever
 * handled here, on the server; page JavaScript never sees them. (The one exception is the short-lived access token
 * handed out for the WebSocket, see realtimeToken.)
 */

const BACKEND_TIMEOUT_MS = 15_000;
/** A document upload may be a little over the API's own limit (5 MB); the API makes the real decision, this only stops a huge body early. */
const MAX_UPLOAD_BYTES = 6 * 1024 * 1024;
/** Hand out a fresh access token for the WebSocket when the current one has less than this left. */
const MIN_TOKEN_LIFE_SECONDS = 90;

export type Tokens = { accessToken: string; refreshToken: string };
type AuthBody = { accessToken: string; refreshToken: string; role: string };

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
 * Blocks cross-site calls: the request must carry the custom header, and a write must also come from this site's own
 * origin. SameSite=Strict already keeps the cookies off cross-site requests; this is the second lock.
 */
export function rejectCrossSite(req: NextRequest): NextResponse | null {
  if (req.headers.get(CSRF_HEADER) !== CSRF_VALUE) return json({ detail: "Cross-site request refused" }, 403);
  if (req.method === "GET" || req.method === "HEAD") return null;
  const origin = req.headers.get("origin");
  const host = req.headers.get("host");
  try {
    if (origin && host && new URL(origin).host === host) return null;
  } catch {
    /* fall through */
  }
  return json({ detail: "Cross-site request refused" }, 403);
}

async function backend(path: string, init: RequestInit): Promise<Response> {
  return fetch(env.backendUrl + path, {
    ...init,
    cache: "no-store",
    redirect: "manual",
    signal: AbortSignal.timeout(BACKEND_TIMEOUT_MS),
  });
}

export type AuthOutcome = { status: number; tokens?: Tokens; retryAfter?: number };

async function authenticate(path: string, payload: unknown): Promise<AuthOutcome> {
  const res = await backend(path, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload),
  });
  if (!res.ok) {
    const wait = Number(res.headers.get("retry-after"));
    return { status: res.status, retryAfter: Number.isFinite(wait) && wait > 0 ? Math.ceil(wait) : undefined };
  }
  const body = (await res.json()) as AuthBody;
  // this is the rider app: a customer's or an admin's account does not get a session here
  if (body.role !== "RIDER") return { status: 403 };
  return { status: 200, tokens: { accessToken: body.accessToken, refreshToken: body.refreshToken } };
}

export const backendLogin = (phone: string, password: string) => authenticate("/api/auth/login", { phone, password });

export const backendRegister = (name: string, phone: string, password: string, vehicleType: string) =>
  authenticate("/api/auth/register/rider", { name, phone, password, vehicleType });

async function backendRefresh(refreshToken: string): Promise<Tokens | null> {
  const out = await authenticate("/api/auth/refresh", { refreshToken });
  return out.tokens ?? null;
}

/** The `exp` (seconds since 1970) of a JWT, without verifying it: the backend verifies; this only decides when to renew. */
export function tokenExpiry(jwt: string): number | null {
  try {
    const payload = JSON.parse(Buffer.from(jwt.split(".")[1] ?? "", "base64url").toString("utf8")) as { exp?: unknown };
    return typeof payload.exp === "number" ? payload.exp : null;
  } catch {
    return null;
  }
}

/**
 * Forwards one allowed API call. If the access token has expired the refresh token is used once, the call is repeated,
 * and the new cookies ride back on the response. A session that cannot be refreshed ends here: cookies cleared, 401.
 */
export async function forward(req: NextRequest, path: string): Promise<NextResponse> {
  const access = req.cookies.get(ACCESS_COOKIE)?.value;
  const refresh = req.cookies.get(REFRESH_COOKIE)?.value;
  if (!access && !refresh) return json({ detail: "Not signed in" }, 401);

  // a document is binary: it must travel as the bytes it is, never as text (which would corrupt a photo)
  const binary = req.headers.get("content-type")?.startsWith("application/octet-stream") ?? false;
  if (binary && Number(req.headers.get("content-length") ?? 0) > MAX_UPLOAD_BYTES) return json({ detail: "The file is too large" }, 413);
  const body: string | Uint8Array | undefined =
    req.method === "GET" || req.method === "HEAD" ? undefined : binary ? new Uint8Array(await req.arrayBuffer()) : await req.text();
  if (binary && body && body.length > MAX_UPLOAD_BYTES) return json({ detail: "The file is too large" }, 413);
  const call = (token: string) =>
    backend(path + req.nextUrl.search, {
      method: req.method,
      headers: {
        Authorization: `Bearer ${token}`,
        ...(body && body.length > 0 ? { "Content-Type": binary ? "application/octet-stream" : "application/json" } : {}),
        Accept: "application/json",
      },
      body: body && body.length > 0 ? (body as BodyInit) : undefined,
    });

  let renewed: Tokens | null = null;
  let upstream: Response;
  try {
    upstream = access ? await call(access) : new Response(null, { status: 401 });
    if (upstream.status === 401 && refresh) {
      renewed = await backendRefresh(refresh);
      if (!renewed) return ended();
      upstream = await call(renewed.accessToken);
    }
  } catch {
    return json({ detail: "The API is not reachable" }, 502);
  }
  if (upstream.status === 401) return ended();

  const retryAfter = upstream.headers.get("retry-after");
  const text = await upstream.text();
  const out = new NextResponse(upstream.status === 204 ? null : text || null, {
    status: upstream.status,
    headers: {
      "Content-Type": upstream.headers.get("content-type") ?? "application/json",
      "Cache-Control": "no-store",
      ...(retryAfter ? { "Retry-After": retryAfter } : {}),
    },
  });
  if (renewed) setSession(out, renewed);
  return out;
}

function ended(): NextResponse {
  const out = json({ detail: "Session expired" }, 401);
  clearSession(out);
  return out;
}

/**
 * A bearer token for the browser's WebSocket (STOMP CONNECT carries it). It is the same short-lived access token the
 * cookie holds, renewed first if it is about to expire; the browser keeps it in memory only.
 */
export async function realtimeToken(req: NextRequest): Promise<NextResponse> {
  const access = req.cookies.get(ACCESS_COOKIE)?.value;
  const refresh = req.cookies.get(REFRESH_COOKIE)?.value;
  const nowSeconds = Date.now() / 1000;
  const exp = access ? tokenExpiry(access) : null;
  if (access && exp !== null && exp - nowSeconds > MIN_TOKEN_LIFE_SECONDS) {
    return json({ token: access, url: env.realtimeUrl });
  }
  if (!refresh) return ended();
  let renewed: Tokens | null;
  try {
    renewed = await backendRefresh(refresh);
  } catch {
    return json({ detail: "The API is not reachable" }, 502);
  }
  if (!renewed) return ended();
  const out = json({ token: renewed.accessToken, url: env.realtimeUrl });
  setSession(out, renewed);
  return out;
}
