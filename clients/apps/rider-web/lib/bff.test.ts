import { NextRequest } from "next/server";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { forward, realtimeToken, rejectCrossSite, tokenExpiry } from "@/lib/bff";
import { ACCESS_COOKIE, CSRF_HEADER, CSRF_VALUE, REFRESH_COOKIE } from "@/lib/session";

const b64 = (o: unknown) => Buffer.from(JSON.stringify(o)).toString("base64url");
/** An unsigned token with the given lifetime: the BFF only reads `exp`, the backend verifies the signature. */
const jwt = (secondsLeft: number) => `${b64({ alg: "HS256" })}.${b64({ exp: Math.floor(Date.now() / 1000) + secondsLeft })}.sig`;

const HOST = "rider.test";
function req(method: string, path: string, opts: { cookies?: Record<string, string>; headers?: Record<string, string>; body?: string } = {}) {
  const cookie = Object.entries(opts.cookies ?? {}).map(([k, v]) => `${k}=${v}`).join("; ");
  return new NextRequest(`http://${HOST}${path}`, {
    method,
    headers: { host: HOST, [CSRF_HEADER]: CSRF_VALUE, ...(method === "GET" ? {} : { origin: `http://${HOST}` }), ...(cookie ? { cookie } : {}), ...opts.headers },
    body: opts.body,
  });
}

const fetchMock = vi.fn();
beforeEach(() => vi.stubGlobal("fetch", fetchMock));
afterEach(() => {
  fetchMock.mockReset();
  vi.unstubAllGlobals();
});
const reply = (body: unknown, status = 200, headers: Record<string, string> = {}) =>
  new Response(body === null ? null : JSON.stringify(body), { status, headers: { "content-type": "application/json", ...headers } });
const refreshOk = (role = "RIDER") => reply({ accessToken: "A2", refreshToken: "R2", role, profileId: "r1" });

describe("rejectCrossSite", () => {
  it("needs the custom header on every call, reads included", () => {
    expect(rejectCrossSite(req("GET", "/api/x"))).toBeNull();
    expect(rejectCrossSite(req("GET", "/api/x", { headers: { [CSRF_HEADER]: "0" } }))?.status).toBe(403);
    const bare = new NextRequest(`http://${HOST}/api/x`, { headers: { host: HOST } });
    expect(rejectCrossSite(bare)?.status).toBe(403);
  });

  it("lets a write through only from this site's own origin", () => {
    expect(rejectCrossSite(req("POST", "/api/x"))).toBeNull();
    expect(rejectCrossSite(req("POST", "/api/x", { headers: { origin: "http://evil.test" } }))?.status).toBe(403);
    const noOrigin = new NextRequest(`http://${HOST}/api/x`, { method: "POST", headers: { host: HOST, [CSRF_HEADER]: CSRF_VALUE } });
    expect(rejectCrossSite(noOrigin)?.status).toBe(403);
  });
});

describe("forward", () => {
  it("refuses a call with no session at all", async () => {
    expect((await forward(req("GET", "/api/backend/api/riders/me"), "/api/riders/me")).status).toBe(401);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("sends the cookie's token as a bearer and nothing else from the browser", async () => {
    fetchMock.mockResolvedValue(reply({ id: "r1" }));
    const res = await forward(req("GET", "/x?a=1", { cookies: { [ACCESS_COOKIE]: "AT" }, headers: { "x-evil": "1" } }), "/api/riders/me");
    expect(res.status).toBe(200);
    const [url, init] = fetchMock.mock.calls[0]!;
    expect(url).toBe("http://localhost:8080/api/riders/me?a=1");
    expect(init.headers).toEqual({ Authorization: "Bearer AT", Accept: "application/json" });
  });

  it("renews an expired session once, repeats the call and sets the new cookies", async () => {
    fetchMock.mockResolvedValueOnce(reply({}, 401)).mockResolvedValueOnce(refreshOk()).mockResolvedValueOnce(reply({ id: "r1" }));
    const res = await forward(req("GET", "/x", { cookies: { [ACCESS_COOKIE]: "old", [REFRESH_COOKIE]: "R1" } }), "/api/riders/me");
    expect(res.status).toBe(200);
    expect(fetchMock.mock.calls.map((c) => c[0])).toEqual([
      "http://localhost:8080/api/riders/me",
      "http://localhost:8080/api/auth/refresh",
      "http://localhost:8080/api/riders/me",
    ]);
    expect(fetchMock.mock.calls[2]![1].headers.Authorization).toBe("Bearer A2");
    expect(res.cookies.get(ACCESS_COOKIE)?.value).toBe("A2");
    expect(res.cookies.get(REFRESH_COOKIE)?.value).toBe("R2");
  });

  it("ends the session, clearing cookies, when it cannot be renewed", async () => {
    fetchMock.mockResolvedValueOnce(reply({}, 401)).mockResolvedValueOnce(reply({}, 401));
    const res = await forward(req("GET", "/x", { cookies: { [ACCESS_COOKIE]: "old", [REFRESH_COOKIE]: "R1" } }), "/api/riders/me");
    expect(res.status).toBe(401);
    expect(res.cookies.get(ACCESS_COOKIE)?.value).toBe("");
    expect(res.cookies.get(REFRESH_COOKIE)?.value).toBe("");
  });

  it("does not keep a session that refreshes into someone else's role", async () => {
    fetchMock.mockResolvedValueOnce(reply({}, 401)).mockResolvedValueOnce(refreshOk("ADMIN"));
    const res = await forward(req("GET", "/x", { cookies: { [ACCESS_COOKIE]: "old", [REFRESH_COOKIE]: "R1" } }), "/api/riders/me");
    expect(res.status).toBe(401);
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it("sends a document's bytes exactly as they are, with the right content type and the query", async () => {
    fetchMock.mockResolvedValue(reply({ status: "NOT_SUBMITTED" }));
    // every byte value, including the ones a text round trip would mangle
    const bytes = Uint8Array.from({ length: 256 }, (_, i) => i);
    const request = new NextRequest(`http://${HOST}/x?expiresOn=2030-01-01`, {
      method: "PUT",
      headers: { host: HOST, origin: `http://${HOST}`, [CSRF_HEADER]: CSRF_VALUE, "content-type": "application/octet-stream", cookie: `${ACCESS_COOKIE}=AT` },
      body: bytes,
    });
    const res = await forward(request, "/api/riders/me/verification/documents/DRIVING_LICENCE");
    expect(res.status).toBe(200);
    const [url, init] = fetchMock.mock.calls[0]!;
    expect(url).toBe("http://localhost:8080/api/riders/me/verification/documents/DRIVING_LICENCE?expiresOn=2030-01-01");
    expect(init.headers["Content-Type"]).toBe("application/octet-stream");
    expect(Array.from(init.body as Uint8Array)).toEqual(Array.from(bytes));
  });

  it("refuses an enormous upload without reading it or calling the API", async () => {
    const request = new NextRequest(`http://${HOST}/x`, {
      method: "PUT",
      headers: { host: HOST, origin: `http://${HOST}`, [CSRF_HEADER]: CSRF_VALUE, "content-type": "application/octet-stream", "content-length": String(50 * 1024 * 1024), cookie: `${ACCESS_COOKIE}=AT` },
      body: new Uint8Array([1]),
    });
    expect((await forward(request, "/api/riders/me/verification/documents/DRIVING_LICENCE")).status).toBe(413);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("passes a 429 and its Retry-After through (location updates are rate limited)", async () => {
    fetchMock.mockResolvedValue(reply({ detail: "slow down" }, 429, { "retry-after": "3" }));
    const res = await forward(req("PUT", "/x", { cookies: { [ACCESS_COOKIE]: "AT" }, body: "{}" }), "/api/riders/me/location");
    expect(res.status).toBe(429);
    expect(res.headers.get("retry-after")).toBe("3");
  });

  it("passes a 204 through with no body", async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 204 }));
    const res = await forward(req("GET", "/x", { cookies: { [ACCESS_COOKIE]: "AT" } }), "/api/trips/active");
    expect(res.status).toBe(204);
    expect(await res.text()).toBe("");
  });

  it("answers 502 when the API cannot be reached", async () => {
    fetchMock.mockRejectedValue(new TypeError("fetch failed"));
    const res = await forward(req("GET", "/x", { cookies: { [ACCESS_COOKIE]: "AT" } }), "/api/riders/me");
    expect(res.status).toBe(502);
  });
});

describe("realtimeToken", () => {
  it("returns the current token when it has plenty of life left, without calling the API", async () => {
    const at = jwt(600);
    const res = await realtimeToken(req("GET", "/api/session/realtime", { cookies: { [ACCESS_COOKIE]: at } }));
    expect(await res.json()).toEqual({ token: at, url: "ws://localhost:8080/ws" });
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("renews a token that is about to expire, and the cookies with it", async () => {
    fetchMock.mockResolvedValue(refreshOk());
    const res = await realtimeToken(req("GET", "/api/session/realtime", { cookies: { [ACCESS_COOKIE]: jwt(20), [REFRESH_COOKIE]: "R1" } }));
    expect((await res.json()).token).toBe("A2");
    expect(res.cookies.get(ACCESS_COOKIE)?.value).toBe("A2");
  });

  it("renews when the access cookie is gone but the refresh cookie is not", async () => {
    fetchMock.mockResolvedValue(refreshOk());
    const res = await realtimeToken(req("GET", "/api/session/realtime", { cookies: { [REFRESH_COOKIE]: "R1" } }));
    expect(res.status).toBe(200);
  });

  it("is a 401 with no usable session", async () => {
    expect((await realtimeToken(req("GET", "/api/session/realtime"))).status).toBe(401);
    fetchMock.mockResolvedValue(reply({}, 401));
    expect((await realtimeToken(req("GET", "/api/session/realtime", { cookies: { [REFRESH_COOKIE]: "R1" } }))).status).toBe(401);
  });
});

describe("tokenExpiry", () => {
  it("reads exp and shrugs off garbage", () => {
    expect(tokenExpiry(jwt(100))).toBeGreaterThan(Date.now() / 1000);
    for (const junk of ["", "abc", "a.b.c", "a..c", `a.${b64({ exp: "soon" })}.c`]) expect(tokenExpiry(junk)).toBeNull();
  });
});
