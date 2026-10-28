// @vitest-environment node
import { NextRequest } from "next/server";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { backendPath, forward, rejectCrossSite } from "@/lib/bff";
import { ACCESS_COOKIE, CSRF_HEADER, CSRF_VALUE, REFRESH_COOKIE } from "@/lib/session";

const HOST = "admin.example.com";

function req(method: string, url: string, init: { headers?: Record<string, string>; cookies?: Record<string, string>; body?: string } = {}) {
  const headers = new Headers({ host: HOST, ...init.headers });
  if (init.cookies) headers.set("cookie", Object.entries(init.cookies).map(([k, v]) => `${k}=${v}`).join("; "));
  return new NextRequest(`https://${HOST}${url}`, { method, headers, body: init.body });
}

const json = (status: number, body: unknown) => new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });

describe("backendPath", () => {
  it("builds admin API paths from plain segments", () => {
    expect(backendPath(["trips"])).toBe("/api/admin/trips");
    expect(backendPath(["trips", "b35dc964-1e02-4719-9498-29d0409bc40b", "cancel"])).toBe("/api/admin/trips/b35dc964-1e02-4719-9498-29d0409bc40b/cancel");
  });
  it.each([[[]], [[".."]], [["trips", ".."]], [["a.b"]], [["a/b"]], [["%2e%2e"]], [[""]], [["x y"]]])("refuses %j", (segments) => {
    expect(backendPath(segments)).toBeNull();
  });
});

describe("rejectCrossSite", () => {
  it("lets reads through untouched", () => {
    expect(rejectCrossSite(req("GET", "/api/admin/trips"))).toBeNull();
  });
  it("accepts a write from our own origin with the header", () => {
    expect(rejectCrossSite(req("POST", "/api/admin/x", { headers: { origin: `https://${HOST}`, [CSRF_HEADER]: CSRF_VALUE } }))).toBeNull();
  });
  it.each([
    ["no origin", { [CSRF_HEADER]: CSRF_VALUE }],
    ["another origin", { origin: "https://evil.example", [CSRF_HEADER]: CSRF_VALUE }],
    ["a lookalike origin", { origin: `https://${HOST}.evil.example`, [CSRF_HEADER]: CSRF_VALUE }],
    ["no header", { origin: `https://${HOST}` }],
    ["a wrong header value", { origin: `https://${HOST}`, [CSRF_HEADER]: "0" }],
    ["an unparseable origin", { origin: "null", [CSRF_HEADER]: CSRF_VALUE }],
  ])("refuses a write with %s", async (_name, headers) => {
    const res = rejectCrossSite(req("POST", "/api/admin/x", { headers }));
    expect(res?.status).toBe(403);
  });
});

describe("forward", () => {
  let fetchMock: ReturnType<typeof vi.fn>;
  beforeEach(() => {
    fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);
  });
  afterEach(() => vi.unstubAllGlobals());

  const setCookies = (res: Response) => res.headers.getSetCookie().join(" | ");

  it("sends the access token as a bearer and passes the answer back", async () => {
    fetchMock.mockResolvedValueOnce(json(200, { items: [] }));
    const res = await forward(req("GET", "/api/admin/trips?page=1", { cookies: { [ACCESS_COOKIE]: "AT" } }), "/api/admin/trips");

    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ items: [] });
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe("http://localhost:8080/api/admin/trips?page=1");
    expect(new Headers(init.headers).get("authorization")).toBe("Bearer AT");
    expect(setCookies(res)).toBe("");
  });

  it("forwards a JSON body on writes", async () => {
    fetchMock.mockResolvedValueOnce(json(200, { ok: true }));
    await forward(req("POST", "/api/admin/incidents/1/resolve", { cookies: { [ACCESS_COOKIE]: "AT" }, body: '{"note":"done"}' }), "/api/admin/incidents/1/resolve");
    const [, init] = fetchMock.mock.calls[0];
    expect(init.method).toBe("POST");
    expect(init.body).toBe('{"note":"done"}');
    expect(new Headers(init.headers).get("content-type")).toBe("application/json");
  });

  it("does not forward the browser's cookies or other headers to the API", async () => {
    fetchMock.mockResolvedValueOnce(json(200, {}));
    await forward(req("GET", "/api/admin/trips", { cookies: { [ACCESS_COOKIE]: "AT", [REFRESH_COOKIE]: "RT" }, headers: { "x-forwarded-for": "1.2.3.4" } }), "/api/admin/trips");
    const headers = new Headers(fetchMock.mock.calls[0][1].headers);
    expect(headers.get("cookie")).toBeNull();
    expect(headers.get("x-forwarded-for")).toBeNull();
  });

  it("refreshes an expired session once, retries, and sets the new cookies", async () => {
    fetchMock
      .mockResolvedValueOnce(json(401, {}))
      .mockResolvedValueOnce(json(200, { accessToken: "AT2", refreshToken: "RT2", role: "ADMIN" }))
      .mockResolvedValueOnce(json(200, { items: [1] }));

    const res = await forward(req("GET", "/api/admin/trips", { cookies: { [ACCESS_COOKIE]: "old", [REFRESH_COOKIE]: "RT" } }), "/api/admin/trips");

    expect(res.status).toBe(200);
    expect(fetchMock).toHaveBeenCalledTimes(3);
    expect(fetchMock.mock.calls[1][0]).toBe("http://localhost:8080/api/auth/refresh");
    expect(JSON.parse(fetchMock.mock.calls[1][1].body)).toEqual({ refreshToken: "RT" });
    expect(new Headers(fetchMock.mock.calls[2][1].headers).get("authorization")).toBe("Bearer AT2");
    expect(setCookies(res)).toContain(`${ACCESS_COOKIE}=AT2`);
    expect(setCookies(res)).toContain(`${REFRESH_COOKIE}=RT2`);
  });

  it("ends the session when the refresh token is no good", async () => {
    fetchMock.mockResolvedValueOnce(json(401, {})).mockResolvedValueOnce(json(401, {}));
    const res = await forward(req("GET", "/api/admin/trips", { cookies: { [ACCESS_COOKIE]: "old", [REFRESH_COOKIE]: "bad" } }), "/api/admin/trips");
    expect(res.status).toBe(401);
    expect(setCookies(res)).toMatch(new RegExp(`${ACCESS_COOKIE}=;.*Max-Age=0`));
  });

  it("will not turn a non-admin refresh into an admin session", async () => {
    fetchMock.mockResolvedValueOnce(json(401, {})).mockResolvedValueOnce(json(200, { accessToken: "x", refreshToken: "y", role: "CUSTOMER" }));
    const res = await forward(req("GET", "/api/admin/trips", { cookies: { [ACCESS_COOKIE]: "old", [REFRESH_COOKIE]: "RT" } }), "/api/admin/trips");
    expect(res.status).toBe(401);
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it("does not retry forever when the new token is refused too", async () => {
    fetchMock
      .mockResolvedValueOnce(json(401, {}))
      .mockResolvedValueOnce(json(200, { accessToken: "AT2", refreshToken: "RT2", role: "ADMIN" }))
      .mockResolvedValueOnce(json(401, {}));
    const res = await forward(req("GET", "/api/admin/trips", { cookies: { [ACCESS_COOKIE]: "old", [REFRESH_COOKIE]: "RT" } }), "/api/admin/trips");
    expect(res.status).toBe(401);
    expect(fetchMock).toHaveBeenCalledTimes(3);
  });

  it("passes the API's own errors through (a 409 stays a 409) without touching the session", async () => {
    fetchMock.mockResolvedValueOnce(json(409, { detail: "Trip is already completed" }));
    const res = await forward(req("POST", "/api/admin/trips/1/cancel", { cookies: { [ACCESS_COOKIE]: "AT" } }), "/api/admin/trips/1/cancel");
    expect(res.status).toBe(409);
    expect(await res.json()).toEqual({ detail: "Trip is already completed" });
    expect(setCookies(res)).toBe("");
  });

  it("says so when the API cannot be reached", async () => {
    fetchMock.mockRejectedValueOnce(new TypeError("fetch failed"));
    const res = await forward(req("GET", "/api/admin/trips", { cookies: { [ACCESS_COOKIE]: "AT" } }), "/api/admin/trips");
    expect(res.status).toBe(502);
  });

  it("refuses a request with no session without calling the API", async () => {
    const res = await forward(req("GET", "/api/admin/trips"), "/api/admin/trips");
    expect(res.status).toBe(401);
    expect(fetchMock).not.toHaveBeenCalled();
  });
});
