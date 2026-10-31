import { describe, expect, it } from "vitest";
import { directionsUrl, distanceKm, km, money, secondsUntil, titleCase } from "./format";

describe("format", () => {
  it("formats money, falling back for a currency it does not know", () => {
    expect(money(10.15, "USD")).toContain("10.15");
    expect(money(1.5, "NOT-A-CURRENCY")).toBe("1.50 NOT-A-CURRENCY");
  });
  it("formats distances", () => {
    expect(km(0.162)).toBe("162 m");
    expect(km(2.46)).toBe("2.5 km");
  });
  it("counts whole seconds down and never below zero", () => {
    const now = Date.parse("2026-10-30T12:00:00Z");
    expect(secondsUntil("2026-10-30T12:00:10.2Z", now)).toBe(11);
    expect(secondsUntil("2026-10-30T11:59:00Z", now)).toBe(0);
    expect(secondsUntil("garbage", now)).toBe(0);
  });
  it("title-cases statuses and builds a directions link", () => {
    expect(titleCase("AWAITING_FUNDS")).toBe("Awaiting funds");
    expect(directionsUrl(12.9, 77.5)).toContain("destination=12.9,77.5");
  });

  it("measures the distance between two points", () => {
    expect(distanceKm({ lat: 12.9716, lon: 77.5946 }, { lat: 12.9716, lon: 77.5946 })).toBe(0);
    // one degree of latitude is about 111 km
    expect(distanceKm({ lat: 12, lon: 77 }, { lat: 13, lon: 77 })).toBeCloseTo(111.2, 0);
    // the Bengaluru pickup/drop pair used throughout the tests is about 5 km apart
    expect(distanceKm({ lat: 12.9716, lon: 77.5946 }, { lat: 12.9352, lon: 77.6245 })).toBeGreaterThan(4);
  });
});
