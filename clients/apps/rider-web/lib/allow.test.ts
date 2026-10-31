import { describe, expect, it } from "vitest";
import { backendPath } from "@/lib/allow";

const ID = "3f2b8c1e-9d4a-4e7b-8a21-5c6d7e8f9a0b";
const p = (method: string, path: string) => backendPath(method, path.split("/"));

describe("what the rider app may ask the API for", () => {
  it.each([
    ["GET", "api/riders/me"],
    ["POST", "api/riders/me/online"],
    ["POST", "api/riders/me/offline"],
    ["PUT", "api/riders/me/location"],
    ["GET", "api/riders/me/offers"],
    ["POST", `api/riders/me/offers/${ID}/accept`],
    ["POST", `api/riders/me/offers/${ID}/decline`],
    ["GET", "api/riders/me/earnings"],
    ["GET", "api/riders/me/verification"],
    ["PUT", "api/riders/me/verification/documents/DRIVING_LICENCE"],
    ["PUT", "api/riders/me/verification/documents/SELFIE"],
    ["POST", "api/riders/me/verification/submit"],
    ["GET", "api/riders/me/payouts"],
    ["POST", "api/riders/me/payouts"],
    ["POST", "api/riders/me/payout-account/onboarding"],
    ["GET", "api/trips/active"],
    ["GET", `api/trips/${ID}`],
    ["POST", `api/trips/${ID}/arrive`],
    ["POST", `api/trips/${ID}/start`],
    ["POST", `api/trips/${ID}/complete`],
  ])("allows %s /%s", (method, path) => {
    expect(p(method, path)).toBe("/" + path);
  });

  it.each([
    // verification: only the rider's own side, and only the four known documents
    ["PUT", "api/riders/me/verification/documents/PASSPORT"],
    ["GET", "api/riders/me/verification/documents/SELFIE"],
    ["DELETE", "api/riders/me/verification/documents/SELFIE"],
    ["POST", "api/riders/me/verification/approve"],
    ["GET", `api/admin/verifications/${ID}/documents/SELFIE`],
    ["POST", `api/admin/verifications/${ID}/approve`],
    // other roles' endpoints
    ["GET", "api/admin/trips"],
    ["POST", "api/admin/trips/" + ID + "/cancel"],
    ["POST", "api/fares/quote"],
    ["POST", "api/trips"],
    ["POST", `api/trips/${ID}/cancel`],
    ["GET", "api/customers/me"],
    ["POST", "api/customers/me/payment-method/setup"],
    // dev shortcuts and credentials endpoints are not for the browser
    ["PUT", "api/riders/me/payout-account"],
    ["POST", "api/auth/login"],
    ["POST", "api/auth/refresh"],
    ["GET", "actuator/health"],
    // right path, wrong method
    ["GET", "api/riders/me/online"],
    ["POST", "api/riders/me"],
    ["POST", "api/riders/me/location"],
    ["GET", `api/riders/me/offers/${ID}/accept`],
    ["DELETE", "api/riders/me"],
  ])("refuses %s /%s", (method, path) => {
    expect(p(method, path)).toBeNull();
  });

  it("refuses path tricks", () => {
    for (const path of [
      "api/riders/me/../../admin/trips",
      "api/riders/me/%2e%2e/admin",
      "api/riders/me/..",
      "api/riders//me",
      "api/riders/me/offers/not-a-uuid/accept",
      `api/trips/${ID}/arrive/extra`,
      "api/riders/me.json",
      "riders/me", // must come under /api
      "",
    ]) {
      expect(p("GET", path), path).toBeNull();
      expect(p("POST", path), path).toBeNull();
    }
  });
});
