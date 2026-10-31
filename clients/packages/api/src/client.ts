import { z } from "zod";
import type { AuthStrategy } from "./auth";
import { ApiError, ContractError } from "./errors";
import {
  AuthResult,
  CardCheckout,
  CardSetup,
  Customer,
  Earnings,
  Offer,
  Payout,
  PayoutOnboarding,
  Quote,
  Rider,
  RiderLocation,
  Trip,
  type DocumentType,
  type VehicleType,
  Verification,
} from "./schemas";

export interface ApiOptions {
  /** Server root, e.g. http://10.0.2.2:8080 from an Android emulator, or "" / "/bff" for a web app's same-origin proxy. */
  baseUrl: string;
  auth: AuthStrategy;
  fetch?: typeof fetch;
}

interface Call {
  method: "GET" | "POST" | "PUT";
  path: string;
  body?: unknown;
  /** A file's bytes, sent as they are (not JSON). */
  raw?: Blob | ArrayBuffer | Uint8Array;
  /** Sign-in, registration and refresh are called without credentials and are never retried on 401. */
  anonymous?: boolean;
}

async function errorMessage(res: Response): Promise<string> {
  try {
    const body = (await res.json()) as { detail?: unknown; message?: unknown; title?: unknown };
    for (const v of [body.detail, body.message, body.title]) if (typeof v === "string" && v) return v;
  } catch {
    /* not JSON */
  }
  return res.status >= 500 ? "The server had a problem. Try again." : `Request failed (${res.status})`;
}

export function createApi(opts: ApiOptions) {
  const doFetch = opts.fetch ?? fetch;
  const base = opts.baseUrl.replace(/\/$/, "");

  async function send(call: Call, authenticated: boolean): Promise<Response> {
    const headers: Record<string, string> = { Accept: "application/json" };
    if (call.body !== undefined) headers["Content-Type"] = "application/json";
    if (call.raw !== undefined) headers["Content-Type"] = "application/octet-stream";
    if (authenticated) Object.assign(headers, await opts.auth.headers());
    try {
      return await doFetch(base + call.path, {
        method: call.method,
        headers,
        credentials: "same-origin",
        body: call.raw !== undefined ? (call.raw as BodyInit) : call.body !== undefined ? JSON.stringify(call.body) : undefined,
      });
    } catch {
      throw new ApiError("Cannot reach the server. Check your connection.", 0);
    }
  }

  async function request<T>(call: Call, schema: z.ZodType<T>): Promise<T> {
    let res = await send(call, !call.anonymous);
    if (res.status === 401 && !call.anonymous) {
      if (await opts.auth.refresh()) res = await send(call, true);
    }
    if (!res.ok) {
      const retry = Number(res.headers.get("Retry-After"));
      throw new ApiError(
        res.status === 401 && !call.anonymous ? "Your session has ended. Sign in again." : await errorMessage(res),
        res.status,
        res.status === 429 && Number.isFinite(retry) && retry > 0 ? retry : undefined,
      );
    }
    const json: unknown = res.status === 204 ? undefined : await res.json().catch(() => undefined);
    const parsed = schema.safeParse(json);
    if (!parsed.success) {
      const issue = parsed.error.issues[0];
      throw new ContractError(call.path, `${issue?.path.join(".") || "(body)"}: ${issue?.message ?? "invalid"}`);
    }
    return parsed.data;
  }

  const get = <T>(path: string, schema: z.ZodType<T>) => request({ method: "GET", path }, schema);
  const post = <T>(path: string, schema: z.ZodType<T>, body?: unknown, anonymous = false) =>
    request({ method: "POST", path, body, anonymous }, schema);
  const nothing = z.undefined();

  return {
    auth: {
      registerCustomer: (b: { name: string; phone: string; password: string }) =>
        post("/api/auth/register/customer", AuthResult, b, true),
      registerRider: (b: { name: string; phone: string; password: string; vehicleType: VehicleType }) =>
        post("/api/auth/register/rider", AuthResult, b, true),
      login: (b: { phone: string; password: string }) => post("/api/auth/login", AuthResult, b, true),
    },

    fares: {
      quote: (b: { vehicleType: VehicleType; pickupLat: number; pickupLon: number; dropLat: number; dropLon: number }) =>
        post("/api/fares/quote", Quote, b),
    },

    trips: {
      book: (quoteId: string) => post("/api/trips", Trip, { quoteId }),
      get: (id: string) => get(`/api/trips/${id}`, Trip),
      /** The trip this person is in right now (customer or rider), or null. How an app that was closed finds its place again. */
      active: async () => (await get("/api/trips/active", Trip.optional())) ?? null,
      /** Customer: where the rider is now. 409 if the trip is not active, 404 if the rider has not reported yet. */
      riderLocation: (id: string) => get(`/api/trips/${id}/rider-location`, RiderLocation),
      cancel: (id: string) => post(`/api/trips/${id}/cancel`, Trip),
      // rider-side steps
      arrive: (id: string) => post(`/api/trips/${id}/arrive`, Trip),
      start: (id: string) => post(`/api/trips/${id}/start`, Trip),
      complete: (id: string) => post(`/api/trips/${id}/complete`, Trip),
    },

    /** Phone notifications: tell the server where to reach this phone (idempotent, call on every launch), and stop on sign-out. */
    devices: {
      register: (token: string, platform: "ios" | "android") => request({ method: "PUT", path: "/api/devices", body: { token, platform } }, nothing),
      unregister: (token: string) => post("/api/devices/unregister", nothing, { token }),
    },

    customer: {
      me: () => get("/api/customers/me", Customer),
      /**
       * Saving a card on Stripe's hosted page: open the returned `url` in the system browser. The card is never typed into
       * our app; it reaches the server by webhook, and `me().hasPaymentMethod` turns true.
       */
      startCardCheckout: () => post("/api/customers/me/payment-method/checkout", CardCheckout),
      /** Step 1 of saving a card with the provider's SDK instead: the returned client secret goes to the Stripe SDK, which collects the card. */
      startCardSetup: () => post("/api/customers/me/payment-method/setup", CardSetup),
    },

    rider: {
      me: () => get("/api/riders/me", Rider),
      goOnline: (lat: number, lon: number) => post("/api/riders/me/online", Rider, { lat, lon }),
      goOffline: () => post("/api/riders/me/offline", Rider),
      /**
       * Every 3-5 seconds while online; never faster than 1/s. A 429 carries `retryAfterSeconds`: wait that long
       * and drop the position (it was not stored).
       */
      updateLocation: (lat: number, lon: number, heading?: number) =>
        request({ method: "PUT", path: "/api/riders/me/location", body: { lat, lon, ...(heading !== undefined && { heading }) } }, Rider),
      offers: () => get("/api/riders/me/offers", z.array(Offer)),
      /** 204: fetch the trip with trips.get(offer.tripId) afterwards. */
      acceptOffer: (offerId: string) => post(`/api/riders/me/offers/${offerId}/accept`, nothing),
      declineOffer: (offerId: string) => post(`/api/riders/me/offers/${offerId}/decline`, nothing),
      earnings: () => get("/api/riders/me/earnings", Earnings),
      /** Whether this rider may drive, what is needed, and what was sent. */
      verification: () => get("/api/riders/me/verification", Verification),
      /**
       * Send one document (a JPEG, PNG or, except for the selfie, a PDF: the server checks what the bytes really are). The licence,
       * registration and insurance need `expiresOn` (YYYY-MM-DD). Sending a type again replaces it.
       */
      uploadDocument: (type: DocumentType, file: Blob | ArrayBuffer | Uint8Array, expiresOn?: string) =>
        request({ method: "PUT", path: `/api/riders/me/verification/documents/${type}${expiresOn ? `?expiresOn=${encodeURIComponent(expiresOn)}` : ""}`, raw: file }, Verification),
      submitVerification: () => post("/api/riders/me/verification/submit", Verification),
      payouts: () => get("/api/riders/me/payouts", z.array(Payout)),
      /** Withdraw `amount`, or everything available when omitted. */
      requestPayout: (amount?: number) => post("/api/riders/me/payouts", Payout, amount === undefined ? undefined : { amount }),
      /** Returns a Stripe-hosted page where the rider gives identity and bank details; we never see them. */
      startPayoutOnboarding: () => post("/api/riders/me/payout-account/onboarding", PayoutOnboarding),
    },
  };
}

export type RidehailApi = ReturnType<typeof createApi>;
