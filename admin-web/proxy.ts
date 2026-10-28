import { NextRequest, NextResponse } from "next/server";
import { ACCESS_COOKIE, PUBLIC_PATHS } from "@/lib/session";

/**
 * Runs before every page request: sends a fresh Content-Security-Policy nonce and keeps signed-out visitors on /login.
 * The cookie check here is only a convenience (no flash of a broken page); the backend enforces the ADMIN role on every call.
 */
function tileOrigin(): string {
  try {
    const sample = (process.env.MAP_TILE_URL || "https://tile.openstreetmap.org/{z}/{x}/{y}.png")
      .replace("{s}", "a").replace("{z}", "0").replace("{x}", "0").replace("{y}", "0");
    return new URL(sample).origin;
  } catch {
    return "";
  }
}

export function proxy(request: NextRequest) {
  const { pathname } = request.nextUrl;
  const signedIn = request.cookies.has(ACCESS_COOKIE);
  const isPublic = PUBLIC_PATHS.includes(pathname);

  if (!signedIn && !isPublic) {
    const url = new URL("/login", request.url);
    if (pathname !== "/") url.searchParams.set("next", pathname + request.nextUrl.search);
    return NextResponse.redirect(url);
  }
  if (signedIn && isPublic) return NextResponse.redirect(new URL("/", request.url));

  const nonce = Buffer.from(crypto.randomUUID()).toString("base64");
  const dev = process.env.NODE_ENV === "development";
  const plainHttp = process.env.ALLOW_INSECURE_COOKIES === "true";
  const csp = [
    "default-src 'self'",
    `script-src 'self' 'nonce-${nonce}' 'strict-dynamic'${dev ? " 'unsafe-eval'" : ""}`,
    // the dev server injects <style> tags without a nonce; production serves real stylesheets, so it keeps the strict form
    dev ? "style-src 'self' 'unsafe-inline'" : `style-src 'self' 'nonce-${nonce}'`,
    // Leaflet positions its markers with style attributes; scripts and stylesheets stay locked down
    "style-src-attr 'unsafe-inline'",
    `img-src 'self' data: blob: ${tileOrigin()}`.trim(),
    "font-src 'self'",
    "connect-src 'self'",
    "object-src 'none'",
    "base-uri 'self'",
    "form-action 'self'",
    "frame-ancestors 'none'",
    // a local run over plain http (ALLOW_INSECURE_COOKIES) must not have its own requests rewritten to https
    ...(dev || plainHttp ? [] : ["upgrade-insecure-requests"]),
  ].join("; ");

  const headers = new Headers(request.headers);
  headers.set("x-nonce", nonce);
  headers.set("Content-Security-Policy", csp);
  const response = NextResponse.next({ request: { headers } });
  response.headers.set("Content-Security-Policy", csp);
  response.headers.set("Cache-Control", "no-store");
  return response;
}

export const config = {
  matcher: [
    // everything except the API routes (they authenticate themselves), static files and prefetches
    {
      source: "/((?!api|_next/static|_next/image|favicon.ico|robots.txt).*)",
      missing: [
        { type: "header", key: "next-router-prefetch" },
        { type: "header", key: "purpose", value: "prefetch" },
      ],
    },
  ],
};
