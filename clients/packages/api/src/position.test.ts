import { describe, expect, it, vi } from "vitest";
import { type Fix, LocationThrottle, type PositionSource, startLocationReporting } from "./position";

describe("LocationThrottle", () => {
  it("sends the first position, then at most one per interval, always the latest", () => {
    const t = new LocationThrottle(4000);
    expect(t.take({ lat: 1, lon: 1 }, 0)).toEqual({ lat: 1, lon: 1 });
    expect(t.take({ lat: 2, lon: 2 }, 1000)).toBeNull();
    expect(t.take({ lat: 3, lon: 3 }, 3999)).toBeNull();
    expect(t.take({ lat: 4, lon: 4 }, 4000)).toEqual({ lat: 4, lon: 4 });
  });

  it("stays quiet for as long as the server asked after a 429", () => {
    const t = new LocationThrottle(1000);
    t.take({ lat: 1, lon: 1 }, 0);
    t.backOff(10, 500);
    expect(t.take({ lat: 2, lon: 2 }, 5000)).toBeNull();
    expect(t.take({ lat: 3, lon: 3 }, 10_499)).toBeNull();
    expect(t.take({ lat: 4, lon: 4 }, 10_500)).toEqual({ lat: 4, lon: 4 });
  });
});

describe("startLocationReporting", () => {
  function fake() {
    let onFix: (f: Fix) => void = () => undefined;
    let onProblem: (p: { denied: boolean; message: string }) => void = () => undefined;
    const stop = vi.fn();
    const source: PositionSource = { watch: (f, p) => ((onFix = f), (onProblem = p), stop), current: async () => ({ lat: 0, lon: 0 }) };
    return { source, fix: (f: Fix) => onFix(f), problem: (denied: boolean) => onProblem({ denied, message: "no gps" }), stop };
  }

  it("sends the latest position every interval and reports tracking", () => {
    const s = fake();
    let t = 0;
    const send = vi.fn().mockResolvedValue(undefined);
    const states: string[] = [];
    startLocationReporting({ source: s.source, send, intervalMs: 4000, now: () => t, onState: (st) => states.push(st) });
    s.fix({ lat: 1, lon: 1 });
    t = 1000; s.fix({ lat: 2, lon: 2 });
    t = 4000; s.fix({ lat: 3, lon: 3, heading: 90 });
    expect(send.mock.calls.map((c) => c[0])).toEqual([{ lat: 1, lon: 1 }, { lat: 3, lon: 3, heading: 90 }]);
    expect(states[0]).toBe("tracking");
  });

  it("drops a failed send, and honours Retry-After on a 429", async () => {
    const s = fake();
    let t = 0;
    const send = vi.fn().mockRejectedValueOnce({ isRateLimited: true, retryAfterSeconds: 20 }).mockResolvedValue(undefined);
    startLocationReporting({ source: s.source, send, intervalMs: 1000, now: () => t, onState: () => undefined });
    s.fix({ lat: 1, lon: 1 });
    await Promise.resolve();
    t = 5000; s.fix({ lat: 2, lon: 2 });
    expect(send).toHaveBeenCalledTimes(1);
    t = 21_000; s.fix({ lat: 3, lon: 3 });
    expect(send).toHaveBeenCalledTimes(2);
  });

  it("reports a blocked or unavailable position, and stop() stops watching", () => {
    const s = fake();
    const states: [string, string | null][] = [];
    const stop = startLocationReporting({ source: s.source, send: vi.fn(), onState: (st, m) => states.push([st, m]) });
    s.problem(true);
    s.problem(false);
    stop();
    expect(states).toEqual([["denied", "no gps"], ["unavailable", "no gps"], ["idle", null]]);
    expect(s.stop).toHaveBeenCalledOnce();
  });
});
