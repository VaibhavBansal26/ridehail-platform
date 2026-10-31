import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { TripPanel } from "@/components/trip-panel";
import { api } from "@/lib/api";
import { renderApp, trip } from "@/test/render";

vi.mock("@/lib/api", () => ({
  api: { trips: { arrive: vi.fn(), start: vi.fn(), complete: vi.fn(), active: vi.fn() }, rider: { me: vi.fn() } },
}));

beforeEach(() => vi.clearAllMocks());

describe("TripPanel", () => {
  it.each([
    ["ACCEPTED", "Head to the pickup", "I've arrived", "arrive", /destination=12\.973,77\.595/],
    ["ARRIVED", "Waiting for the customer", "Start trip", "start", /destination=12\.973,77\.595/],
    ["IN_PROGRESS", "Drive to the drop-off", "Complete trip", "complete", /destination=12\.9352,77\.6245/],
  ] as const)("%s: shows the next step and navigates to the right place", async (status, title, button, step, destination) => {
    vi.mocked(api.trips[step]).mockResolvedValue({ ...trip, status });
    const user = userEvent.setup();
    renderApp(<TripPanel trip={{ ...trip, status }} onCompleted={vi.fn()} />);
    expect(screen.getByRole("heading", { name: title })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /Navigate to/ })).toHaveAttribute("href", expect.stringMatching(destination));
    await user.click(screen.getByRole("button", { name: button }));
    expect(api.trips[step]).toHaveBeenCalledWith(trip.id);
  });

  it("hands the finished trip to the screen only when it is completed", async () => {
    const onCompleted = vi.fn();
    vi.mocked(api.trips.arrive).mockResolvedValue({ ...trip, status: "ARRIVED" });
    vi.mocked(api.trips.complete).mockResolvedValue({ ...trip, status: "COMPLETED" });
    const user = userEvent.setup();

    const first = renderApp(<TripPanel trip={trip} onCompleted={onCompleted} />);
    await user.click(screen.getByRole("button", { name: "I've arrived" }));
    expect(onCompleted).not.toHaveBeenCalled();
    first.unmount();

    renderApp(<TripPanel trip={{ ...trip, status: "IN_PROGRESS" }} onCompleted={onCompleted} />);
    await user.click(screen.getByRole("button", { name: "Complete trip" }));
    expect(onCompleted).toHaveBeenCalledWith(expect.objectContaining({ status: "COMPLETED" }));
  });

  it("shows the server's reason when a step is refused", async () => {
    vi.mocked(api.trips.arrive).mockRejectedValue(new Error("Trip was cancelled"));
    const user = userEvent.setup();
    renderApp(<TripPanel trip={trip} onCompleted={vi.fn()} />);
    await user.click(screen.getByRole("button", { name: "I've arrived" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Trip was cancelled");
  });

  it("renders nothing for a trip that is not on the road", () => {
    const { container } = renderApp(<TripPanel trip={{ ...trip, status: "COMPLETED" }} onCompleted={vi.fn()} />);
    expect(container).toBeEmptyDOMElement();
  });
});
