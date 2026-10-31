import { act, renderHook } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { type Fix, LocationThrottle, loadManualPosition, type PositionSource, saveManualPosition, useLocationReporter } from "@/lib/position";

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

describe("useLocationReporter", () => {
  beforeEach(() => vi.useFakeTimers({ now: 1_000_000 }));
  afterEach(() => vi.useRealTimers());

  function fakeSource() {
    let onFix: (f: Fix) => void = () => undefined;
    let onProblem: (p: { denied: boolean; message: string }) => void = () => undefined;
    const stop = vi.fn();
    const source: PositionSource = {
      watch: (f, p) => {
        onFix = f;
        onProblem = p;
        return stop;
      },
      current: () => Promise.resolve({ lat: 0, lon: 0 }),
    };
    return { source, fix: (f: Fix) => onFix(f), problem: (denied: boolean) => onProblem({ denied, message: "no gps" }), stop };
  }

  it("does nothing while not enabled", () => {
    const s = fakeSource();
    const send = vi.fn().mockResolvedValue(undefined);
    const { result } = renderHook(() => useLocationReporter({ enabled: false, source: s.source, send }));
    expect(result.current.state).toBe("idle");
    expect(send).not.toHaveBeenCalled();
  });

  it("sends the latest position every few seconds and reports that it is tracking", () => {
    const s = fakeSource();
    const send = vi.fn().mockResolvedValue(undefined);
    const { result } = renderHook(() => useLocationReporter({ enabled: true, source: s.source, send, intervalMs: 4000 }));
    act(() => s.fix({ lat: 1, lon: 1 }));
    act(() => { vi.advanceTimersByTime(1000); s.fix({ lat: 2, lon: 2 }); });
    act(() => { vi.advanceTimersByTime(3000); s.fix({ lat: 3, lon: 3, heading: 90 }); });
    expect(send.mock.calls.map((c) => c[0])).toEqual([{ lat: 1, lon: 1 }, { lat: 3, lon: 3, heading: 90 }]);
    expect(result.current.state).toBe("tracking");
    expect(result.current.last).toEqual({ lat: 3, lon: 3, heading: 90 });
  });

  it("keeps going when a send fails, and honours Retry-After on a 429", async () => {
    const s = fakeSource();
    const send = vi.fn()
      .mockRejectedValueOnce({ isRateLimited: true, retryAfterSeconds: 20 })
      .mockResolvedValue(undefined);
    renderHook(() => useLocationReporter({ enabled: true, source: s.source, send, intervalMs: 1000 }));
    await act(async () => { s.fix({ lat: 1, lon: 1 }); });
    await act(async () => { vi.advanceTimersByTime(5000); s.fix({ lat: 2, lon: 2 }); });   // inside the 20 s the server asked for
    expect(send).toHaveBeenCalledTimes(1);
    await act(async () => { vi.advanceTimersByTime(16_000); s.fix({ lat: 3, lon: 3 }); });
    expect(send).toHaveBeenCalledTimes(2);
  });

  it("says so when the position is blocked or unavailable, and stops watching when disabled", () => {
    const s = fakeSource();
    const { result, rerender } = renderHook(({ enabled }) => useLocationReporter({ enabled, source: s.source, send: vi.fn() }), { initialProps: { enabled: true } });
    act(() => s.problem(true));
    expect(result.current).toMatchObject({ state: "denied", message: "no gps" });
    act(() => s.problem(false));
    expect(result.current.state).toBe("unavailable");
    rerender({ enabled: false });
    expect(s.stop).toHaveBeenCalled();
    expect(result.current.state).toBe("idle");
  });
});

describe("the hand-set position", () => {
  afterEach(() => window.sessionStorage.clear());

  it("is remembered for this tab, and forgotten on request", () => {
    expect(loadManualPosition()).toBeNull();
    saveManualPosition({ lat: 12.5, lon: 77.5 });
    expect(loadManualPosition()).toEqual({ lat: 12.5, lon: 77.5 });
    saveManualPosition(null);
    expect(loadManualPosition()).toBeNull();
  });

  it("ignores junk in storage", () => {
    for (const junk of ["{", "null", '{"lat":"x","lon":1}', "[]"]) {
      window.sessionStorage.setItem("rr_manual_position", junk);
      expect(loadManualPosition(), junk).toBeNull();
    }
  });
});
