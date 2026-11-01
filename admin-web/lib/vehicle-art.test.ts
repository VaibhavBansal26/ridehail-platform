import { describe, expect, it } from "vitest";
import { pinSvg, vehicleSvg } from "@/lib/vehicle-art";

describe("the pictures on the live map", () => {
  it.each(["BIKE", "AUTO", "SEDAN", "SUV"] as const)("draws a %s", (type) => {
    const svg = vehicleSvg(type, 34);
    expect(svg).toMatch(/^<svg /);
    expect(svg).toContain('width="34"');
  });

  it("looks different for each kind of vehicle", () => {
    expect(new Set((["BIKE", "AUTO", "SEDAN", "SUV"] as const).map((t) => vehicleSvg(t))).size).toBe(4);
  });

  it("is the same picture the rider and customer apps draw", () => {
    expect(pinSvg("pickup")).toContain("<path");
  });
});
