"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import type { DocumentType } from "@ridehail/api";
import { api } from "@/lib/api";

export const keys = {
  rider: ["rider"] as const,
  trip: ["trip", "active"] as const,
  offers: ["offers"] as const,
  earnings: ["earnings"] as const,
  payouts: ["payouts"] as const,
  verification: ["verification"] as const,
};

// Pushes make these instant, but they are best-effort, so everything also polls slowly: REST is the source of truth.
export const useRider = () => useQuery({ queryKey: keys.rider, queryFn: api.rider.me, refetchInterval: 30_000 });
export const useActiveTrip = () => useQuery({ queryKey: keys.trip, queryFn: api.trips.active, refetchInterval: 15_000 });
export const useOffers = (enabled: boolean) => useQuery({ queryKey: keys.offers, queryFn: api.rider.offers, enabled, refetchInterval: 5_000 });
export const useEarnings = () => useQuery({ queryKey: keys.earnings, queryFn: api.rider.earnings, refetchInterval: 30_000 });
export const usePayouts = () => useQuery({ queryKey: keys.payouts, queryFn: api.rider.payouts, refetchInterval: 30_000 });

/** After anything the rider does, everything it could have changed is fetched again. */
export function useRefreshAll() {
  const qc = useQueryClient();
  return () => qc.invalidateQueries();
}

export function useGoOnline() {
  const refresh = useRefreshAll();
  return useMutation({ mutationFn: (p: { lat: number; lon: number }) => api.rider.goOnline(p.lat, p.lon), onSettled: refresh });
}

export function useGoOffline() {
  const refresh = useRefreshAll();
  return useMutation({ mutationFn: api.rider.goOffline, onSettled: refresh });
}

export function useOfferAnswer() {
  const refresh = useRefreshAll();
  return useMutation({
    mutationFn: (a: { offerId: string; accept: boolean }) => (a.accept ? api.rider.acceptOffer(a.offerId) : api.rider.declineOffer(a.offerId)),
    // a lost race (another rider took it, or it expired) is an error the screen explains, and the list refreshes either way
    onSettled: refresh,
  });
}

export function useTripStep() {
  const refresh = useRefreshAll();
  return useMutation({
    mutationFn: (a: { tripId: string; step: "arrive" | "start" | "complete" }) => api.trips[a.step](a.tripId),
    onSettled: refresh,
  });
}

export function useRequestPayout() {
  const refresh = useRefreshAll();
  return useMutation({ mutationFn: () => api.rider.requestPayout(), onSettled: refresh });
}

export function useStartOnboarding() {
  return useMutation({ mutationFn: api.rider.startPayoutOnboarding });
}

/** Whether this rider may drive, what is needed and what was sent. Polled slowly: a decision arrives as a notification too. */
export const useVerification = () => useQuery({ queryKey: keys.verification, queryFn: api.rider.verification, refetchInterval: 30_000 });

export function useUploadDocument() {
  const refresh = useRefreshAll();
  return useMutation({
    mutationFn: (v: { type: DocumentType; file: File; expiresOn?: string }) => api.rider.uploadDocument(v.type, v.file, v.expiresOn),
    onSettled: refresh,
  });
}

export function useSubmitVerification() {
  const refresh = useRefreshAll();
  return useMutation({ mutationFn: api.rider.submitVerification, onSettled: refresh });
}
