import { act, fireEvent, screen, waitFor } from "@testing-library/react-native";
import { Book } from "@/components/book";
import { api } from "@/lib/api";
import { quote, renderApp } from "@/test-utils";

jest.mock("@/lib/api", () => ({
  api: {
    customer: { me: jest.fn() },
    fares: { quote: jest.fn() },
    trips: { book: jest.fn() },
    places: { search: jest.fn() },
    routes: { between: jest.fn() },
  },
  auth: { accessToken: jest.fn() },
}));
jest.mock("expo-router", () => ({ Link: ({ children }: { children: React.ReactNode }) => children }));

const A = api as unknown as {
  customer: { me: jest.Mock };
  fares: { quote: jest.Mock };
  places: { search: jest.Mock };
  routes: { between: jest.Mock };
};
const webview = jest.requireMock("react-native-webview") as { __injected: string[]; __props: { current: Record<string, (...a: unknown[]) => unknown> } };

const cubbon = { name: "Cubbon Park", address: "Cubbon Park, Sampangi Rama Nagara, Bengaluru, Karnataka, India", lat: 12.9763, lon: 77.5929 };
const road = [[12.9716, 77.5946], [12.9739, 77.594], [12.9763, 77.5929]];

beforeEach(() => {
  jest.clearAllMocks();
  webview.__injected.length = 0;
  A.customer.me.mockResolvedValue({ id: "c1", name: "Ravi", hasPaymentMethod: true });
  A.routes.between.mockResolvedValue({ distanceKm: 3.1, durationMin: 9, points: road });
});

const search = async (text: string) => {
  await fireEvent.changeText(screen.getByLabelText("Search for an address or place"), text);
  await fireEvent.press(screen.getByRole("button", { name: "Search" }));
};
const pickupHere = () => fireEvent.press(screen.getByRole("button", { name: "Use this pickup point" }));
const mapState = () => webview.__injected[webview.__injected.length - 1] ?? "";

describe("choosing a destination by searching for it", () => {
  it("searches when asked, near the pickup, and shows what was found", async () => {
    A.places.search.mockResolvedValue([cubbon]);
    await renderApp(<Book />);
    await pickupHere();
    await search("cubbon park");
    expect(await screen.findByText("Cubbon Park")).toBeTruthy();
    expect(screen.getByText(/Sampangi Rama Nagara/)).toBeTruthy();
    expect(A.places.search).toHaveBeenCalledWith("cubbon park", expect.objectContaining({ lat: 12.9716, lon: 77.5946 }));
  });

  it("does not search for fewer than three letters", async () => {
    await renderApp(<Book />);
    await fireEvent.changeText(screen.getByLabelText("Search for an address or place"), "cu");
    await fireEvent.press(screen.getByRole("button", { name: "Search" }));
    expect(A.places.search).not.toHaveBeenCalled();
  });

  it("takes the chosen result as the destination for the fare", async () => {
    A.places.search.mockResolvedValue([cubbon]);
    A.fares.quote.mockResolvedValue(quote);
    await renderApp(<Book />);
    await pickupHere();
    await search("cubbon park");
    await fireEvent.press(await screen.findByRole("button", { name: /Cubbon Park, Cubbon Park/ }));

    expect(screen.queryByLabelText("Search results")).toBeNull();
    expect(screen.getByText("Going to: Cubbon Park")).toBeTruthy();
    await fireEvent.press(screen.getByRole("button", { name: "See fare" }));
    expect(A.fares.quote.mock.calls[0]![0]).toMatchObject({ dropLat: 12.9763, dropLon: 77.5929 });
  });

  it("says so, and keeps the popular places, when the search service is down", async () => {
    A.places.search.mockImplementation(() => Promise.reject(Object.assign(new Error("Address search is not available right now"), { status: 502 })));
    await renderApp(<Book />);
    await search("cubbon park");
    expect(await screen.findByText(/Search isn't available right now/)).toBeTruthy();
    expect(screen.getByRole("radio", { name: "Indiranagar" })).toBeTruthy();
  });

  it("says when nothing was found", async () => {
    A.places.search.mockResolvedValue([]);
    await renderApp(<Book />);
    await search("zzzzzz");
    expect(await screen.findByText(/Nothing found/)).toBeTruthy();
  });
});

describe("the map and the road in the booking screen", () => {
  it("shows the pickup, the destination and the road between them once the routing service has answered", async () => {
    await renderApp(<Book />);
    await pickupHere();
    await waitFor(() => expect(A.routes.between).toHaveBeenCalled());
    await act(async () => webview.__props.current.onMessage!({ nativeEvent: { data: "ready" } }));
    await waitFor(() => expect(mapState()).toContain('"route":[[12.9716,77.5946]'));
    expect(mapState()).toContain('"pickup":{"lat":12.9716,"lon":77.5946}');
    expect(mapState()).toContain('"drop":{'); // the destination
  });
});
