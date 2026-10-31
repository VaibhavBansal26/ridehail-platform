import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";

// Pushes make these instant, but they are best-effort, so everything also polls slowly: REST is the source of truth.
export const useRider = () => useQuery({ queryKey: ["rider"], queryFn: api.rider.me, refetchInterval: 30_000 });
export const useActiveTrip = () => useQuery({ queryKey: ["trip", "active"], queryFn: api.trips.active, refetchInterval: 15_000 });
export const useOffers = (enabled: boolean) => useQuery({ queryKey: ["offers"], queryFn: api.rider.offers, enabled, refetchInterval: 5_000 });
export const useEarnings = () => useQuery({ queryKey: ["earnings"], queryFn: api.rider.earnings, refetchInterval: 30_000 });
export const usePayouts = () => useQuery({ queryKey: ["payouts"], queryFn: api.rider.payouts, refetchInterval: 30_000 });

/** After anything the rider does, everything it could have changed is fetched again. */
function useRefreshAll() {
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
