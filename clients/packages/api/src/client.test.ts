import { describe, expect, it, vi } from "vitest";
import { bearerAuth, cookieAuth, memoryTokenStore, saveSession } from "./auth";
import { createApi } from "./client";
import { ApiError, ContractError } from "./errors";

const BASE = "http://server";
const trip = {
  id: "t1",
  status: "REQUESTED",
  customerId: "c1",
  riderId: null,
  vehicleType: "SEDAN",
  fare: 9.99,
  surgeMultiplier: 1,
  currency: "USD",
  pickupLat: 12.97,
  pickupLon: 77.59,
  dropLat: 12.93,
  dropLon: 77.62,
};
const authResult = { accessToken: "A2", refreshToken: "R2", role: "RIDER", profileId: "r1" };

type Handler = (url: string, init: RequestInit) => Response | Promise<Response>;

function json(body: unknown, status = 200, headers: Record<string, string> = {}) {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json", ...headers } });
}

function setup(handler: Handler, tokens = { accessToken: "A1", refreshToken: "R1" }) {
  const calls: { url: string; init: RequestInit }[] = [];
  const fetcher = vi.fn(async (url: string, init: RequestInit) => {
    calls.push({ url, init });
    return handler(url, init);
  }) as unknown as typeof fetch;
  const store = memoryTokenStore(tokens);
  const onSignedOut = vi.fn();
  const api = createApi({ baseUrl: BASE, fetch: fetcher, auth: bearerAuth({ store, baseUrl: BASE, fetch: fetcher, onSignedOut }) });
  return { api, calls, store, onSignedOut };
}

describe("requests", () => {
  it("sends the bearer token and validates the body", async () => {
    const { api, calls } = setup(() => json(trip));
    expect((await api.trips.get("t1")).fare).toBe(9.99);
    expect(calls[0]!.url).toBe(`${BASE}/api/trips/t1`);
    expect((calls[0]!.init.headers as Record<string, string>).Authorization).toBe("Bearer A1");
  });

  it("names the field when the server's answer is not what the app expects", async () => {
    const { api } = setup(() => json({ ...trip, status: "TELEPORTING" }));
    const err = await api.trips.get("t1").catch((e) => e);
    expect(err).toBeInstanceOf(ContractError);
    expect(err.message).toContain("status");
  });

  it("turns an unreachable server into a status-0 error with a message a person can read", async () => {
    const { api } = setup(() => {
      throw new TypeError("Network request failed");
    });
    const err = await api.trips.get("t1").catch((e) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect(err.isNetwork).toBe(true);
    expect(err.message).toMatch(/connection/i);
  });

  it("reads the server's message, and Retry-After on a 429", async () => {
    const { api } = setup(() => json({ detail: "Too many location updates" }, 429, { "Retry-After": "3" }));
    const err = await api.rider.updateLocation(12.9, 77.5).catch((e) => e);
    expect(err.isRateLimited).toBe(true);
    expect(err.retryAfterSeconds).toBe(3);
    expect(err.message).toBe("Too many location updates");
  });

  it("finds the active trip, or null when the server says there is none", async () => {
    const some = setup(() => json(trip));
    expect((await some.api.trips.active())?.id).toBe("t1");
    expect(some.calls[0]!.url).toBe(`${BASE}/api/trips/active`);
    const none = setup(() => new Response(null, { status: 204 }));
    expect(await none.api.trips.active()).toBeNull();
  });

  it("accepts an offer from a 204 with no body", async () => {
    const { api, calls } = setup(() => new Response(null, { status: 204 }));
    await expect(api.rider.acceptOffer("o1")).resolves.toBeUndefined();
    expect(calls[0]!.url).toBe(`${BASE}/api/riders/me/offers/o1/accept`);
    expect(calls[0]!.init.method).toBe("POST");
  });

  it("sends a heading only when it has one", async () => {
    const rider = { id: "r1", name: "Asha", vehicleType: "SEDAN", status: "AVAILABLE", lat: 1, lon: 2 };
    const { api, calls } = setup(() => json(rider));
    await api.rider.updateLocation(1, 2);
    await api.rider.updateLocation(1, 2, 90);
    expect(JSON.parse(String(calls[0]!.init.body))).toEqual({ lat: 1, lon: 2 });
    expect(JSON.parse(String(calls[1]!.init.body))).toEqual({ lat: 1, lon: 2, heading: 90 });
    expect(calls[0]!.init.method).toBe("PUT");
  });

  it("sign-in is sent without credentials and is not retried on 401", async () => {
    const { api, calls } = setup(() => json({ detail: "Invalid credentials" }, 401));
    const err = await api.auth.login({ phone: "+1", password: "x" }).catch((e) => e);
    expect(err.status).toBe(401);
    expect(err.message).toBe("Invalid credentials");
    expect(calls).toHaveLength(1);
    expect((calls[0]!.init.headers as Record<string, string>).Authorization).toBeUndefined();
  });
});

describe("token refresh", () => {
  it("refreshes once on 401, saves the new tokens and repeats the request", async () => {
    let first = true;
    const { api, calls, store } = setup((url) => {
      if (url.endsWith("/api/auth/refresh")) return json(authResult);
      if (first) {
        first = false;
        return json({}, 401);
      }
      return json(trip);
    });
    await api.trips.get("t1");
    expect(calls.map((c) => c.url.replace(BASE, ""))).toEqual(["/api/trips/t1", "/api/auth/refresh", "/api/trips/t1"]);
    expect(JSON.parse(String(calls[1]!.init.body))).toEqual({ refreshToken: "R1" });
    expect((calls[2]!.init.headers as Record<string, string>).Authorization).toBe("Bearer A2");
    expect(await store.load()).toEqual({ accessToken: "A2", refreshToken: "R2" });
  });

  it("shares one refresh between requests that fail together (the server rotates refresh tokens)", async () => {
    let refreshes = 0;
    const { api } = setup((url, init) => {
      if (url.endsWith("/api/auth/refresh")) {
        refreshes++;
        return json(authResult);
      }
      const auth = (init.headers as Record<string, string>).Authorization;
      return auth === "Bearer A2" ? json(trip) : json({}, 401);
    });
    await Promise.all([api.trips.get("t1"), api.trips.get("t2"), api.trips.get("t3")]);
    expect(refreshes).toBe(1);
  });

  it("signs the person out, clearing tokens, when the refresh token is refused", async () => {
    const { api, store, onSignedOut } = setup((url) => (url.endsWith("/api/auth/refresh") ? json({}, 401) : json({}, 401)));
    const err = await api.trips.get("t1").catch((e) => e);
    expect(err.isUnauthorized).toBe(true);
    expect(await store.load()).toBeNull();
    expect(onSignedOut).toHaveBeenCalledOnce();
  });

  it("keeps the session when the refresh failed only because the server could not be reached", async () => {
    const { api, store, onSignedOut } = setup((url) => {
      if (url.endsWith("/api/auth/refresh")) throw new TypeError("offline");
      return json({}, 401);
    });
    const err = await api.trips.get("t1").catch((e) => e);
    expect(err.isNetwork).toBe(true);
    expect(await store.load()).toEqual({ accessToken: "A1", refreshToken: "R1" });
    expect(onSignedOut).not.toHaveBeenCalled();
  });

  it("does not loop: a second 401 after a successful refresh is reported", async () => {
    const { api, calls } = setup((url) => (url.endsWith("/api/auth/refresh") ? json(authResult) : json({}, 401)));
    const err = await api.trips.get("t1").catch((e) => e);
    expect(err.status).toBe(401);
    expect(calls.filter((c) => c.url.endsWith("/trips/t1"))).toHaveLength(2);
  });
});

const b64 = (o: unknown) => btoa(JSON.stringify(o)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
const jwtLeft = (seconds: number) => `${b64({ alg: "HS256" })}.${b64({ exp: Math.floor(Date.now() / 1000) + seconds })}.sig`;

describe("accessToken (for the WebSocket)", () => {
  function auth(accessToken: string, handler: Handler = () => json(authResult)) {
    const calls: string[] = [];
    const fetcher = (async (url: string, init: RequestInit) => (calls.push(url), handler(url, init))) as unknown as typeof fetch;
    const store = memoryTokenStore({ accessToken, refreshToken: "R1" });
    return { auth: bearerAuth({ store, baseUrl: BASE, fetch: fetcher }), calls, store };
  }

  it("returns a token with plenty of life left as it is", async () => {
    const t = jwtLeft(600);
    const { auth: a, calls } = auth(t);
    expect(await a.accessToken()).toBe(t);
    expect(calls).toEqual([]);
  });

  it("renews one that is about to expire, and saves the new pair", async () => {
    const { auth: a, calls, store } = auth(jwtLeft(10));
    expect(await a.accessToken()).toBe("A2");
    expect(calls).toEqual([`${BASE}/api/auth/refresh`]);
    expect(await store.load()).toEqual({ accessToken: "A2", refreshToken: "R2" });
  });

  it("is null when signed out, or when the session cannot be renewed", async () => {
    expect(await bearerAuth({ store: memoryTokenStore(), baseUrl: BASE }).accessToken()).toBeNull();
    const { auth: a } = auth(jwtLeft(-5), () => json({}, 401));
    expect(await a.accessToken()).toBeNull();
  });

  it("sends a token it cannot read as it is, and lets the server decide", async () => {
    const { auth: a, calls } = auth("not-a-jwt");
    expect(await a.accessToken()).toBe("not-a-jwt");
    expect(calls).toEqual([]);
  });
});

describe("web (cookie) auth", () => {
  it("adds no Authorization header and, on 401, reports the sign-out instead of refreshing", async () => {
    const onSignedOut = vi.fn();
    const calls: RequestInit[] = [];
    const fetcher = (async (_url: string, init: RequestInit) => {
      calls.push(init);
      return json({}, 401);
    }) as unknown as typeof fetch;
    const api = createApi({
      baseUrl: "",
      fetch: fetcher,
      auth: cookieAuth({ extraHeaders: { "x-ridehail-web": "1" }, onSignedOut }),
    });
    await api.trips.get("t1").catch(() => undefined);
    expect((calls[0]!.headers as Record<string, string>)["x-ridehail-web"]).toBe("1");
    expect((calls[0]!.headers as Record<string, string>).Authorization).toBeUndefined();
    expect(onSignedOut).toHaveBeenCalledOnce();
    expect(calls).toHaveLength(1);
  });
});

describe("saveSession", () => {
  it("stores the tokens from a sign-in response", async () => {
    const store = memoryTokenStore();
    await saveSession(store, { accessToken: "a", refreshToken: "r", role: "CUSTOMER", profileId: "p" });
    expect(await store.load()).toEqual({ accessToken: "a", refreshToken: "r" });
  });
});
