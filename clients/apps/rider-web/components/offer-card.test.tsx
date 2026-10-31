import { act, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { OfferCard } from "@/components/offer-card";
import { api } from "@/lib/api";
import { renderApp } from "@/test/render";

vi.mock("@/lib/api", () => ({
  api: { rider: { acceptOffer: vi.fn(), declineOffer: vi.fn(), me: vi.fn(), offers: vi.fn() }, trips: { active: vi.fn() } },
}));

const offer = (secondsLeft: number) => ({
  offerId: "o1",
  tripId: "t1",
  distanceToPickupKm: 0.162,
  expiresAt: new Date(Date.now() + secondsLeft * 1000).toISOString(),
  fare: 10.15,
  currency: "USD",
  surgeMultiplier: 1.5,
  pickupLat: 1,
  pickupLon: 2,
  dropLat: 3,
  dropLon: 4,
});

beforeEach(() => vi.clearAllMocks());
afterEach(() => vi.useRealTimers());

describe("OfferCard", () => {
  it("shows what the rider needs to decide: fare, distance, surge and time left", () => {
    renderApp(<OfferCard offer={offer(15)} />);
    expect(screen.getByText("$10.15")).toBeInTheDocument();
    expect(screen.getByText(/162 m to pickup/)).toBeInTheDocument();
    expect(screen.getByText(/1\.5× surge/)).toBeInTheDocument();
    expect(screen.getByLabelText(/15 seconds left/)).toBeInTheDocument();
  });

  it("accepts and declines through the API", async () => {
    vi.mocked(api.rider.acceptOffer).mockResolvedValue(undefined);
    vi.mocked(api.rider.declineOffer).mockResolvedValue(undefined);
    const user = userEvent.setup();
    renderApp(<OfferCard offer={offer(15)} />);
    await user.click(screen.getByRole("button", { name: "Accept" }));
    expect(api.rider.acceptOffer).toHaveBeenCalledWith("o1");
    await user.click(screen.getByRole("button", { name: "Decline" }));
    expect(api.rider.declineOffer).toHaveBeenCalledWith("o1");
  });

  it("says why when someone else got there first", async () => {
    vi.mocked(api.rider.acceptOffer).mockRejectedValue(Object.assign(new Error("Offer is no longer available"), { status: 409 }));
    const user = userEvent.setup();
    renderApp(<OfferCard offer={offer(15)} />);
    await user.click(screen.getByRole("button", { name: "Accept" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Offer is no longer available");
  });

  it("counts down, and disappears when time is up", () => {
    vi.useFakeTimers({ now: Date.now() });
    renderApp(<OfferCard offer={offer(3)} />);
    expect(screen.getByLabelText(/3 seconds left/)).toBeInTheDocument();
    act(() => { vi.advanceTimersByTime(2000); });
    expect(screen.getByLabelText(/1 seconds left/)).toBeInTheDocument();
    act(() => { vi.advanceTimersByTime(1500); });
    expect(screen.queryByRole("button", { name: "Accept" })).not.toBeInTheDocument();
  });
});
