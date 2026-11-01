import type { LatLon, RiderLocation } from "@ridehail/api";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";

export const keys = { customer: ["customer"] as const, trip: ["trip", "active"] as const, riderLocation: (id: string) => ["riderLocation", id] as const };

// Pushes make these instant, but they are best-effort, so everything also polls: REST is the source of truth.
export const useCustomer = () => useQuery({ queryKey: keys.customer, queryFn: api.customer.me, refetchInterval: 30_000 });
export const useActiveTrip = () => useQuery({ queryKey: keys.trip, queryFn: api.trips.active, refetchInterval: 10_000 });
export const useTrip = (id: string | null, enabled: boolean) =>
  useQuery({ queryKey: ["trip", id], queryFn: () => api.trips.get(id!), enabled: enabled && !!id, refetchInterval: 5_000 });

/** Where the rider is: pushed every couple of seconds while the trip runs, and read once over REST for a freshly opened app. */
export const useRiderLocation = (tripId: string | null, enabled: boolean) =>
  useQuery<RiderLocation | null>({
    queryKey: keys.riderLocation(tripId ?? ""),
    // 404: the rider has not reported a position yet. That is "unknown", not an error to show.
    queryFn: () => api.trips.riderLocation(tripId!).catch((e: { status?: number }) => (e?.status === 404 || e?.status === 409 ? null : Promise.reject(e))),
    enabled: enabled && !!tripId,
    refetchInterval: 15_000,
  });

function useRefreshAll() {
  const qc = useQueryClient();
  return () => qc.invalidateQueries();
}

/** Address search, run when the person asks (not on every keystroke: the search services forbid autocomplete traffic). */
export const usePlaceSearch = () => useMutation({ mutationFn: ({ q, near }: { q: string; near?: LatLon }) => api.places.search(q, near) });

/** The road between two points, for drawing. Nothing while it loads or if the routing service is down: the map then draws a straight line. */
export function useRoad(from: LatLon | null | undefined, to: LatLon | null | undefined) {
  const r = (n: number) => Math.round(n * 1e4) / 1e4; // about 11 m: nearby requests share one answer
  return useQuery({
    queryKey: ["road", from && r(from.lat), from && r(from.lon), to && r(to.lat), to && r(to.lon)],
    queryFn: () => api.routes.between(from!, to!),
    enabled: !!from && !!to,
    staleTime: 30 * 60_000,
    retry: false,
    select: (route) => route.points,
  });
}

export const useQuote = () => useMutation({ mutationFn: api.fares.quote });

export function useBook() {
  const refresh = useRefreshAll();
  return useMutation({ mutationFn: (quoteId: string) => api.trips.book(quoteId), onSettled: refresh });
}

export function useCancel() {
  const refresh = useRefreshAll();
  return useMutation({ mutationFn: (tripId: string) => api.trips.cancel(tripId), onSettled: refresh });
}

export const useCardCheckout = () => useMutation({ mutationFn: api.customer.startCardCheckout });

export const useSupport = () => useQuery({ queryKey: ["support"], queryFn: api.support.conversation, refetchInterval: 15_000 });

export function useSendSupport() {
  const qc = useQueryClient();
  return useMutation({ mutationFn: (body: string) => api.support.send(body), onSuccess: (conversation) => qc.setQueryData(["support"], conversation) });
}

export function useMarkSupportRead() {
  const qc = useQueryClient();
  return useMutation({ mutationFn: api.support.markRead, onSuccess: () => qc.invalidateQueries({ queryKey: ["support"] }) });
}
