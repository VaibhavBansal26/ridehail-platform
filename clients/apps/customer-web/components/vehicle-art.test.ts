import { describe, expect, it } from "vitest";
import { pinSvg, vehicleSvg } from "@/components/vehicle-art";

describe("vehicle and pin pictures", () => {
  it.each(["BIKE", "AUTO", "SEDAN", "SUV"] as const)("draws a %s, turned to its heading", (type) => {
    const svg = vehicleSvg(type, 40, 90);
    expect(svg).toMatch(/^<svg /);
    expect(svg).toContain('transform="rotate(90 20 20)"');
    expect(svg.length).toBeGreaterThan(300);
  });

  it("looks different for each kind of vehicle", () => {
    const all = new Set((["BIKE", "AUTO", "SEDAN", "SUV"] as const).map((t) => vehicleSvg(t)));
    expect(all.size).toBe(4);
  });

  it("uses no inline style attributes, which the apps' Content-Security-Policy would refuse", () => {
    for (const svg of [vehicleSvg("SEDAN"), vehicleSvg("BIKE"), pinSvg("pickup"), pinSvg("drop")]) expect(svg).not.toMatch(/\sstyle=/);
  });
});
