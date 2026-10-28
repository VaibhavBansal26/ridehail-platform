import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { z } from "zod";
import { ApiError, get, post, query } from "@/lib/api";
import { CSRF_HEADER, CSRF_VALUE } from "@/lib/session";

const reply = (status: number, body: unknown) => new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });
let fetchMock: ReturnType<typeof vi.fn>;
const assign = vi.fn();

beforeEach(() => {
  fetchMock = vi.fn();
  vi.stubGlobal("fetch", fetchMock);
  vi.stubGlobal("location", { pathname: "/trips", search: "?page=2", assign });
});
afterEach(() => {
  vi.unstubAllGlobals();
  assign.mockClear();
});

describe("get", () => {
  it("returns data that matches the schema", async () => {
    fetchMock.mockResolvedValueOnce(reply(200, { n: 1 }));
    await expect(get("things", z.object({ n: z.number() }))).resolves.toEqual({ n: 1 });
    expect(fetchMock.mock.calls[0][0]).toBe("/api/admin/things");
    expect(fetchMock.mock.calls[0][1].credentials).toBe("same-origin");
  });

  it("names the broken field when the API answers in an unexpected shape", async () => {
    fetchMock.mockResolvedValueOnce(reply(200, { n: "one" }));
    await expect(get("things", z.object({ n: z.number() }))).rejects.toThrow(/shape this panel does not understand \(n\)/);
  });

  it("sends the user to sign in when the session is gone, remembering where they were", async () => {
    fetchMock.mockResolvedValueOnce(reply(401, { detail: "Session expired" }));
    await expect(get("things", z.unknown())).rejects.toMatchObject({ status: 401 });
    expect(assign).toHaveBeenCalledWith("/login?next=%2Ftrips%3Fpage%3D2");
  });

  it("surfaces the API's own message", async () => {
    fetchMock.mockResolvedValueOnce(reply(404, { detail: "Trip not found: 1" }));
    await expect(get("trips/1", z.unknown())).rejects.toThrow("Trip not found: 1");
  });

  it("explains a dead connection", async () => {
    fetchMock.mockRejectedValueOnce(new TypeError("Failed to fetch"));
    const error = (await get("things", z.unknown()).catch((e: unknown) => e)) as ApiError;
    expect(error).toBeInstanceOf(ApiError);
    expect(error.status).toBe(0);
    expect(error.message).toMatch(/Cannot reach/);
  });
});

describe("post", () => {
  it("marks the request as ours and sends JSON", async () => {
    fetchMock.mockResolvedValueOnce(reply(200, {}));
    await post("incidents/1/resolve", { note: "done" });
    const [, init] = fetchMock.mock.calls[0];
    expect(init.method).toBe("POST");
    expect(init.headers[CSRF_HEADER]).toBe(CSRF_VALUE);
    expect(init.body).toBe('{"note":"done"}');
  });

  it("does not put the header on reads", async () => {
    fetchMock.mockResolvedValueOnce(reply(200, {}));
    await get("x", z.unknown());
    expect(fetchMock.mock.calls[0][1].headers[CSRF_HEADER]).toBeUndefined();
  });

  it("turns a conflict into a readable error", async () => {
    fetchMock.mockResolvedValueOnce(reply(409, { detail: "Trip is already completed" }));
    await expect(post("trips/1/cancel")).rejects.toMatchObject({ status: 409, message: "Trip is already completed" });
  });
});

describe("query", () => {
  it("skips empty values and encodes the rest", () => {
    expect(query({ status: "", page: 0, type: undefined, q: "a b&c" })).toBe("?page=0&q=a+b%26c");
    expect(query({})).toBe("");
  });
});
