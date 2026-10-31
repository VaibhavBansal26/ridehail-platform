import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { EarningsView } from "@/components/earnings-view";
import { api } from "@/lib/api";
import { renderApp } from "@/test/render";

vi.mock("@/lib/api", () => ({
  api: { rider: { earnings: vi.fn(), payouts: vi.fn(), requestPayout: vi.fn(), startPayoutOnboarding: vi.fn() } },
}));

const earnings = (over: object = {}) => ({
  available: 8.12, onHold: 0, payoutsInProgress: 0, currency: "USD", hasPayoutAccount: true, payoutBlockedReason: null,
  recent: [{ at: "2026-10-30T20:13:00Z", direction: "CREDIT" as const, amount: 8.12, kind: "PAYMENT_CAPTURE", description: "Trip abc" }],
  ...over,
});
const payout = (status: "PAID" | "AWAITING_FUNDS") => ({
  id: "p1", amount: 8.12, currency: "USD", status, failureReason: null, waitingSince: null, createdAt: "2026-10-30T20:14:00Z", completedAt: null,
});

beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(api.rider.payouts).mockResolvedValue([]);
});

describe("EarningsView", () => {
  it("shows the balances and recent activity from the server's numbers", async () => {
    vi.mocked(api.rider.earnings).mockResolvedValue(earnings({ onHold: 2.5, payoutsInProgress: 1 }));
    renderApp(<EarningsView />);
    expect(await screen.findAllByText("$8.12")).not.toHaveLength(0);
    expect(screen.getByText("$2.50")).toBeInTheDocument();
    expect(screen.getByText(/waiting for a payment dispute/i)).toBeInTheDocument();
    expect(screen.getByText("Trip abc")).toBeInTheDocument();
  });

  it("withdraws everything available and explains what happens next", async () => {
    vi.mocked(api.rider.earnings).mockResolvedValue(earnings());
    vi.mocked(api.rider.requestPayout).mockResolvedValue(payout("AWAITING_FUNDS"));
    const user = userEvent.setup();
    renderApp(<EarningsView />);
    await user.click(await screen.findByRole("button", { name: "Withdraw $8.12" }));
    expect(api.rider.requestPayout).toHaveBeenCalledOnce();
    expect(await screen.findByRole("status")).toHaveTextContent(/waiting for card payments to settle/i);
  });

  it("cannot withdraw nothing, or while the bank is rejecting payouts", async () => {
    vi.mocked(api.rider.earnings).mockResolvedValue(earnings({ available: 0 }));
    const first = renderApp(<EarningsView />);
    expect(await screen.findByRole("button", { name: /Withdraw/ })).toBeDisabled();
    first.unmount();

    vi.mocked(api.rider.earnings).mockResolvedValue(earnings({ payoutBlockedReason: "Your bank rejected the last payout." }));
    renderApp(<EarningsView />);
    expect(await screen.findByRole("alert")).toHaveTextContent("Your bank rejected the last payout.");
    expect(screen.getByRole("button", { name: /Withdraw/ })).toBeDisabled();
  });

  it("sends a rider with no payout account to the provider's onboarding, not to a form of ours", async () => {
    vi.mocked(api.rider.earnings).mockResolvedValue(earnings({ hasPayoutAccount: false }));
    vi.mocked(api.rider.startPayoutOnboarding).mockResolvedValue({ url: "https://connect.stripe.com/setup/abc", expiresAt: "2026-10-30T21:00:00Z" });
    const assign = vi.fn();
    vi.stubGlobal("location", { ...window.location, assign });
    const user = userEvent.setup();
    renderApp(<EarningsView />);
    await user.click(await screen.findByRole("button", { name: "Set up payouts" }));
    expect(assign).toHaveBeenCalledWith("https://connect.stripe.com/setup/abc");
    expect(screen.queryByRole("button", { name: /Withdraw/ })).not.toBeInTheDocument();
    vi.unstubAllGlobals();
  });

  it("lists past withdrawals with their status", async () => {
    vi.mocked(api.rider.earnings).mockResolvedValue(earnings());
    vi.mocked(api.rider.payouts).mockResolvedValue([payout("PAID")]);
    renderApp(<EarningsView />);
    expect(await screen.findByText("Paid")).toBeInTheDocument();
  });
});
