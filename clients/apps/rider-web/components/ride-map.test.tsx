import { screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { OfferCard } from "@/components/offer-card";
import { TripPanel } from "@/components/trip-panel";
import { renderApp } from "@/test/render";

const maps: Record<string, unknown>[] = [];
vi.mock("@/components/ride-map", () => ({
  RideMap: (props: Record<string, unknown>) => {
    maps.push(props);
    return <div data-testid="map" />;
  },
}));
vi.mock("@/lib/api", () => ({ api: { rider: { acceptOffer: vi.fn(), declineOffer: vi.fn(), me: vi.fn(), offers: vi.fn() }, trips: { active: vi.fn(), step: vi.fn() } } }));

const offer = {
  offerId: "o1", tripId: "t1", distanceToPickupKm: 0.5, expiresAt: new Date(Date.now() + 60_000).toISOString(), fare: 10, currency: "USD",
  surgeMultiplier: 1, pickupLat: 12.97, pickupLon: 77.59, dropLat: 12.93, dropLon: 77.62,
};
const trip = {
  id: "t1", status: "ACCEPTED" as const, customerId: "c1", riderId: "r1", vehicleType: "SEDAN" as const, fare: 10, surgeMultiplier: 1, currency: "USD",
  pickupLat: 12.97, pickupLon: 77.59, dropLat: 12.93, dropLon: 77.62, riderName: "Asha", riderVehicleType: "SEDAN" as const, paymentStatus: null,
};

describe("maps on the rider's screens", () => {
  it("shows every offer on its own map, with the pickup and the drop-off, so several requests can be told apart", () => {
    maps.length = 0;
    renderApp(
      <>
        <OfferCard offer={offer} />
        <OfferCard offer={{ ...offer, offerId: "o2", pickupLat: 12.95, pickupLon: 77.6 }} />
      </>,
    );
    expect(screen.getAllByTestId("map")).toHaveLength(2);
    expect(maps[0]).toMatchObject({ pickup: { lat: 12.97, lon: 77.59 }, drop: { lat: 12.93, lon: 77.62 } });
    expect(maps[maps.length - 1]).toMatchObject({ pickup: { lat: 12.95, lon: 77.6 } });
  });

  it("puts the rider's own vehicle on the trip map once a position is known, and not before", () => {
    maps.length = 0;
    const first = renderApp(<TripPanel trip={trip} onCompleted={() => {}} position={null} vehicle="SEDAN" />);
    expect(maps[maps.length - 1]).toMatchObject({ pickup: { lat: 12.97, lon: 77.59 }, drop: { lat: 12.93, lon: 77.62 }, car: null });
    first.unmount();
    renderApp(<TripPanel trip={trip} onCompleted={() => {}} position={{ lat: 12.971, lon: 77.591, heading: 45 }} vehicle="SEDAN" />);
    expect(maps[maps.length - 1]).toMatchObject({ car: { lat: 12.971, lon: 77.591, heading: 45, vehicle: "SEDAN" } });
  });
});
