import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render } from "@testing-library/react";
import type { ReactElement } from "react";
import { NoticeProvider } from "@/components/notices";

/** Renders with the same providers as the app, and a query client that does not retry or cache between tests. */
export function renderApp(ui: ReactElement) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 }, mutations: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <NoticeProvider>{ui}</NoticeProvider>
    </QueryClientProvider>,
  );
}

export const trip = {
  id: "3f2b8c1e-9d4a-4e7b-8a21-5c6d7e8f9a0b",
  status: "ACCEPTED" as const,
  customerId: "c1",
  riderId: "r1",
  vehicleType: "SEDAN" as const,
  fare: 10.15,
  surgeMultiplier: 1,
  currency: "USD",
  pickupLat: 12.973,
  pickupLon: 77.595,
  dropLat: 12.9352,
  dropLon: 77.6245,
};
