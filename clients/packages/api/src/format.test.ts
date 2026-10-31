import { describe, expect, it } from "vitest";
import { directionsUrl, km, money, secondsUntil, titleCase } from "./format";

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
});
