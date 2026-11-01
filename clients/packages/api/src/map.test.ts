import { describe, expect, it } from "vitest";
import { bearing, metres, pinSvg, vehicleSvg } from "./map";

const origin = { lat: 12.97, lon: 77.59 };

describe("bearing", () => {
  it("points along the compass", () => {
    expect(bearing(origin, { lat: 12.98, lon: 77.59 })).toBeCloseTo(0, 0); // north
    expect(bearing(origin, { lat: 12.97, lon: 77.6 })).toBeCloseTo(90, 0); // east
    expect(bearing(origin, { lat: 12.96, lon: 77.59 })).toBeCloseTo(180, 0); // south
    expect(bearing(origin, { lat: 12.97, lon: 77.58 })).toBeCloseTo(270, 0); // west
  });
});

describe("metres", () => {
  it("is zero for the same point and about 111 km per degree of latitude", () => {
    expect(metres(origin, origin)).toBe(0);
    expect(metres(origin, { lat: 13.97, lon: 77.59 })).toBeGreaterThan(110_000);
    expect(metres(origin, { lat: 13.97, lon: 77.59 })).toBeLessThan(112_000);
  });
});

describe("vehicle and pin pictures", () => {
  it.each(["BIKE", "AUTO", "SEDAN", "SUV"] as const)("draws a %s, turned to its heading", (type) => {
    const svg = vehicleSvg(type, 40, 90);
    expect(svg).toMatch(/^<svg /);
    expect(svg).toContain('transform="rotate(90 20 20)"');
    expect(svg.length).toBeGreaterThan(300);
  });

  it("looks different for each kind of vehicle", () => {
    expect(new Set((["BIKE", "AUTO", "SEDAN", "SUV"] as const).map((t) => vehicleSvg(t))).size).toBe(4);
  });

  it("uses no inline style attributes, which a strict Content-Security-Policy refuses", () => {
    for (const svg of [vehicleSvg("SEDAN"), vehicleSvg("BIKE"), pinSvg("pickup"), pinSvg("drop")]) expect(svg).not.toMatch(/\sstyle=/);
  });
});
