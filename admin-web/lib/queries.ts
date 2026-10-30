"use client";

import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { get, post, query } from "@/lib/api";
import {
  AdminList,
  Attention,
  AuditPage,
  DisputePage,
  Funding,
  IncidentPage,
  LiveSnapshot,
  PaymentPage,
  PayoutPage,
  RiderPage,
  TrialBalance,
  TripDetail,
  TripPage,
} from "@/lib/schemas";

const PAGE_SIZE = 20;
export { PAGE_SIZE };

export const useAudit = (action: string, page: number) =>
  useQuery({
    queryKey: ["audit", action, page],
    queryFn: () => get("audit" + query({ action, page, size: PAGE_SIZE }), AuditPage),
    placeholderData: keepPreviousData,
    refetchInterval: 30_000,
  });

export const useAdmins = () => useQuery({ queryKey: ["admins"], queryFn: () => get("admins", AdminList) });

export const useAttention = () =>
  useQuery({ queryKey: ["attention"], queryFn: () => get("finance/attention", Attention), refetchInterval: 30_000 });

export const useFunding = () =>
  useQuery({ queryKey: ["funding"], queryFn: () => get("payouts/funding", Funding), refetchInterval: 60_000 });

export const useTrialBalance = () =>
  useQuery({ queryKey: ["trial-balance"], queryFn: () => get("ledger/trial-balance", TrialBalance), refetchInterval: 60_000 });

export const useLive = () =>
  useQuery({ queryKey: ["live"], queryFn: () => get("ops/live", LiveSnapshot), refetchInterval: 5_000 });

export const useTrips = (status: string, page: number) =>
  useQuery({
    queryKey: ["trips", status, page],
    queryFn: () => get("trips" + query({ status, page, size: PAGE_SIZE }), TripPage),
    placeholderData: keepPreviousData,
    refetchInterval: 15_000,
  });

export const useTrip = (id: string) =>
  useQuery({ queryKey: ["trip", id], queryFn: () => get(`trips/${id}`, TripDetail), refetchInterval: 10_000 });

export const useRiders = (status: string, page: number) =>
  useQuery({
    queryKey: ["riders", status, page],
    queryFn: () => get("riders" + query({ status, page, size: PAGE_SIZE }), RiderPage),
    placeholderData: keepPreviousData,
    refetchInterval: 15_000,
  });

export const usePayments = (status: string, page: number) =>
  useQuery({
    queryKey: ["payments", status, page],
    queryFn: () => get("payments" + query({ status, page, size: PAGE_SIZE }), PaymentPage),
    placeholderData: keepPreviousData,
    refetchInterval: 20_000,
  });

export const usePayouts = (status: string, page: number) =>
  useQuery({
    queryKey: ["payouts", status, page],
    queryFn: () => get("payouts" + query({ status, page, size: PAGE_SIZE }), PayoutPage),
    placeholderData: keepPreviousData,
    refetchInterval: 20_000,
  });

export const useDisputes = (status: string, page: number) =>
  useQuery({
    queryKey: ["disputes", status, page],
    queryFn: () => get("disputes" + query({ status, page, size: PAGE_SIZE }), DisputePage),
    placeholderData: keepPreviousData,
    refetchInterval: 30_000,
  });

export const useIncidents = (status: string, type: string, page: number) =>
  useQuery({
    queryKey: ["incidents", status, type, page],
    queryFn: () => get("incidents" + query({ status, type, page, size: PAGE_SIZE }), IncidentPage),
    placeholderData: keepPreviousData,
    refetchInterval: 30_000,
  });

/** After any change, everything that could now be different is refetched. */
function useAction<V>(run: (v: V) => Promise<void>) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: run,
    onSettled: () => qc.invalidateQueries(),
  });
}

export const useCancelTrip = () => useAction((id: string) => post(`trips/${id}/cancel`));
export const useRetryPayment = () => useAction((id: string) => post(`payments/${id}/retry`));
export const useRefundPayment = () => useAction((id: string) => post(`payments/${id}/refund`));
export const useResolveIncident = () =>
  useAction((v: { id: string; note: string }) => post(`incidents/${v.id}/resolve`, { note: v.note }));
export const useCreateAdmin = () => useAction((v: { phone: string; password: string }) => post("admins", v));
export const useSetAdminEnabled = () => useAction((v: { id: string; enabled: boolean }) => post(`admins/${v.id}/${v.enabled ? "enable" : "disable"}`));
