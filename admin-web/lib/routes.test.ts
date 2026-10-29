// @vitest-environment node
import { NextRequest } from "next/server";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { POST as login } from "@/app/api/session/login/route";
import { POST as logout } from "@/app/api/session/logout/route";
import { GET as proxyGet } from "@/app/api/admin/[...path]/route";
import { ACCESS_COOKIE, CSRF_HEADER, CSRF_VALUE, REFRESH_COOKIE } from "@/lib/session";

const HOST = "admin.example.com";
const sameSite = { host: HOST, origin: `https://${HOST}`, [CSRF_HEADER]: CSRF_VALUE, "content-type": "application/json" };
const post = (url: string, body: unknown, headers: Record<string, string> = sameSite) =>
  new NextRequest(`https://${HOST}${url}`, { method: "POST", headers, body: typeof body === "string" ? body : JSON.stringify(body) });
const reply = (status: number, body: unknown) => new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });

let fetchMock: ReturnType<typeof vi.fn>;
beforeEach(() => {
  fetchMock = vi.fn();
  vi.stubGlobal("fetch", fetchMock);
});
afterEach(() => vi.unstubAllGlobals());

describe("POST /api/session/login", () => {
  it("signs an admin in with httpOnly, SameSite=Strict, Secure cookies and never returns the tokens", async () => {
    fetchMock.mockResolvedValueOnce(reply(200, { accessToken: "AT", refreshToken: "RT", role: "ADMIN", profileId: "p" }));
    const res = await login(post("/api/session/login", { phone: "admin", password: "pw" }));

    expect(res.status).toBe(200);
    expect(JSON.stringify(await res.json())).not.toMatch(/AT|RT/);
    const cookies = res.headers.getSetCookie();
    const access = cookies.find((c) => c.startsWith(`${ACCESS_COOKIE}=`))!;
    const refresh = cookies.find((c) => c.startsWith(`${REFRESH_COOKIE}=`))!;
    for (const c of [access, refresh]) {
      expect(c).toMatch(/HttpOnly/i);
      expect(c).toMatch(/SameSite=strict/i);
      expect(c).toMatch(/Secure/i);
    }
    expect(access).toMatch(/Path=\/(;|$)/);
    expect(refresh).toMatch(/Path=\/api(;|$)/);   // the refresh token is only ever sent to API routes
  });

  it("gives a customer or rider the same answer as a wrong password and sets no cookie", async () => {
    fetchMock.mockResolvedValueOnce(reply(200, { accessToken: "AT", refreshToken: "RT", role: "CUSTOMER", profileId: "p" }));
    const asCustomer = await login(post("/api/session/login", { phone: "c", password: "pw" }));
    fetchMock.mockResolvedValueOnce(reply(401, {}));
    const wrong = await login(post("/api/session/login", { phone: "admin", password: "bad" }));

    expect(asCustomer.status).toBe(401);
    expect(await asCustomer.json()).toEqual(await wrong.json());
    expect(asCustomer.headers.getSetCookie()).toEqual([]);
  });

  it("is refused from another site", async () => {
    const res = await login(post("/api/session/login", { phone: "admin", password: "pw" }, { ...sameSite, origin: "https://evil.example" }));
    expect(res.status).toBe(403);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it.each([["not json"], [{}], [{ phone: "a" }], [{ phone: 1, password: 2 }]])("rejects a malformed body %j without calling the API", async (body) => {
    const res = await login(post("/api/session/login", body));
    expect(res.status).toBe(400);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("reports an unreachable API and a rate limit distinctly", async () => {
    fetchMock.mockRejectedValueOnce(new TypeError("down"));
    expect((await login(post("/api/session/login", { phone: "a", password: "b" }))).status).toBe(502);
    fetchMock.mockResolvedValueOnce(new Response("{}", { status: 429, headers: { "Retry-After": "42" } }));
    const limited = await login(post("/api/session/login", { phone: "a", password: "b" }));
    expect(limited.status).toBe(429);
    expect(limited.headers.get("Retry-After")).toBe("42");
    expect((await limited.json()).detail).toContain("42 seconds");
    expect(limited.headers.getSetCookie()).toEqual([]);
  });
});

describe("POST /api/session/logout", () => {
  it("clears both cookies", async () => {
    const res = await logout(post("/api/session/logout", ""));
    const cookies = res.headers.getSetCookie().join(" | ");
    expect(cookies).toMatch(new RegExp(`${ACCESS_COOKIE}=;.*Max-Age=0`));
    expect(cookies).toMatch(new RegExp(`${REFRESH_COOKIE}=;.*Max-Age=0`));
  });
  it("is refused from another site", async () => {
    expect((await logout(post("/api/session/logout", "", { ...sameSite, origin: "https://evil.example" }))).status).toBe(403);
  });
});

describe("/api/admin/[...path]", () => {
  const get = (path: string) =>
    proxyGet(new NextRequest(`https://${HOST}/api/admin/${path}`, { headers: { host: HOST, cookie: `${ACCESS_COOKIE}=AT` } }), {
      params: Promise.resolve({ path: path.split("?")[0].split("/") }),
    });

  it("only ever reaches /api/admin/ on the backend", async () => {
    fetchMock.mockResolvedValueOnce(reply(200, {}));
    await get("trips");
    expect(fetchMock.mock.calls[0][0]).toBe("http://localhost:8080/api/admin/trips");
  });

  it.each([["..%2Factuator"], ["a.b"], ["trips/..%2Fx"]])("404s a sneaky path %s", async (p) => {
    expect((await get(p)).status).toBe(404);
    expect(fetchMock).not.toHaveBeenCalled();
  });
});
