import { describe, expect, it } from "vitest";
import { backendPath } from "@/lib/allow";

const ID = "3f2b8c1e-9d4a-4e7b-8a21-5c6d7e8f9a0b";
const p = (method: string, path: string, dev = false) => backendPath(method, path.split("/"), dev);

describe("what the customer app may ask the API for", () => {
  it.each([
    ["GET", "api/customers/me"],
    ["POST", "api/customers/me/payment-method/checkout"],
    ["POST", "api/fares/quote"],
    ["POST", "api/trips"],
    ["GET", "api/support"],
    ["POST", "api/support/messages"],
    ["POST", "api/support/read"],
    ["GET", "api/trips/active"],
    ["GET", `api/trips/${ID}`],
    ["GET", `api/trips/${ID}/rider-location`],
    ["POST", `api/trips/${ID}/cancel`],
  ])("allows %s /%s", (method, path) => {
    expect(p(method, path)).toBe("/" + path);
  });

  it.each([
    // other roles' endpoints
    // support: the person's own side only, never the staff inbox
    ["GET", "api/admin/support"],
    ["GET", `api/admin/support/${ID}`],
    ["POST", `api/admin/support/${ID}/reply`],
    ["POST", `api/admin/support/${ID}/resolve`],
    ["DELETE", "api/support"],
    ["GET", "api/support/messages"],
    ["POST", "api/support"],
    ["GET", "api/admin/trips"],
    ["POST", `api/admin/trips/${ID}/cancel`],
    ["GET", "api/riders/me"],
    ["POST", "api/riders/me/online"],
    ["PUT", "api/riders/me/location"],
    ["POST", `api/trips/${ID}/arrive`],
    ["POST", `api/trips/${ID}/start`],
    ["POST", `api/trips/${ID}/complete`],
    // credentials and webhooks are not for the browser
    ["POST", "api/auth/login"],
    ["POST", "api/webhooks/stripe"],
    ["GET", "actuator/health"],
    // the card shortcut is a development convenience, off unless this deployment says so
    ["PUT", "api/customers/me/payment-method"],
    ["POST", "api/customers/me/payment-method/setup"],
    // right path, wrong method
    ["GET", "api/fares/quote"],
    ["POST", "api/customers/me"],
    ["GET", `api/trips/${ID}/cancel`],
    ["DELETE", `api/trips/${ID}`],
  ])("refuses %s /%s", (method, path) => {
    expect(p(method, path)).toBeNull();
  });

  it("allows the test-card shortcut only when the deployment is a development one", () => {
    expect(p("PUT", "api/customers/me/payment-method", true)).toBe("/api/customers/me/payment-method");
    expect(p("POST", "api/customers/me/payment-method", true)).toBeNull();
    expect(p("PUT", "api/riders/me/payout-account", true)).toBeNull();
  });

  it("refuses path tricks", () => {
    for (const path of [
      "api/customers/me/../../admin/trips",
      "api/customers/me/%2e%2e/admin",
      "api/trips/..",
      "api//trips",
      "api/trips/not-a-uuid/cancel",
      `api/trips/${ID}/cancel/extra`,
      "api/customers/me.json",
      "trips/active", // must come under /api
      "",
    ]) {
      expect(p("GET", path, true), path).toBeNull();
      expect(p("POST", path, true), path).toBeNull();
    }
  });
});
