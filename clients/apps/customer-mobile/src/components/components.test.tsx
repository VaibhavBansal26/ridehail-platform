import { fireEvent, screen, waitFor } from "@testing-library/react-native";
import { Linking } from "react-native";
import { AccountView } from "@/components/account-view";
import { Book } from "@/components/book";
import { Home } from "@/components/home";
import { Receipt } from "@/components/receipt";
import { TripView } from "@/components/trip-view";
import { api } from "@/lib/api";
import { quote, renderApp, trip } from "@/test-utils";

jest.mock("@/lib/api", () => ({
  api: {
    customer: { me: jest.fn(), startCardCheckout: jest.fn() },
    fares: { quote: jest.fn() },
    trips: { book: jest.fn(), cancel: jest.fn(), active: jest.fn(), get: jest.fn(), riderLocation: jest.fn() },
  },
  auth: { accessToken: jest.fn() },
}));
jest.mock("expo-router", () => ({ Link: ({ children, href }: { children: React.ReactNode; href: string }) => { const { Text } = require("react-native"); return <Text accessibilityRole="link" accessibilityHint={href}>{children}</Text>; } }));

const A = api as unknown as {
  customer: Record<"me" | "startCardCheckout", jest.Mock>;
  fares: { quote: jest.Mock };
  trips: Record<"book" | "cancel" | "active" | "get" | "riderLocation", jest.Mock>;
};
const customer = (hasPaymentMethod = true) => ({ id: "c1", name: "Ravi", hasPaymentMethod });

beforeEach(() => {
  jest.clearAllMocks();
  A.customer.me.mockResolvedValue(customer());
});

async function chooseRoute() {
  await fireEvent.press(screen.getByRole("button", { name: "Use this pickup point" }));
  await fireEvent.press(screen.getByRole("button", { name: "See fare" }));
}

describe("Book", () => {
  it("asks the server for the fare of the chosen route and shows what it says", async () => {
    A.fares.quote.mockResolvedValue({ ...quote, total: 10.15, surgeActive: true, surgeMultiplier: 1.5 });
    await renderApp(<Book />);
    await chooseRoute();
    expect(await screen.findByText("$10.15")).toBeTruthy();
    expect(A.fares.quote.mock.calls[0]![0]).toEqual({ vehicleType: "SEDAN", pickupLat: 12.9716, pickupLon: 77.5946, dropLat: 12.9756, dropLon: 77.6068 });
    expect(screen.getByText(/1\.5× busy-time pricing/)).toBeTruthy();
  });

  it("will not ask for a fare without a pickup point", async () => {
    await renderApp(<Book />);
    await fireEvent.press(screen.getByRole("button", { name: "See fare" }));
    expect(await screen.findByText("Set your pickup point first.")).toBeTruthy();
    expect(A.fares.quote).not.toHaveBeenCalled();
  });

  it("requests the ride with the quote it was shown", async () => {
    A.fares.quote.mockResolvedValue(quote);
    A.trips.book.mockResolvedValue({ ...trip, status: "REQUESTED" });
    await renderApp(<Book />);
    await chooseRoute();
    await fireEvent.press(await screen.findByRole("button", { name: "Request ride" }));
    await waitFor(() => expect(A.trips.book.mock.calls[0]![0]).toBe("q1"));
  });

  it("holds the request back, and says why, when there is no card", async () => {
    A.customer.me.mockResolvedValue(customer(false));
    A.fares.quote.mockResolvedValue(quote);
    await renderApp(<Book />);
    await chooseRoute();
    expect(await screen.findByText("Add a card")).toBeTruthy();
    expect(screen.getByRole("button", { name: "Request ride" }).props.accessibilityState.disabled).toBe(true);
  });

  it("shows the server's reason when booking is refused", async () => {
    A.fares.quote.mockResolvedValue(quote);
    A.trips.book.mockRejectedValue(new Error("You have an unpaid trip"));
    await renderApp(<Book />);
    await chooseRoute();
    await fireEvent.press(await screen.findByRole("button", { name: "Request ride" }));
    expect(await screen.findByText("You have an unpaid trip")).toBeTruthy();
  });

  it("does not offer a fare that has expired", async () => {
    A.fares.quote.mockResolvedValue({ ...quote, expiresAt: new Date(Date.now() - 1000).toISOString() });
    await renderApp(<Book />);
    await chooseRoute();
    expect(await screen.findByText(/fare has expired/)).toBeTruthy();
    expect(screen.queryByRole("button", { name: "Request ride" })).toBeNull();
  });
});

describe("TripView", () => {
  it("says it is finding a rider, and lets the customer cancel", async () => {
    A.trips.cancel.mockResolvedValue({ ...trip, status: "CANCELLED" });
    await renderApp(<TripView trip={{ ...trip, status: "REQUESTED", riderId: null, riderName: null, riderVehicleType: null }} />);
    expect(screen.getByText(/Finding you a rider/)).toBeTruthy();
    await fireEvent.press(screen.getByRole("button", { name: "Cancel ride" }));
    await waitFor(() => expect(A.trips.cancel.mock.calls[0]![0]).toBe(trip.id));
  });

  it("shows who is coming and how far away they are from their reported position", async () => {
    A.trips.riderLocation.mockResolvedValue({ tripId: trip.id, status: "ACCEPTED", lat: 12.976, lon: 77.595, at: "2026-10-30T20:00:00Z" });
    await renderApp(<TripView trip={trip} />);
    expect(screen.getByText("Asha is on the way")).toBeTruthy();
    expect(await screen.findByText(/from your pickup point/)).toBeTruthy();
    expect(screen.getByText("Asha · Sedan")).toBeTruthy();
  });

  it("measures to the drop-off once the ride has started, and cannot be cancelled", async () => {
    A.trips.riderLocation.mockResolvedValue({ tripId: trip.id, status: "IN_PROGRESS", lat: 12.9352, lon: 77.62, at: "2026-10-30T20:00:00Z" });
    await renderApp(<TripView trip={{ ...trip, status: "IN_PROGRESS" }} />);
    expect(await screen.findByText(/from your destination/)).toBeTruthy();
    expect(screen.queryByRole("button", { name: "Cancel ride" })).toBeNull();
  });

  it("does not turn a rider who has not reported yet into an error", async () => {
    A.trips.riderLocation.mockRejectedValue(Object.assign(new Error("Not found"), { status: 404 }));
    await renderApp(<TripView trip={trip} />);
    expect(await screen.findByText("Waiting for their position…")).toBeTruthy();
  });
});

describe("Receipt", () => {
  it("shows a paid trip", async () => {
    await renderApp(<Receipt trip={{ ...trip, status: "COMPLETED", paymentStatus: "CAPTURED" }} onDone={jest.fn()} />);
    expect(screen.getByText("$10.15")).toBeTruthy();
    expect(screen.getByText("Paid. Thanks for riding.")).toBeTruthy();
  });

  it("sends the customer to fix their card when the charge failed", async () => {
    await renderApp(<Receipt trip={{ ...trip, status: "COMPLETED", paymentStatus: "REQUIRES_ATTENTION" }} onDone={jest.fn()} />);
    expect(screen.getByText(/could not charge your card/)).toBeTruthy();
    expect(screen.getByRole("link").props.accessibilityHint).toBe("/account");
  });

  it("says a cancelled ride or an unfilled request cost nothing, and goes away when dismissed", async () => {
    const onDone = jest.fn();
    await renderApp(<Receipt trip={{ ...trip, status: "CANCELLED" }} onDone={onDone} />);
    expect(screen.getByText("You have not been charged.")).toBeTruthy();
    await fireEvent.press(screen.getByRole("button", { name: "Done" }));
    expect(onDone).toHaveBeenCalled();
  });
});

describe("Home", () => {
  it("shows the booking form when there is no trip", async () => {
    A.trips.active.mockResolvedValue(null);
    await renderApp(<Home />);
    expect(await screen.findByText("Where to?")).toBeTruthy();
  });

  it("shows the trip that is going on", async () => {
    A.trips.active.mockResolvedValue(trip);
    A.trips.riderLocation.mockResolvedValue(null);
    await renderApp(<Home />);
    expect(await screen.findByText("Asha is on the way")).toBeTruthy();
  });
});

describe("AccountView", () => {
  it("sends a customer without a card to the provider's hosted page in the system browser", async () => {
    A.customer.me.mockResolvedValue(customer(false));
    A.customer.startCardCheckout.mockResolvedValue({ url: "https://checkout.stripe.com/c/pay/cs_1", expiresAt: "2026-10-31T00:00:00Z" });
    const open = jest.spyOn(Linking, "openURL").mockResolvedValue(undefined);
    await renderApp(<AccountView />);
    await fireEvent.press(await screen.findByRole("button", { name: "Add a card" }));
    await waitFor(() => expect(open).toHaveBeenCalledWith("https://checkout.stripe.com/c/pay/cs_1"));
  });

  it("says a card is on file, and offers to replace it", async () => {
    await renderApp(<AccountView />);
    expect(await screen.findByText(/You have a card on file/)).toBeTruthy();
    expect(screen.getByRole("button", { name: "Replace card" })).toBeTruthy();
  });

  it("shows why the provider could not be reached", async () => {
    A.customer.me.mockResolvedValue(customer(false));
    A.customer.startCardCheckout.mockRejectedValue(new Error("Could not start card setup, please try again"));
    await renderApp(<AccountView />);
    await fireEvent.press(await screen.findByRole("button", { name: "Add a card" }));
    expect(await screen.findByText(/Could not start card setup/)).toBeTruthy();
  });
});
