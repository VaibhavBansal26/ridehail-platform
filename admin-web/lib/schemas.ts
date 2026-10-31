import { z } from "zod";

/**
 * Runtime shapes of the admin API. Every response is parsed, so a backend change that breaks the panel shows up as one
 * clear error instead of "undefined" in a table. The same schemas validate the contract fixtures the backend tests keep
 * in sync (fixtures/*.json).
 */
export const TripStatus = z.enum(["REQUESTED", "ACCEPTED", "ARRIVED", "IN_PROGRESS", "COMPLETED", "CANCELLED", "NO_RIDERS_AVAILABLE"]);
export const RiderStatus = z.enum(["OFFLINE", "AVAILABLE", "ON_TRIP"]);
export const VehicleType = z.enum(["BIKE", "AUTO", "SEDAN", "SUV"]);
export const PaymentStatus = z.enum(["PENDING", "CAPTURED", "DISPUTED", "CHARGED_BACK", "REQUIRES_ATTENTION", "REFUNDED"]);
export const PayoutStatus = z.enum(["PENDING", "AWAITING_FUNDS", "PAID", "FAILED", "REQUIRES_ATTENTION"]);
export const OfferStatus = z.enum(["PENDING", "ACCEPTED", "DECLINED", "EXPIRED", "CANCELLED"]);
export const DisputeStatus = z.enum(["INQUIRY", "OPEN", "WON", "LOST", "CLOSED"]);
export const IncidentType = z.enum(["CONNECT_PAYOUT_FAILED", "PLATFORM_PAYOUT_FAILED", "TRANSFER_REVERSED", "REFUND_FAILED"]);
export const IncidentStatus = z.enum(["OPEN", "RESOLVED"]);

const id = z.string().uuid();
const instant = z.string(); // ISO-8601 from the API; formatted at the edges
const money = z.number();

export const page = <T extends z.ZodType>(item: T) =>
  z.object({ items: z.array(item), total: z.number(), page: z.number(), size: z.number() });

export const Trip = z.object({
  id,
  status: TripStatus,
  customerId: id.nullable(),
  customerName: z.string().nullable(),
  riderId: id.nullable(),
  riderName: z.string().nullable(),
  vehicleType: VehicleType,
  fare: money,
  surgeMultiplier: money,
  currency: z.string(),
  offersSent: z.number(),
  pickupLat: z.number(),
  pickupLon: z.number(),
  dropLat: z.number(),
  dropLon: z.number(),
  requestedAt: instant,
  acceptedAt: instant.nullable(),
  startedAt: instant.nullable(),
  completedAt: instant.nullable(),
});
export type Trip = z.infer<typeof Trip>;
export const TripPage = page(Trip);

export const Rider = z.object({
  id,
  name: z.string(),
  vehicleType: VehicleType,
  status: RiderStatus,
  lat: z.number().nullable(),
  lon: z.number().nullable(),
  rating: z.number(),
  locationUpdatedAt: instant.nullable(),
});
export type Rider = z.infer<typeof Rider>;
export const RiderPage = page(Rider);

export const TripDetail = z.object({
  trip: Trip,
  payment: z
    .object({
      id,
      status: PaymentStatus,
      amount: money,
      commission: money,
      riderAmount: money,
      currency: z.string(),
      attempts: z.number(),
      failureReason: z.string().nullable(),
    })
    .nullable(),
  offers: z.array(
    z.object({
      id,
      riderId: id,
      riderName: z.string().nullable(),
      status: OfferStatus,
      riderDistanceKm: z.number(),
      createdAt: instant,
      expiresAt: instant,
      respondedAt: instant.nullable(),
    }),
  ),
  disputes: z.array(
    z.object({
      id,
      status: DisputeStatus,
      amount: money,
      currency: z.string(),
      reason: z.string().nullable(),
      evidenceDueBy: instant.nullable(),
    }),
  ),
});
export type TripDetail = z.infer<typeof TripDetail>;

export const LiveSnapshot = z.object({
  generatedAt: instant,
  riders: z.array(
    z.object({
      id,
      name: z.string(),
      vehicleType: VehicleType,
      status: RiderStatus,
      lat: z.number(),
      lon: z.number(),
      locationUpdatedAt: instant.nullable(),
    }),
  ),
  trips: z.array(
    z.object({
      id,
      status: TripStatus,
      vehicleType: VehicleType,
      customerName: z.string().nullable(),
      riderName: z.string().nullable(),
      riderId: id.nullable(),
      pickupLat: z.number(),
      pickupLon: z.number(),
      dropLat: z.number(),
      dropLon: z.number(),
      requestedAt: instant,
    }),
  ),
  riderCounts: z.record(z.string(), z.number()),
  tripCounts: z.record(z.string(), z.number()),
  truncated: z.boolean(),
});
export type LiveSnapshot = z.infer<typeof LiveSnapshot>;

export const Payment = z.object({
  id,
  tripId: id,
  customerId: id,
  riderId: id.nullable(),
  amount: money,
  commission: money,
  riderAmount: money,
  currency: z.string(),
  status: PaymentStatus,
  attempts: z.number(),
  failureReason: z.string().nullable(),
});
export type Payment = z.infer<typeof Payment>;
export const PaymentPage = page(Payment);

export const Payout = z.object({
  id,
  riderId: id,
  amount: money,
  status: PayoutStatus,
  attempts: z.number(),
  failureReason: z.string().nullable(),
  waitingSince: instant.nullable(),
  createdAt: instant,
});
export type Payout = z.infer<typeof Payout>;
export const PayoutPage = page(Payout);

export const Funding = z.object({
  currency: z.string(),
  providerAvailable: money.nullable(),
  awaitingTotal: money,
  awaitingCount: z.number(),
});

export const Dispute = z.object({
  id,
  providerId: z.string(),
  status: DisputeStatus,
  providerStatus: z.string().nullable(),
  matched: z.boolean(),
  amount: money,
  currency: z.string(),
  reason: z.string().nullable(),
  evidenceDueBy: instant.nullable(),
  evidenceOverdue: z.boolean(),
  paymentId: id.nullable(),
  tripId: id.nullable(),
  customerId: id.nullable(),
  riderId: id.nullable(),
  frozen: z.boolean(),
  riderHeldAmount: money,
  feeBooked: money,
  providerUrl: z.string().nullable(),
  ledgerNote: z.string().nullable(),
  createdAt: instant,
  resolvedAt: instant.nullable(),
});
export type Dispute = z.infer<typeof Dispute>;
export const DisputePage = page(Dispute);

export const Incident = z.object({
  id,
  type: IncidentType,
  providerRef: z.string().nullable(),
  status: IncidentStatus,
  riderId: id.nullable(),
  paymentId: id.nullable(),
  payoutId: id.nullable(),
  amount: money.nullable(),
  currency: z.string().nullable(),
  detail: z.string().nullable(),
  createdAt: instant,
  resolvedAt: instant.nullable(),
  resolutionNote: z.string().nullable(),
});
export type Incident = z.infer<typeof Incident>;
export const IncidentPage = page(Incident);

export const Attention = z.object({
  needsAction: z.boolean(),
  disputes: z.object({
    open: z.number(),
    inquiries: z.number(),
    openAmount: money,
    overdue: z.number(),
    earliestEvidenceDueBy: instant.nullable(),
  }),
  incidents: z.object({ open: z.number(), byType: z.record(z.string(), z.number()) }),
  paymentsRequiringAttention: z.number(),
  payoutsRequiringAttention: z.number(),
  payoutsAwaitingFunds: z.number(),
  riderFundsOnHold: money,
});
export type Attention = z.infer<typeof Attention>;

export const TrialBalance = z.object({
  byType: z.record(z.string(), z.object({ debits: money, credits: money })),
  totalDebits: money,
  totalCredits: money,
  balanced: z.boolean(),
});
export type TrialBalance = z.infer<typeof TrialBalance>;

export const AdminAccount = z.object({
  id,
  phone: z.string(),
  enabled: z.boolean(),
  you: z.boolean(),
  createdAt: instant,
});
export type AdminAccount = z.infer<typeof AdminAccount>;
export const AdminList = z.array(AdminAccount);

export const AdminAction = z.object({
  id,
  actorAccountId: id,
  actorPhone: z.string(),
  action: z.string(),
  targetType: z.string().nullable(),
  targetId: z.string().nullable(),
  detail: z.string().nullable(),
  createdAt: instant,
});
export type AdminAction = z.infer<typeof AdminAction>;
export const AuditPage = page(AdminAction);

// ---------------------------------------------------------------- rider verification

export const VerificationStatus = z.enum(["NOT_SUBMITTED", "PENDING_REVIEW", "APPROVED", "REJECTED", "SUSPENDED", "EXPIRED"]);
export const DocumentType = z.enum(["DRIVING_LICENCE", "VEHICLE_REGISTRATION", "INSURANCE", "SELFIE"]);
export type DocumentType = z.infer<typeof DocumentType>;

export const VerificationQueueItem = z.object({
  riderId: z.string(),
  name: z.string(),
  phone: z.string(),
  vehicleType: z.string(),
  status: VerificationStatus,
  submittedAt: z.string().nullish(),
  documents: z.number(),
});
export type VerificationQueueItem = z.infer<typeof VerificationQueueItem>;
export const VerificationQueue = page(VerificationQueueItem);

export const VerificationDocument = z.object({
  type: DocumentType,
  label: z.string(),
  contentType: z.string(),
  sizeBytes: z.number(),
  uploadedAt: z.string(),
  expiresOn: z.string().nullish(),
});

export const VerificationDetail = z.object({
  riderId: z.string(),
  name: z.string(),
  phone: z.string(),
  vehicleType: z.string(),
  verification: z.object({
    required: z.boolean(),
    status: VerificationStatus,
    note: z.string().nullish(),
    submittedAt: z.string().nullish(),
    reviewedAt: z.string().nullish(),
    requirements: z.array(z.object({ type: DocumentType, label: z.string(), needsExpiry: z.boolean(), imageOnly: z.boolean() })),
    documents: z.array(VerificationDocument),
    missing: z.array(DocumentType),
    canUpload: z.boolean(),
    canSubmit: z.boolean(),
  }),
});
export type VerificationDetail = z.infer<typeof VerificationDetail>;

// ---------------------------------------------------------------- dispute evidence

export const DisputeEvidence = z.object({
  disputeId: z.string(),
  explanation: z.string().nullish(),
  facts: z.object({
    customerName: z.string().nullish(),
    productDescription: z.string().nullish(),
    serviceDate: z.string().nullish(),
    accessActivityLog: z.string().nullish(),
  }),
  submitted: z.boolean(),
  submittedAt: z.string().nullish(),
  lastError: z.string().nullish(),
  canSubmit: z.boolean(),
  cannotSubmitBecause: z.string().nullish(),
  evidenceDueBy: z.string().nullish(),
});
export type DisputeEvidence = z.infer<typeof DisputeEvidence>;
export const EVIDENCE_LIMIT = 5000;
