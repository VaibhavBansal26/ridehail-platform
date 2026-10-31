import { renderHook, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { TripView } from "@/components/trip-view";
import { api } from "@/lib/api";
import { useHeading } from "@/lib/use-heading";
import { renderApp, trip } from "@/test/render";

const maps: Record<string, unknown>[] = [];
vi.mock("@/components/ride-map", () => ({
  RideMap: (props: Record<string, unknown>) => {
    maps.push(props);
    return <div data-testid="map" />;
  },
}));
vi.mock("@/lib/api", () => ({ api: { customer: { me: vi.fn() }, trips: { cancel: vi.fn(), active: vi.fn(), get: vi.fn(), riderLocation: vi.fn() } } }));
const riderLocation = vi.mocked(api.trips.riderLocation);

beforeEach(() => {
  vi.clearAllMocks();
  maps.length = 0;
});

describe("the customer's map", () => {
  it("shows the pickup and drop-off while a rider is being found, with no car yet", async () => {
    await renderApp(<TripView trip={{ ...trip, status: "REQUESTED", riderId: null, riderName: null, riderVehicleType: null }} />);
    expect(screen.getByTestId("map")).toBeInTheDocument();
    expect(maps[maps.length - 1]).toMatchObject({ pickup: { lat: 12.973, lon: 77.595 }, drop: { lat: 12.9352, lon: 77.6245 }, car: null });
  });

  it("puts the rider's vehicle on the map at the position the server reported", async () => {
    riderLocation.mockResolvedValue({ tripId: trip.id, status: "ACCEPTED", lat: 12.976, lon: 77.595, at: "2026-10-30T20:00:00Z" });
    await renderApp(<TripView trip={trip} />);
    await waitFor(() => expect(maps[maps.length - 1]).toMatchObject({ car: { lat: 12.976, lon: 77.595, vehicle: "SEDAN" } }));
  });
});

describe("useHeading", () => {
  const at = (lat: number, lon: number) => ({ lat, lon });

  it("is unknown until the vehicle has moved, then points the way it went", () => {
    const { result, rerender } = renderHook(({ p }) => useHeading(p), { initialProps: { p: at(12.97, 77.59) } });
    expect(result.current).toBeUndefined();
    rerender({ p: at(12.971, 77.59) }); // north
    expect(result.current).toBeCloseTo(0, 0);
    rerender({ p: at(12.971, 77.591) }); // east
    expect(result.current).toBeCloseTo(90, 0);
  });

  it("ignores GPS jitter of a few metres", () => {
    const { result, rerender } = renderHook(({ p }) => useHeading(p), { initialProps: { p: at(12.97, 77.59) } });
    rerender({ p: at(12.970005, 77.59) }); // about 0.5 m
    expect(result.current).toBeUndefined();
  });
});
