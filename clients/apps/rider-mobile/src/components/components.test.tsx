import { act, fireEvent, screen, waitFor } from "@testing-library/react-native";
import { Linking } from "react-native";
import { OfferCard } from "@/components/offer-card";
import { TripPanel } from "@/components/trip-panel";
import { api } from "@/lib/api";
import { renderApp, trip } from "@/test-utils";

jest.mock("@/lib/api", () => ({
  api: {
    rider: { acceptOffer: jest.fn(), declineOffer: jest.fn(), me: jest.fn(), offers: jest.fn() },
    trips: { arrive: jest.fn(), start: jest.fn(), complete: jest.fn(), active: jest.fn() },
    routes: { between: jest.fn() },
  },
}));
const rider = api.rider as jest.Mocked<typeof api.rider>;
const trips = api.trips as jest.Mocked<typeof api.trips>;

const offer = (secondsLeft: number) => ({
  offerId: "o1", tripId: "t1", distanceToPickupKm: 0.162, expiresAt: new Date(Date.now() + secondsLeft * 1000).toISOString(),
  fare: 10.15, currency: "USD", surgeMultiplier: 1.5, pickupLat: 1, pickupLon: 2, dropLat: 3, dropLon: 4,
});

beforeEach(() => jest.clearAllMocks());
afterEach(() => {
  jest.clearAllTimers();
  jest.useRealTimers();
});

describe("OfferCard", () => {
  it("shows what the rider needs to decide: fare, distance, surge and time left", async () => {
    await renderApp(<OfferCard offer={offer(15)} />);
    expect(screen.getByText("$10.15")).toBeTruthy();
    expect(screen.getByText(/162 m to pickup/)).toBeTruthy();
    expect(screen.getByText(/1\.5× surge/)).toBeTruthy();
    expect(screen.getByLabelText(/15 seconds left/)).toBeTruthy();
  });

  it("accepts and declines through the API", async () => {
    rider.acceptOffer.mockResolvedValue(undefined);
    rider.declineOffer.mockResolvedValue(undefined);
    await renderApp(<OfferCard offer={offer(15)} />);
    await fireEvent.press(screen.getByRole("button", { name: "Accept" }));
    await waitFor(() => expect(rider.acceptOffer).toHaveBeenCalledWith("o1"));
    await fireEvent.press(screen.getByRole("button", { name: "Decline" }));
    await waitFor(() => expect(rider.declineOffer).toHaveBeenCalledWith("o1"));
  });

  it("says why when someone else got there first", async () => {
    rider.acceptOffer.mockRejectedValue(new Error("Offer is no longer available"));
    await renderApp(<OfferCard offer={offer(15)} />);
    await fireEvent.press(screen.getByRole("button", { name: "Accept" }));
    expect(await screen.findByText("Offer is no longer available")).toBeTruthy();
  });

  it("counts down and disappears when time is up", async () => {
    jest.useFakeTimers({ now: Date.now() });
    await renderApp(<OfferCard offer={offer(3)} />);
    expect(screen.getByLabelText(/3 seconds left/)).toBeTruthy();
    await act(async () => { jest.advanceTimersByTime(3500); });
    expect(screen.queryByRole("button", { name: "Accept" })).toBeNull();
  });
});

describe("TripPanel", () => {
  it.each([
    ["ACCEPTED", "Head to the pickup", "I've arrived", "arrive", /destination=12\.973,77\.595/],
    ["ARRIVED", "Waiting for the customer", "Start trip", "start", /destination=12\.973,77\.595/],
    ["IN_PROGRESS", "Drive to the drop-off", "Complete trip", "complete", /destination=12\.9352,77\.6245/],
  ] as const)("%s: shows the next step, navigates to the right place", async (status, title, button, step, destination) => {
    trips[step].mockResolvedValue({ ...trip, status });
    const open = jest.spyOn(Linking, "openURL").mockResolvedValue(undefined);
    await renderApp(<TripPanel trip={{ ...trip, status }} onCompleted={jest.fn()} />);
    expect(screen.getByText(title)).toBeTruthy();
    await fireEvent.press(screen.getByRole("button", { name: /Navigate to/ }));
    expect(open).toHaveBeenCalledWith(expect.stringMatching(destination));
    await fireEvent.press(screen.getByRole("button", { name: button }));
    await waitFor(() => expect(trips[step]).toHaveBeenCalledWith(trip.id));
  });

  it("hands the finished trip to the screen only when it is completed", async () => {
    const onCompleted = jest.fn();
    trips.arrive.mockResolvedValue({ ...trip, status: "ARRIVED" });
    trips.complete.mockResolvedValue({ ...trip, status: "COMPLETED" });
    const first = await renderApp(<TripPanel trip={trip} onCompleted={onCompleted} />);
    await fireEvent.press(screen.getByRole("button", { name: "I've arrived" }));
    await waitFor(() => expect(trips.arrive).toHaveBeenCalled());
    expect(onCompleted).not.toHaveBeenCalled();
    await first.unmount();

    await renderApp(<TripPanel trip={{ ...trip, status: "IN_PROGRESS" }} onCompleted={onCompleted} />);
    await fireEvent.press(screen.getByRole("button", { name: "Complete trip" }));
    await waitFor(() => expect(onCompleted).toHaveBeenCalledWith(expect.objectContaining({ status: "COMPLETED" })));
  });

  it("shows the server's reason when a step is refused", async () => {
    trips.arrive.mockRejectedValue(new Error("Trip was cancelled"));
    await renderApp(<TripPanel trip={trip} onCompleted={jest.fn()} />);
    await fireEvent.press(await screen.findByRole("button", { name: "I've arrived" }));
    expect(await screen.findByText("Trip was cancelled")).toBeTruthy();
  });
});

describe("maps on the rider's screens", () => {
  const webview = jest.requireMock("react-native-webview") as { __injected: string[]; __props: { current: Record<string, (...a: unknown[]) => unknown> } };
  const ready = () => act(async () => webview.__props.current.onMessage!({ nativeEvent: { data: "ready" } }));
  const lastState = () => webview.__injected[webview.__injected.length - 1] ?? "";

  beforeEach(() => {
    webview.__injected.length = 0;
    (api as unknown as { routes: { between: jest.Mock } }).routes.between.mockResolvedValue({ distanceKm: 5, durationMin: 12, points: [[1, 2], [2, 3], [3, 4]] });
  });

  it("shows an offer on its own map, with the pickup, the drop-off and the road", async () => {
    await renderApp(<OfferCard offer={offer(15)} />);
    await ready();
    await waitFor(() => expect(lastState()).toContain('"route":[[1,2],[2,3],[3,4]]'));
    expect(lastState()).toContain('"pickup":{"lat":1,"lon":2}');
    expect(lastState()).toContain('"drop":{"lat":3,"lon":4}');
  });

  it("shows the trip map without a vehicle until the driver's position is known", async () => {
    await renderApp(<TripPanel trip={trip} onCompleted={() => {}} position={null} vehicle="SEDAN" />);
    await ready();
    expect(lastState()).toContain('"car":null');
  });

  it("puts the driver's own vehicle on the trip map once a position is known", async () => {
    await renderApp(<TripPanel trip={trip} onCompleted={() => {}} position={{ lat: 12.971, lon: 77.591, heading: 45 }} vehicle="SEDAN" />);
    await ready();
    expect(lastState()).toContain('"car":{"lat":12.971,"lon":77.591,"heading":45,"vehicle":"SEDAN"}');
  });
});
