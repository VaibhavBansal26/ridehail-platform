import { describe, expect, it } from "vitest";
import { deadline, money, relative, safeProviderUrl, shortId, titleCase } from "@/lib/format";

const NOW = Date.parse("2026-10-28T12:00:00Z");
const ago = (ms: number) => new Date(NOW - ms).toISOString();

describe("money", () => {
  it("formats amounts in their currency", () => {
    expect(money(9.99, "USD")).toBe("$9.99");
    expect(money(1234.5)).toBe("$1,234.50");
  });
  it("shows a dash for missing amounts and survives an unknown currency", () => {
    expect(money(null)).toBe("—");
    expect(money(undefined)).toBe("—");
    expect(money(5, "NOPE")).toContain("5.00");
  });
});

describe("relative", () => {
  it("describes the past and the future", () => {
    expect(relative(ago(5 * 60_000), NOW)).toBe("5m ago");
    expect(relative(ago(3 * 3_600_000), NOW)).toBe("3h ago");
    expect(relative(ago(-2 * 86_400_000), NOW)).toBe("in 2d");
  });
  it("calls anything under 45 seconds 'just now'", () => {
    expect(relative(ago(10_000), NOW)).toBe("just now");
    expect(relative(ago(-10_000), NOW)).toBe("in a moment");
  });
  it("tolerates missing or broken input", () => {
    expect(relative(null, NOW)).toBe("—");
    expect(relative("not a date", NOW)).toBe("—");
  });
});

describe("deadline", () => {
  it("flags a missed deadline", () => {
    const d = deadline(ago(26 * 3_600_000), NOW);
    expect(d.overdue).toBe(true);
    expect(d.label).toMatch(/^Overdue by 1d/);
  });
  it("flags one that is close", () => {
    expect(deadline(ago(-24 * 3_600_000), NOW)).toMatchObject({ overdue: false, soon: true });
    expect(deadline(ago(-5 * 86_400_000), NOW)).toMatchObject({ overdue: false, soon: false });
  });
  it("copes with no deadline", () => {
    expect(deadline(null, NOW).label).toBe("No deadline");
  });
});

describe("safeProviderUrl", () => {
  it("allows https links to the provider dashboard only", () => {
    expect(safeProviderUrl("https://dashboard.stripe.com/test/disputes/dp_1")).toBe("https://dashboard.stripe.com/test/disputes/dp_1");
    expect(safeProviderUrl("javascript:alert(1)")).toBeNull();
    expect(safeProviderUrl("http://dashboard.stripe.com/x")).toBeNull();
    expect(safeProviderUrl("https://dashboard.stripe.com.evil.example/x")).toBeNull();
    expect(safeProviderUrl("https://evil.example/dashboard.stripe.com")).toBeNull();
    expect(safeProviderUrl(null)).toBeNull();
  });
});

describe("small helpers", () => {
  it("titleCases enum names", () => expect(titleCase("NO_RIDERS_AVAILABLE")).toBe("No Riders Available"));
  it("shortens ids", () => {
    expect(shortId("b35dc964-1e02-4719-9498-29d0409bc40b")).toBe("b35dc964");
    expect(shortId(null)).toBe("—");
  });
});
