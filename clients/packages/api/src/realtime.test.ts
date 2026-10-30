import { describe, expect, it } from "vitest";
import { Destinations, streamsFor, toWebSocketUrl } from "./realtime";
import { parseRealtime } from "./schemas";

describe("parseRealtime", () => {
  it("understands an offer pushed to a rider", () => {
    const m = parseRealtime({
      type: "OFFER_RECEIVED",
      tripId: "t1",
      offerId: "o1",
      distanceToPickupKm: 0.8,
      expiresAt: "2026-10-26T14:03:11Z",
      pickupLat: 12.97,
      pickupLon: 77.59,
      dropLat: 12.93,
      dropLon: 77.62,
      fare: 9.5,
      currency: "USD",
      surgeMultiplier: 1,
    });
    expect(m.type).toBe("OFFER_RECEIVED");
  });

  it("understands trip updates, including who is coming", () => {
    const m = parseRealtime({ type: "TRIP_ACCEPTED", tripId: "t1", status: "ACCEPTED", riderId: "r1", fare: 9.5, currency: "USD", riderName: "Asha", vehicleType: "SEDAN", riderLat: 1, riderLon: 2 });
    expect(m).toMatchObject({ type: "TRIP_ACCEPTED", riderName: "Asha" });
  });

  it("understands live location, with or without a heading", () => {
    const base = { type: "RIDER_LOCATION", tripId: "t1", status: "IN_PROGRESS", lat: 1, lon: 2, at: "2026-10-26T14:03:11Z" };
    expect(parseRealtime(base).type).toBe("RIDER_LOCATION");
    expect(parseRealtime({ ...base, heading: 90 })).toMatchObject({ heading: 90 });
  });

  it("understands money notices, which can arrive without a trip", () => {
    expect(parseRealtime({ type: "FUNDS_ON_HOLD" }).type).toBe("FUNDS_ON_HOLD");
    expect(parseRealtime({ type: "EARNING_CREDITED", tripId: "t1", amount: 7.6, currency: "USD" })).toMatchObject({ amount: 7.6 });
  });

  it("never throws: unknown or malformed pushes come back as UNKNOWN", () => {
    for (const raw of [{ type: "SOMETHING_NEW", tripId: "t1" }, { type: "TRIP_STARTED" }, "not json", null, 42, { type: "RIDER_LOCATION", lat: "north" }]) {
      expect(parseRealtime(raw).type).toBe("UNKNOWN");
    }
  });
});

describe("streams", () => {
  it("subscribes riders to offers and trips, customers to trips and location (the server rejects anything else)", () => {
    expect(streamsFor("RIDER")).toEqual([Destinations.offers, Destinations.trips]);
    expect(streamsFor("CUSTOMER")).toEqual([Destinations.trips, Destinations.location]);
  });

  it("builds the WebSocket address from the server address", () => {
    expect(toWebSocketUrl("http://10.0.2.2:8080")).toBe("ws://10.0.2.2:8080/ws");
    expect(toWebSocketUrl("https://api.example.com/")).toBe("wss://api.example.com/ws");
  });
});
