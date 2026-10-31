import { readFileSync, readdirSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import type { z } from "zod";
import { AuthResult, CardCheckout, CardSetup, Customer, Earnings, Offer, Payout, Quote, Rider, RiderLocation, Trip } from "./schemas";

/**
 * The backend's ClientApiContractTest writes these files from real responses and fails if a response changes shape;
 * this test fails if the schemas cannot parse them. Regenerate with `mvn test -Dfixtures.write=true` in the repo root.
 */
const DIR = join(__dirname, "../../../fixtures");
const load = (name: string): unknown => JSON.parse(readFileSync(join(DIR, name), "utf8"));

const cases: [string, z.ZodType][] = [
  ["auth-result.json", AuthResult],
  ["customer-me.json", Customer],
  ["rider-me.json", Rider],
  ["quote.json", Quote],
  ["trip-requested.json", Trip],
  ["trip-accepted.json", Trip],
  ["trip-active.json", Trip],
  ["trip-completed.json", Trip],
  ["rider-location.json", RiderLocation],
  ["earnings.json", Earnings],
  ["payout.json", Payout],
  ["card-setup.json", CardSetup],
  ["card-checkout.json", CardCheckout],
];

describe("contract fixtures", () => {
  it.each(cases)("%s parses", (file, schema) => {
    const result = schema.safeParse(load(file));
    expect(result.success, result.success ? "" : JSON.stringify(result.error.issues)).toBe(true);
  });

  it("offers.json and payouts.json are lists whose items parse", () => {
    const offers = load("offers.json") as unknown[];
    const payouts = load("payouts.json") as unknown[];
    expect(offers.length).toBeGreaterThan(0);
    expect(payouts.length).toBeGreaterThan(0);
    offers.forEach((o) => expect(Offer.safeParse(o).success).toBe(true));
    payouts.forEach((p) => expect(Payout.safeParse(p).success).toBe(true));
  });

  it("every fixture file is covered by a test above", () => {
    const covered = new Set([...cases.map(([f]) => f), "offers.json", "payouts.json"]);
    expect(readdirSync(DIR).filter((f) => f.endsWith(".json") && !covered.has(f))).toEqual([]);
  });
});
