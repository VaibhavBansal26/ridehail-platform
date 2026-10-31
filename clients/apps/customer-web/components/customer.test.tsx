import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { AccountView } from "@/components/account-view";
import { Book } from "@/components/book";
import { Home } from "@/components/home";
import { Receipt } from "@/components/receipt";
import { TripView } from "@/components/trip-view";
import { api } from "@/lib/api";
import { quote, renderApp, trip } from "@/test/render";

vi.mock("@/lib/api", () => ({
  api: {
    customer: { me: vi.fn(), startCardCheckout: vi.fn() },
    fares: { quote: vi.fn() },
    trips: { book: vi.fn(), cancel: vi.fn(), active: vi.fn(), get: vi.fn(), riderLocation: vi.fn() },
  },
}));
const A = api as unknown as {
  customer: { me: ReturnType<typeof vi.fn>; startCardCheckout: ReturnType<typeof vi.fn> };
  fares: { quote: ReturnType<typeof vi.fn> };
  trips: Record<"book" | "cancel" | "active" | "get" | "riderLocation", ReturnType<typeof vi.fn>>;
};

const customer = (hasPaymentMethod = true) => ({ id: "c1", name: "Ravi", hasPaymentMethod });

beforeEach(() => {
  vi.clearAllMocks();
  A.customer.me.mockResolvedValue(customer());
});

async function chooseRoute(user: ReturnType<typeof userEvent.setup>) {
  await user.click(screen.getByText("Enter pickup coordinates"));
  await user.click(screen.getByRole("button", { name: "Use this pickup point" }));
  await user.click(screen.getByRole("button", { name: "See fare" }));
}

describe("Book", () => {
  it("asks the server for the fare of the chosen route and shows what it says", async () => {
    A.fares.quote.mockResolvedValue({ ...quote, total: 10.15, surgeActive: true, surgeMultiplier: 1.5 });
    const user = userEvent.setup();
    await renderApp(<Book />);
    await chooseRoute(user);
    expect(A.fares.quote.mock.calls[0]![0]).toEqual({ vehicleType: "SEDAN", pickupLat: 12.9716, pickupLon: 77.5946, dropLat: 12.9756, dropLon: 77.6068 });
    expect(await screen.findByText("$10.15")).toBeInTheDocument();
    expect(screen.getByText(/1\.5× busy-time pricing/)).toBeInTheDocument();
  });

  it("will not ask for a fare without a pickup point", async () => {
    const user = userEvent.setup();
    await renderApp(<Book />);
    await user.click(screen.getByRole("button", { name: "See fare" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Set your pickup point first");
    expect(A.fares.quote).not.toHaveBeenCalled();
  });

  it("requests the ride with the quote it was shown", async () => {
    A.fares.quote.mockResolvedValue(quote);
    A.trips.book.mockResolvedValue({ ...trip, status: "REQUESTED" });
    const user = userEvent.setup();
    await renderApp(<Book />);
    await chooseRoute(user);
    await user.click(await screen.findByRole("button", { name: "Request ride" }));
    expect(A.trips.book).toHaveBeenCalledWith("q1");
  });

  it("holds the request back, and says why, when there is no card", async () => {
    A.customer.me.mockResolvedValue(customer(false));
    A.fares.quote.mockResolvedValue(quote);
    const user = userEvent.setup();
    await renderApp(<Book />);
    await chooseRoute(user);
    expect(await screen.findByRole("link", { name: "Add a card" })).toHaveAttribute("href", "/account");
    expect(screen.getByRole("button", { name: "Request ride" })).toBeDisabled();
  });

  it("shows the server's reason when booking is refused", async () => {
    A.fares.quote.mockResolvedValue(quote);
    A.trips.book.mockRejectedValue(new Error("You have an unpaid trip"));
    const user = userEvent.setup();
    await renderApp(<Book />);
    await chooseRoute(user);
    await user.click(await screen.findByRole("button", { name: "Request ride" }));
    expect(await screen.findByText("You have an unpaid trip")).toBeInTheDocument();
  });

  it("does not offer a fare that has expired", async () => {
    A.fares.quote.mockResolvedValue({ ...quote, expiresAt: new Date(Date.now() - 1000).toISOString() });
    const user = userEvent.setup();
    await renderApp(<Book />);
    await chooseRoute(user);
    expect(await screen.findByText(/fare has expired/)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Request ride" })).not.toBeInTheDocument();
  });
});

describe("TripView", () => {
  it("says it is finding a rider, and lets the customer cancel", async () => {
    A.trips.cancel.mockResolvedValue({ ...trip, status: "CANCELLED" });
    const user = userEvent.setup();
    await renderApp(<TripView trip={{ ...trip, status: "REQUESTED", riderId: null, riderName: null, riderVehicleType: null }} />);
    expect(screen.getByRole("heading", { name: /Finding you a rider/ })).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Cancel ride" }));
    expect(A.trips.cancel).toHaveBeenCalledWith(trip.id);
  });

  it("shows who is coming and how far away they are from their reported position", async () => {
    A.trips.riderLocation.mockResolvedValue({ tripId: trip.id, status: "ACCEPTED", lat: 12.976, lon: 77.595, at: "2026-10-30T20:00:00Z" });
    await renderApp(<TripView trip={trip} />);
    expect(screen.getByRole("heading", { name: "Asha is on the way" })).toBeInTheDocument();
    expect(await screen.findByText(/from your pickup point/)).toBeInTheDocument();
    expect(screen.getByText("Asha · Sedan")).toBeInTheDocument();
  });

  it("measures to the drop-off once the ride has started, and cannot be cancelled", async () => {
    A.trips.riderLocation.mockResolvedValue({ tripId: trip.id, status: "IN_PROGRESS", lat: 12.9352, lon: 77.62, at: "2026-10-30T20:00:00Z" });
    await renderApp(<TripView trip={{ ...trip, status: "IN_PROGRESS" }} />);
    expect(await screen.findByText(/from your destination/)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Cancel ride" })).not.toBeInTheDocument();
  });

  it("says so when the rider has arrived", async () => {
    A.trips.riderLocation.mockResolvedValue(null);
    await renderApp(<TripView trip={{ ...trip, status: "ARRIVED" }} />);
    expect(screen.getByRole("heading", { name: "Asha has arrived" })).toBeInTheDocument();
  });

  it("does not turn a rider who has not reported yet into an error", async () => {
    A.trips.riderLocation.mockRejectedValue(Object.assign(new Error("Not found"), { status: 404 }));
    await renderApp(<TripView trip={trip} />);
    expect(await screen.findByText("Waiting for their position…")).toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });
});

describe("Receipt", () => {
  it("shows a paid trip", async () => {
    await renderApp(<Receipt trip={{ ...trip, status: "COMPLETED", paymentStatus: "CAPTURED" }} onDone={vi.fn()} />);
    expect(screen.getByText("$10.15")).toBeInTheDocument();
    expect(screen.getByText("Paid. Thanks for riding.")).toBeInTheDocument();
  });

  it("sends the customer to fix their card when the charge failed", async () => {
    await renderApp(<Receipt trip={{ ...trip, status: "COMPLETED", paymentStatus: "REQUIRES_ATTENTION" }} onDone={vi.fn()} />);
    expect(screen.getByText(/could not charge your card/)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Update card" })).toHaveAttribute("href", "/account");
  });

  it("says a cancelled ride or an unfilled request cost nothing", async () => {
    const first = await renderApp(<Receipt trip={{ ...trip, status: "CANCELLED" }} onDone={vi.fn()} />);
    expect(screen.getByRole("heading", { name: "Ride cancelled" })).toBeInTheDocument();
    expect(screen.getByText("You have not been charged.")).toBeInTheDocument();
    first.unmount();
    await renderApp(<Receipt trip={{ ...trip, status: "NO_RIDERS_AVAILABLE" }} onDone={vi.fn()} />);
    expect(screen.getByRole("heading", { name: "No riders available" })).toBeInTheDocument();
  });

  it("goes away when dismissed", async () => {
    const onDone = vi.fn();
    const user = userEvent.setup();
    await renderApp(<Receipt trip={{ ...trip, status: "COMPLETED", paymentStatus: "CAPTURED" }} onDone={onDone} />);
    await user.click(screen.getByRole("button", { name: "Done" }));
    expect(onDone).toHaveBeenCalled();
  });
});

describe("Home", () => {
  it("shows the booking form when there is no trip", async () => {
    A.trips.active.mockResolvedValue(null);
    await renderApp(<Home />);
    expect(await screen.findByText("Where to?")).toBeInTheDocument();
  });

  it("shows the trip that is going on", async () => {
    A.trips.active.mockResolvedValue(trip);
    A.trips.riderLocation.mockResolvedValue(null);
    await renderApp(<Home />);
    expect(await screen.findByRole("heading", { name: "Asha is on the way" })).toBeInTheDocument();
  });
});

describe("AccountView", () => {
  it("sends a customer without a card to the provider's hosted page, not to a form of ours", async () => {
    A.customer.me.mockResolvedValue(customer(false));
    A.customer.startCardCheckout.mockResolvedValue({ url: "https://checkout.stripe.com/c/pay/cs_1", expiresAt: "2026-10-31T00:00:00Z" });
    const assign = vi.fn();
    vi.stubGlobal("location", { ...window.location, assign });
    const user = userEvent.setup();
    await renderApp(<AccountView />);
    await user.click(await screen.findByRole("button", { name: "Add a card" }));
    await waitFor(() => expect(assign).toHaveBeenCalledWith("https://checkout.stripe.com/c/pay/cs_1"));
    vi.unstubAllGlobals();
  });

  it("says a card is on file, and offers to replace it", async () => {
    await renderApp(<AccountView />);
    expect(await screen.findByText(/You have a card on file/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Replace card" })).toBeInTheDocument();
  });

  it("shows why the provider could not be reached", async () => {
    A.customer.me.mockResolvedValue(customer(false));
    A.customer.startCardCheckout.mockRejectedValue(new Error("Could not start card setup, please try again"));
    const user = userEvent.setup();
    await renderApp(<AccountView />);
    await user.click(await screen.findByRole("button", { name: "Add a card" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Could not start card setup");
  });
});
