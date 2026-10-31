import { NextRequest } from "next/server";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { GET as realtime } from "@/app/api/session/realtime/route";
import { POST as login } from "@/app/api/session/login/route";
import { POST as logout } from "@/app/api/session/logout/route";
import { POST as register } from "@/app/api/session/register/route";
import { GET as proxyGet, POST as proxyPost, PUT as proxyPut } from "@/app/api/backend/[...path]/route";
import { ACCESS_COOKIE, CSRF_HEADER, CSRF_VALUE, REFRESH_COOKIE } from "@/lib/session";

const HOST = "customer.test";
const ID = "3f2b8c1e-9d4a-4e7b-8a21-5c6d7e8f9a0b";

function req(method: string, path: string, body?: unknown, extra: Record<string, string> = {}) {
  return new NextRequest(`http://${HOST}${path}`, {
    method,
    headers: { host: HOST, origin: `http://${HOST}`, [CSRF_HEADER]: CSRF_VALUE, "content-type": "application/json", ...extra },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
}
const ctx = (path: string) => ({ params: Promise.resolve({ path: path.split("/") }) });

const fetchMock = vi.fn();
beforeEach(() => vi.stubGlobal("fetch", fetchMock));
afterEach(() => {
  fetchMock.mockReset();
  vi.unstubAllGlobals();
});
const reply = (body: unknown, status = 200, headers: Record<string, string> = {}) =>
  new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json", ...headers } });
const auth = (role: string) => reply({ accessToken: "SECRET-AT", refreshToken: "SECRET-RT", role, profileId: "p1" });

describe("sign-in", () => {
  it("starts a session for a customer and never puts a token in the response body", async () => {
    fetchMock.mockResolvedValue(auth("CUSTOMER"));
    const res = await login(req("POST", "/api/session/login", { phone: " +1555 ", password: "pw-pw-pw-1" }));
    expect(res.status).toBe(200);
    expect(JSON.stringify(await res.json())).not.toContain("SECRET");
    expect(res.cookies.get(ACCESS_COOKIE)).toMatchObject({ value: "SECRET-AT", httpOnly: true, sameSite: "strict", path: "/" });
    expect(res.cookies.get(REFRESH_COOKIE)).toMatchObject({ value: "SECRET-RT", httpOnly: true, path: "/api" });
    expect(JSON.parse(fetchMock.mock.calls[0]![1].body)).toEqual({ phone: "+1555", password: "pw-pw-pw-1" });
  });

  it("refuses a rider's or an admin's account exactly like a wrong password", async () => {
    fetchMock.mockResolvedValueOnce(auth("RIDER"));
    const asRider = await login(req("POST", "/api/session/login", { phone: "+1", password: "x" }));
    fetchMock.mockResolvedValueOnce(auth("ADMIN"));
    const asAdmin = await login(req("POST", "/api/session/login", { phone: "+1", password: "x" }));
    fetchMock.mockResolvedValueOnce(reply({}, 401));
    const wrong = await login(req("POST", "/api/session/login", { phone: "+1", password: "x" }));
    for (const res of [asRider, asAdmin, wrong]) {
      expect(res.status).toBe(401);
      expect(await res.json()).toEqual({ detail: "Wrong phone or password" });
      expect(res.cookies.get(ACCESS_COOKIE)).toBeUndefined();
    }
  });

  it("passes a lockout through with how long to wait", async () => {
    fetchMock.mockResolvedValue(reply({}, 429, { "retry-after": "42" }));
    const res = await login(req("POST", "/api/session/login", { phone: "+1", password: "x" }));
    expect(res.status).toBe(429);
    expect(res.headers.get("retry-after")).toBe("42");
    expect((await res.json()).detail).toMatch(/42 seconds/);
  });

  it("rejects cross-site and malformed requests before calling the API", async () => {
    expect((await login(req("POST", "/api/session/login", { phone: "+1", password: "x" }, { origin: "http://evil.test" }))).status).toBe(403);
    expect((await login(req("POST", "/api/session/login", { phone: "+1", password: "x" }, { [CSRF_HEADER]: "" }))).status).toBe(403);
    expect((await login(req("POST", "/api/session/login", { phone: "", password: "" }))).status).toBe(400);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("says when the API cannot be reached", async () => {
    fetchMock.mockRejectedValue(new TypeError("fetch failed"));
    expect((await login(req("POST", "/api/session/login", { phone: "+1", password: "x" }))).status).toBe(502);
  });
});

describe("registration", () => {
  const good = { name: "Ravi", phone: "+1555", password: "password123" };

  it("creates a customer account and signs in", async () => {
    fetchMock.mockResolvedValue(auth("CUSTOMER"));
    const res = await register(req("POST", "/api/session/register", good));
    expect(res.status).toBe(201);
    expect(fetchMock.mock.calls[0]![0]).toBe("http://localhost:8080/api/auth/register/customer");
    expect(res.cookies.get(ACCESS_COOKIE)?.value).toBe("SECRET-AT");
  });

  it.each([
    [{ ...good, name: " " }, "name"],
    [{ ...good, password: "short" }, "password"],
    [{ ...good, password: "x".repeat(73) }, "password"],
  ])("refuses bad details (%#) without calling the API", async (body, what) => {
    const res = await register(req("POST", "/api/session/register", body));
    expect(res.status).toBe(400);
    expect((await res.json()).detail.toLowerCase()).toContain(what);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("says when the number is taken", async () => {
    fetchMock.mockResolvedValue(reply({}, 409));
    const res = await register(req("POST", "/api/session/register", good));
    expect(res.status).toBe(409);
    expect((await res.json()).detail).toMatch(/already registered/);
  });
});

describe("sign-out", () => {
  it("clears both cookies", async () => {
    const res = await logout(req("POST", "/api/session/logout"));
    expect(res.cookies.get(ACCESS_COOKIE)?.value).toBe("");
    expect(res.cookies.get(REFRESH_COOKIE)?.value).toBe("");
  });
  it("is not something another site can trigger", async () => {
    expect((await logout(req("POST", "/api/session/logout", undefined, { origin: "http://evil.test" }))).status).toBe(403);
  });
});

describe("realtime token", () => {
  it("needs the custom header", async () => {
    const res = await realtime(req("GET", "/api/session/realtime", undefined, { [CSRF_HEADER]: "" }));
    expect(res.status).toBe(403);
  });
});

describe("the proxy", () => {
  const cookie = { cookie: `${ACCESS_COOKIE}=AT` };

  it("forwards an allowed call", async () => {
    fetchMock.mockResolvedValue(reply({ id: "c1" }));
    const res = await proxyGet(req("GET", "/api/backend/api/customers/me", undefined, cookie), ctx("api/customers/me"));
    expect(res.status).toBe(200);
    expect(fetchMock.mock.calls[0]![0]).toBe("http://localhost:8080/api/customers/me");
  });

  it("forwards a quote request as POST with its body", async () => {
    fetchMock.mockResolvedValue(reply({ quoteId: "q1" }));
    const body = { vehicleType: "SEDAN", pickupLat: 1, pickupLon: 2, dropLat: 3, dropLon: 4 };
    const res = await proxyPost(req("POST", "/api/backend/api/fares/quote", body, cookie), ctx("api/fares/quote"));
    expect(res.status).toBe(200);
    expect(fetchMock.mock.calls[0]![1].method).toBe("POST");
    expect(JSON.parse(fetchMock.mock.calls[0]![1].body)).toEqual(body);
  });

  it.each([
    ["GET", "api/admin/trips"],
    ["POST", `api/admin/trips/${ID}/cancel`],
    ["GET", "api/riders/me"],
    ["POST", "api/riders/me/online"],
    ["POST", `api/trips/${ID}/arrive`],
    ["PUT", "api/customers/me/payment-method"],   // the dev card shortcut is off unless DEV_CARD_SHORTCUT=true
    ["GET", "api/customers/me/../../admin/trips"],
  ])("refuses %s /%s without ever calling the API", async (method, path) => {
    const handler = method === "GET" ? proxyGet : method === "PUT" ? proxyPut : proxyPost;
    const res = await handler(req(method, `/api/backend/${path}`, method === "GET" ? undefined : {}, cookie), ctx(path));
    expect(res.status).toBe(404);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("refuses a cross-site write even for an allowed path", async () => {
    const res = await proxyPost(req("POST", "/api/backend/api/trips", {}, { ...cookie, origin: "http://evil.test" }), ctx("api/trips"));
    expect(res.status).toBe(403);
    expect(fetchMock).not.toHaveBeenCalled();
  });
});
