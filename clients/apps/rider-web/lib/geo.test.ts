import { describe, expect, it } from "vitest";
import { bearing, metres } from "@/lib/geo";

const origin = { lat: 12.97, lon: 77.59 };

describe("bearing", () => {
  it("points along the compass", () => {
    expect(bearing(origin, { lat: 12.98, lon: 77.59 })).toBeCloseTo(0, 0);     // north
    expect(bearing(origin, { lat: 12.97, lon: 77.6 })).toBeCloseTo(90, 0);     // east
    expect(bearing(origin, { lat: 12.96, lon: 77.59 })).toBeCloseTo(180, 0);   // south
    expect(bearing(origin, { lat: 12.97, lon: 77.58 })).toBeCloseTo(270, 0);   // west
  });
});

describe("metres", () => {
  it("is zero for the same point and about 111 km per degree of latitude", () => {
    expect(metres(origin, origin)).toBe(0);
    expect(metres(origin, { lat: 13.97, lon: 77.59 })).toBeGreaterThan(110_000);
    expect(metres(origin, { lat: 13.97, lon: 77.59 })).toBeLessThan(112_000);
  });
});
