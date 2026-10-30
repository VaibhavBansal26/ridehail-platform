import { randomUUID } from "node:crypto";
import { describe, expect, it } from "vitest";
import { bearerAuth, memoryTokenStore, saveSession } from "./auth";
import { createApi } from "./client";
import { connectRealtime, toWebSocketUrl } from "./realtime";
import type { RealtimeMessage, UnknownMessage } from "./schemas";

/**
 * A whole ride through the shared client against a running backend, over real HTTP and STOMP WebSockets.
 * Skipped unless RIDEHAIL_E2E_URL is set, e.g. start the API (`mvn spring-boot:run`) then
 *   RIDEHAIL_E2E_URL=http://localhost:8080 npm test --workspace @ridehail/api
 */
const URL = process.env.RIDEHAIL_E2E_URL;

type Message = RealtimeMessage | UnknownMessage;

function inbox() {
  const seen: Message[] = [];
  const waiters: { type: string; resolve: (m: Message) => void }[] = [];
  return {
    push(m: Message) {
      seen.push(m);
      for (const w of [...waiters]) {
        if (w.type === m.type) {
          waiters.splice(waiters.indexOf(w), 1);
          w.resolve(m);
        }
      }
    },
    /** Resolves with the next (or an already received) message of this type. */
    next(type: string, ms = 8000): Promise<Message> {
      const have = seen.find((m) => m.type === type);
      if (have) return Promise.resolve(have);
      return new Promise((resolve, reject) => {
        const t = setTimeout(
          () => reject(new Error(`no ${type} within ${ms} ms; got: ${seen.map((m) => m.type).join(", ") || "nothing"}`)),
          ms,
        );
        waiters.push({
          type,
          resolve: (m) => {
            clearTimeout(t);
            resolve(m);
          },
        });
      });
    },
  };
}

describe.skipIf(!URL)("a ride through the shared client, against a running backend", () => {
  it("books, dispatches, accepts, tracks, completes and pays", async () => {
    const base = URL!;
    const anon = createApi({ baseUrl: base, auth: bearerAuth({ store: memoryTokenStore(), baseUrl: base }) });

    const customerStore = memoryTokenStore();
    const riderStore = memoryTokenStore();
    const customer = createApi({ baseUrl: base, auth: bearerAuth({ store: customerStore, baseUrl: base }) });
    const rider = createApi({ baseUrl: base, auth: bearerAuth({ store: riderStore, baseUrl: base }) });

    await saveSession(customerStore, await anon.auth.registerCustomer({ name: "E2E Customer", phone: randomUUID(), password: "password123" }));
    await saveSession(riderStore, await anon.auth.registerRider({ name: "E2E Rider", phone: randomUUID(), password: "password123", vehicleType: "SEDAN" }));

    // the dev/simulated provider lets a client set a card directly; the Stripe provider refuses this on purpose
    const token = (await customerStore.load())!.accessToken;
    const card = await fetch(`${base}/api/customers/me/payment-method`, {
      method: "PUT",
      headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
      body: JSON.stringify({ token: "tok_ok" }),
    });
    expect(card.status).toBe(204);
    expect((await customer.customer.me()).hasPaymentMethod).toBe(true);

    // realtime for both, before anything happens
    const riderMsgs = inbox();
    const customerMsgs = inbox();
    const riderLive = connectRealtime({ url: toWebSocketUrl(base), role: "RIDER", getToken: async () => (await riderStore.load())?.accessToken ?? null, onMessage: (m) => riderMsgs.push(m) });
    const customerLive = connectRealtime({ url: toWebSocketUrl(base), role: "CUSTOMER", getToken: async () => (await customerStore.load())?.accessToken ?? null, onMessage: (m) => customerMsgs.push(m) });

    try {
      await new Promise((r) => setTimeout(r, 800)); // let both subscriptions settle
      await rider.rider.goOnline(12.9766, 77.5946);

      const quote = await customer.fares.quote({ vehicleType: "SEDAN", pickupLat: 12.9716, pickupLon: 77.5946, dropLat: 12.9352, dropLon: 77.6245 });
      const trip = await customer.trips.book(quote.quoteId);
      expect(trip.status).toBe("REQUESTED");

      // the rider is offered the trip, by push and by REST
      const offer = (await riderMsgs.next("OFFER_RECEIVED")) as Extract<RealtimeMessage, { type: "OFFER_RECEIVED" }>;
      expect(offer.tripId).toBe(trip.id);
      expect(offer.fare).toBe(quote.total);
      expect((await rider.rider.offers()).map((o) => o.offerId)).toContain(offer.offerId);

      await rider.rider.acceptOffer(offer.offerId);
      const accepted = (await customerMsgs.next("TRIP_ACCEPTED")) as Extract<RealtimeMessage, { type: "TRIP_UPDATE" | "TRIP_ACCEPTED" }>;
      expect(accepted).toMatchObject({ tripId: trip.id, riderName: "E2E Rider" });

      // live position reaches the customer
      await rider.rider.updateLocation(12.972, 77.595, 90);
      const where = await customerMsgs.next("RIDER_LOCATION");
      expect(where).toMatchObject({ tripId: trip.id, lat: 12.972, lon: 77.595, heading: 90 });
      expect((await customer.trips.riderLocation(trip.id)).lat).toBe(12.972);

      await rider.trips.arrive(trip.id);
      await customerMsgs.next("RIDER_ARRIVED");
      await rider.trips.start(trip.id);
      await customerMsgs.next("TRIP_STARTED");
      expect((await rider.trips.complete(trip.id)).status).toBe("COMPLETED");
      await customerMsgs.next("TRIP_COMPLETED");

      // paid: the customer gets a receipt, the rider an earning
      const receipt = await customerMsgs.next("PAYMENT_CAPTURED");
      expect(receipt).toMatchObject({ amount: quote.total });
      await riderMsgs.next("EARNING_CREDITED");
      const earnings = await rider.rider.earnings();
      expect(earnings.available).toBeGreaterThan(0);
      expect(earnings.recent.length).toBeGreaterThan(0);
    } finally {
      await Promise.all([riderLive.close(), customerLive.close()]);
    }
  }, 40_000);
});
