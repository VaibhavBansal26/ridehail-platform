import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { Book } from "@/components/book";
import { api } from "@/lib/api";
import { quote, renderApp } from "@/test/render";

const maps: Record<string, unknown>[] = [];
vi.mock("@/components/ride-map", () => ({
  RideMap: (props: Record<string, unknown>) => {
    maps.push(props);
    return null;
  },
}));
vi.mock("@/lib/api", () => ({
  api: {
    customer: { me: vi.fn() },
    fares: { quote: vi.fn() },
    trips: { book: vi.fn() },
    places: { search: vi.fn() },
    routes: { between: vi.fn() },
  },
}));
const A = api as unknown as {
  customer: { me: ReturnType<typeof vi.fn> };
  fares: { quote: ReturnType<typeof vi.fn> };
  places: { search: ReturnType<typeof vi.fn> };
  routes: { between: ReturnType<typeof vi.fn> };
};

const cubbon = { name: "Cubbon Park", address: "Cubbon Park, Sampangi Rama Nagara, Bengaluru, Karnataka, India", lat: 12.9763, lon: 77.5929 };
const road: [number, number][] = [[12.9716, 77.5946], [12.9739, 77.594], [12.9763, 77.5929]];

async function pickupAt(user: ReturnType<typeof userEvent.setup>) {
  await user.click(screen.getByText("Enter pickup coordinates"));
  await user.click(screen.getByRole("button", { name: "Use this pickup point" }));
}

beforeEach(() => {
  vi.clearAllMocks();
  maps.length = 0;
  A.customer.me.mockResolvedValue({ id: "c1", name: "Ravi", hasPaymentMethod: true });
  A.routes.between.mockResolvedValue({ distanceKm: 3.1, durationMin: 9, points: road });
});

describe("choosing a destination by searching for it", () => {
  it("searches when asked, near the pickup, and shows what was found", async () => {
    A.places.search.mockResolvedValue([cubbon]);
    const user = userEvent.setup();
    await renderApp(<Book />);
    await pickupAt(user);
    await user.type(screen.getByRole("searchbox"), "cubbon park");
    await user.click(screen.getByRole("button", { name: "Search" }));
    expect(await screen.findByText("Cubbon Park")).toBeInTheDocument();
    expect(screen.getByText(/Sampangi Rama Nagara/)).toBeInTheDocument();
    expect(A.places.search).toHaveBeenCalledWith("cubbon park", { lat: 12.9716, lon: 77.5946 });
  });

  it("does not search for fewer than three letters", async () => {
    const user = userEvent.setup();
    await renderApp(<Book />);
    await user.type(screen.getByRole("searchbox"), "cu");
    expect(screen.getByRole("button", { name: "Search" })).toBeDisabled();
    expect(A.places.search).not.toHaveBeenCalled();
  });

  it("takes the chosen result as the destination for the fare and for the map", async () => {
    A.places.search.mockResolvedValue([cubbon]);
    A.fares.quote.mockResolvedValue(quote);
    const user = userEvent.setup();
    await renderApp(<Book />);
    await pickupAt(user);
    await user.type(screen.getByRole("searchbox"), "cubbon park");
    await user.click(screen.getByRole("button", { name: "Search" }));
    await user.click(await screen.findByRole("button", { name: /Cubbon Park/ }));

    expect(screen.queryByRole("list", { name: "Search results" })).not.toBeInTheDocument();
    expect(screen.getByRole("combobox", { name: /Drop-off/ })).toHaveDisplayValue("Cubbon Park");
    await user.click(screen.getByRole("button", { name: "See fare" }));
    expect(A.fares.quote.mock.calls[0]![0]).toMatchObject({ dropLat: 12.9763, dropLon: 77.5929 });
    await waitFor(() => expect(maps[maps.length - 1]).toMatchObject({ drop: { lat: 12.9763, lon: 77.5929 }, route: road }));
  });

  it("says so, and keeps the popular places, when the search service is down", async () => {
    A.places.search.mockImplementation(() => Promise.reject(Object.assign(new Error("Address search is not available right now"), { status: 502 })));
    const user = userEvent.setup();
    await renderApp(<Book />);
    await user.type(screen.getByRole("searchbox"), "cubbon park");
    await user.click(screen.getByRole("button", { name: "Search" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(/Search isn't available right now/);
    expect(screen.getByRole("combobox", { name: /Drop-off/ })).toBeEnabled();
  });

  it("says when nothing was found", async () => {
    A.places.search.mockResolvedValue([]);
    const user = userEvent.setup();
    await renderApp(<Book />);
    await user.type(screen.getByRole("searchbox"), "zzzzzz");
    await user.click(screen.getByRole("button", { name: "Search" }));
    expect(await screen.findByText(/Nothing found/)).toBeInTheDocument();
  });
});

describe("the road on the map", () => {
  it("draws the road between the pickup and the destination once the routing service has answered", async () => {
    const user = userEvent.setup();
    await renderApp(<Book />);
    await pickupAt(user);
    await waitFor(() => expect(maps[maps.length - 1]).toMatchObject({ pickup: { lat: 12.9716, lon: 77.5946 }, route: road }));
    expect(A.routes.between).toHaveBeenCalledWith(expect.objectContaining({ lat: 12.9716, lon: 77.5946 }), expect.objectContaining({ lat: 12.9756, lon: 77.6068 }));
  });

  it("falls back to no road (a straight line on the map) when the routing service is down", async () => {
    A.routes.between.mockImplementation(() => Promise.reject(Object.assign(new Error("Routing is not available right now"), { status: 502 })));
    const user = userEvent.setup();
    await renderApp(<Book />);
    await pickupAt(user);
    await waitFor(() => expect(A.routes.between).toHaveBeenCalled());
    expect(maps[maps.length - 1]!.route).toBeUndefined();
  });
});
