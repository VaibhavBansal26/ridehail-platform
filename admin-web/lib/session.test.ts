import { describe, expect, it } from "vitest";
import { safeNextPath } from "@/lib/session";

describe("safeNextPath (where to go after signing in)", () => {
  it("keeps same-site paths", () => {
    expect(safeNextPath("/trips?page=2")).toBe("/trips?page=2");
    expect(safeNextPath("/")).toBe("/");
  });
  it.each([null, undefined, "", "trips", "//evil.example", "/\\evil.example", "https://evil.example", "javascript:alert(1)"])(
    "refuses %j",
    (value) => expect(safeNextPath(value)).toBe("/"),
  );
});
