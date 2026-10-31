import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render } from "@testing-library/react-native";
import type { ReactElement } from "react";

/** Renders with a query client that does not retry or cache between tests. */
export async function renderApp(ui: ReactElement) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 }, mutations: { retry: false } } });
  return await render(<QueryClientProvider client={client}>{ui}</QueryClientProvider>);
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
