import { fireEvent, screen, waitFor } from "@testing-library/react-native";
import { Linking } from "react-native";
import { EarningsView } from "@/components/earnings-view";
import { api } from "@/lib/api";
import { renderApp } from "@/test-utils";

jest.mock("@/lib/api", () => ({ api: { rider: { earnings: jest.fn(), payouts: jest.fn(), requestPayout: jest.fn(), startPayoutOnboarding: jest.fn() } } }));
const rider = api.rider as unknown as Record<string, jest.Mock>;

const earnings = (over: Record<string, unknown> = {}) => ({
  available: 8.12, onHold: 0, payoutsInProgress: 0, currency: "USD", hasPayoutAccount: false, payoutBlockedReason: null, recent: [], ...over,
});

beforeEach(() => {
  jest.clearAllMocks();
  rider.earnings.mockResolvedValue(earnings());
  rider.payouts.mockResolvedValue([]);
});

describe("payout setup", () => {
  it("asks for an email, because the payment provider needs one, before opening its onboarding page", async () => {
    rider.startPayoutOnboarding.mockResolvedValue({ url: "https://connect.stripe.com/setup/abc", expiresAt: "2026-10-30T21:00:00Z" });
    const open = jest.spyOn(Linking, "openURL").mockResolvedValue(undefined);
    await renderApp(<EarningsView />);

    const button = await screen.findByLabelText("Set up payouts");
    await fireEvent.press(button);
    expect(rider.startPayoutOnboarding).not.toHaveBeenCalled();

    await fireEvent.changeText(screen.getByLabelText("Email"), " asha@example.com ");
    await fireEvent.press(screen.getByText("Set up payouts"));
    await waitFor(() => expect(rider.startPayoutOnboarding).toHaveBeenCalledWith("asha@example.com"));
    await waitFor(() => expect(open).toHaveBeenCalledWith("https://connect.stripe.com/setup/abc"));
  });
});
