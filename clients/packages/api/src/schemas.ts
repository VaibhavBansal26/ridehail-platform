import { z } from "zod";

/**
 * What the customer and rider endpoints return, and what the WebSocket pushes. Every response is parsed with one of
 * these, so if the backend and a client drift apart the app says which field is wrong instead of showing `undefined`.
 * The backend's tests write clients/fixtures/*.json from real responses and fail if their shape changes; this package's
 * tests parse the same files (contract.test.ts).
 */

const money = z.number();
const instant = z.string(); // ISO-8601, e.g. 2026-10-26T14:03:11.482Z

export const Role = z.enum(["CUSTOMER", "RIDER", "ADMIN"]);
export type Role = z.infer<typeof Role>;

export const VehicleType = z.enum(["BIKE", "AUTO", "SEDAN", "SUV"]);
export type VehicleType = z.infer<typeof VehicleType>;

export const TripStatus = z.enum([
  "REQUESTED",
  "ACCEPTED",
  "ARRIVED",
  "IN_PROGRESS",
  "COMPLETED",
  "CANCELLED",
  "NO_RIDERS_AVAILABLE",
]);
export type TripStatus = z.infer<typeof TripStatus>;

/** Statuses in which a trip is still going on (mirrors TripStatus.isActive on the server). */
export const ACTIVE_TRIP_STATUSES: readonly TripStatus[] = ["REQUESTED", "ACCEPTED", "ARRIVED", "IN_PROGRESS"];
export const isActiveTrip = (s: TripStatus): boolean => ACTIVE_TRIP_STATUSES.includes(s);

export const RiderStatus = z.enum(["OFFLINE", "AVAILABLE", "ON_TRIP"]);
export type RiderStatus = z.infer<typeof RiderStatus>;

export const PayoutStatus = z.enum(["PENDING", "AWAITING_FUNDS", "PAID", "FAILED", "REQUIRES_ATTENTION"]);
export type PayoutStatus = z.infer<typeof PayoutStatus>;

// ---------------------------------------------------------------- REST

export const AuthResult = z.object({
  accessToken: z.string(),
  refreshToken: z.string(),
  role: Role,
  profileId: z.string(),
});
export type AuthResult = z.infer<typeof AuthResult>;

export const Rider = z.object({
  id: z.string(),
  name: z.string(),
  vehicleType: VehicleType,
  status: RiderStatus,
  lat: z.number().nullish(),
  lon: z.number().nullish(),
});
export type Rider = z.infer<typeof Rider>;

export const Customer = z.object({
  id: z.string(),
  name: z.string(),
  hasPaymentMethod: z.boolean(),
});
export type Customer = z.infer<typeof Customer>;

export const Quote = z.object({
  quoteId: z.string(),
  vehicleType: VehicleType,
  distanceKm: z.number(),
  durationMin: z.number(),
  subtotal: money,
  surgeMultiplier: money,
  total: money,
  currency: z.string(),
  surgeActive: z.boolean(),
  expiresAt: instant,
});
export type Quote = z.infer<typeof Quote>;

export const Trip = z.object({
  id: z.string(),
  status: TripStatus,
  customerId: z.string(),
  riderId: z.string().nullish(),
  vehicleType: VehicleType,
  fare: money,
  surgeMultiplier: money,
  currency: z.string(),
});
export type Trip = z.infer<typeof Trip>;

export const RiderLocation = z.object({
  tripId: z.string(),
  status: TripStatus,
  lat: z.number(),
  lon: z.number(),
  at: instant,
});
export type RiderLocation = z.infer<typeof RiderLocation>;

export const Offer = z.object({
  offerId: z.string(),
  tripId: z.string(),
  distanceToPickupKm: z.number(),
  expiresAt: instant,
});
export type Offer = z.infer<typeof Offer>;

export const CardSetup = z.object({ clientSecret: z.string(), publishableKey: z.string().nullish() });
export type CardSetup = z.infer<typeof CardSetup>;

export const PayoutOnboarding = z.object({ url: z.string(), expiresAt: instant });
export type PayoutOnboarding = z.infer<typeof PayoutOnboarding>;

export const LedgerLine = z.object({
  at: instant,
  direction: z.enum(["DEBIT", "CREDIT"]),
  amount: money,
  kind: z.string(),
  description: z.string(),
});
export type LedgerLine = z.infer<typeof LedgerLine>;

export const Earnings = z.object({
  available: money,
  onHold: money,
  payoutsInProgress: money,
  currency: z.string(),
  hasPayoutAccount: z.boolean(),
  payoutBlockedReason: z.string().nullish(),
  recent: z.array(LedgerLine),
});
export type Earnings = z.infer<typeof Earnings>;

export const Payout = z.object({
  id: z.string(),
  amount: money,
  currency: z.string(),
  status: PayoutStatus,
  failureReason: z.string().nullish(),
  waitingSince: instant.nullish(),
  createdAt: instant,
  completedAt: instant.nullish(),
});
export type Payout = z.infer<typeof Payout>;

// ---------------------------------------------------------------- WebSocket pushes

const trip = z.string().optional(); // money notices can arrive without a trip

export const OfferReceived = z.object({
  type: z.literal("OFFER_RECEIVED"),
  tripId: z.string(),
  offerId: z.string(),
  distanceToPickupKm: z.number(),
  expiresAt: instant,
  pickupLat: z.number(),
  pickupLon: z.number(),
  dropLat: z.number(),
  dropLon: z.number(),
  fare: money,
  currency: z.string(),
  surgeMultiplier: money,
});

export const OfferGone = z.object({
  type: z.enum(["OFFER_EXPIRED", "OFFER_WITHDRAWN"]),
  tripId: z.string(),
  offerId: z.string(),
});

export const TripUpdate = z.object({
  type: z.enum([
    "TRIP_REQUESTED",
    "TRIP_ACCEPTED",
    "RIDER_ARRIVED",
    "TRIP_STARTED",
    "TRIP_COMPLETED",
    "TRIP_CANCELLED",
    "NO_RIDERS_AVAILABLE",
  ]),
  tripId: z.string(),
  status: TripStatus.optional(),
  riderId: z.string().optional(),
  fare: money.optional(),
  currency: z.string().optional(),
  // only on TRIP_ACCEPTED: who is coming
  riderName: z.string().optional(),
  vehicleType: VehicleType.optional(),
  riderLat: z.number().optional(),
  riderLon: z.number().optional(),
});

/** Money notices carry little detail on purpose; the app refetches earnings or the trip. */
export const MoneyNotice = z.object({
  type: z.enum(["PAYMENT_CAPTURED", "PAYMENT_FAILED", "EARNING_CREDITED", "FUNDS_ON_HOLD", "HOLD_ENDED", "BANK_PAYOUT_FAILED"]),
  tripId: trip,
  amount: money.optional(),
  currency: z.string().optional(),
  reason: z.string().optional(),
});

export const RiderLocationUpdate = z.object({
  type: z.literal("RIDER_LOCATION"),
  tripId: z.string(),
  status: TripStatus,
  lat: z.number(),
  lon: z.number(),
  heading: z.number().optional(),
  at: instant,
});

export type RealtimeMessage =
  | z.infer<typeof OfferReceived>
  | z.infer<typeof OfferGone>
  | z.infer<typeof TripUpdate>
  | z.infer<typeof MoneyNotice>
  | z.infer<typeof RiderLocationUpdate>;

export type UnknownMessage = { type: "UNKNOWN"; raw: unknown };

const realtimeSchemas = [OfferReceived, OfferGone, TripUpdate, MoneyNotice, RiderLocationUpdate] as const;

/**
 * Parse a pushed message. Never throws: a message the app does not understand (a newer server, a malformed frame)
 * comes back as `{ type: "UNKNOWN" }` so one odd push can never crash the app. Pushes are best-effort anyway;
 * REST stays the source of truth.
 */
export function parseRealtime(raw: unknown): RealtimeMessage | UnknownMessage {
  for (const schema of realtimeSchemas) {
    const r = schema.safeParse(raw);
    if (r.success) return r.data;
  }
  return { type: "UNKNOWN", raw };
}
