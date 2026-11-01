import { act, render, screen } from "@testing-library/react-native";
import { RideMap } from "@/components/ride-map";

const webview = jest.requireMock("react-native-webview") as { __injected: string[]; __props: { current: Record<string, (...a: unknown[]) => unknown> } };

const pickup = { lat: 12.9716, lon: 77.5946 };
const drop = { lat: 12.98, lon: 77.6 };

beforeEach(() => {
  webview.__injected.length = 0;
});

async function pageIsReady() {
  await act(async () => {
    webview.__props.current.onMessage!({ nativeEvent: { data: "ready" } });
  });
}

describe("the map", () => {
  it("shows a card with the map's label, and sends nothing until the page says it is ready", async () => {
    await render(<RideMap pickup={pickup} drop={drop} label="Map of your ride" />);
    expect(screen.getByLabelText("Map of your ride")).toBeTruthy();
    expect(webview.__injected).toHaveLength(0);
  });

  it("tells the page what to show once it is ready, and again each time that changes", async () => {
    const { rerender } = await render(<RideMap pickup={pickup} drop={drop} />);
    await pageIsReady();
    expect(webview.__injected).toHaveLength(1);
    expect(webview.__injected[0]).toMatch(/^window\.setState\(\{.*"pickup":\{"lat":12\.9716,"lon":77\.5946\}.*\}\);true;$/);

    await rerender(<RideMap pickup={pickup} drop={drop} route={[[12.9716, 77.5946], [12.98, 77.6]]} car={{ lat: 12.972, lon: 77.595, heading: 90, vehicle: "SUV" }} />);
    expect(webview.__injected).toHaveLength(2);
    expect(webview.__injected[1]).toContain('"vehicle":"SUV"');
    expect(webview.__injected[1]).toContain('"route":[[12.9716,77.5946],[12.98,77.6]]');
  });

  it("does not resend what has not changed", async () => {
    const { rerender } = await render(<RideMap pickup={pickup} drop={drop} />);
    await pageIsReady();
    await rerender(<RideMap pickup={{ ...pickup }} drop={{ ...drop }} />); // new objects, same places
    expect(webview.__injected).toHaveLength(1);
  });

  it("loads the shared page with the tile server and Leaflet's integrity hashes", async () => {
    await render(<RideMap pickup={pickup} />);
    const source = webview.__props.current.source as unknown as { html: string; baseUrl: string };
    expect(source.html).toContain("https://tile.openstreetmap.org/{z}/{x}/{y}.png");
    expect(source.html).toContain('integrity="sha256-');
    expect(source.baseUrl).toBe("https://map.ridehail.app");
  });

  it("lets nothing take the card to another page", async () => {
    await render(<RideMap pickup={pickup} />);
    const allow = webview.__props.current.onShouldStartLoadWithRequest as (r: { url: string; isTopFrame?: boolean }) => boolean;
    expect(allow({ url: "https://map.ridehail.app/", isTopFrame: true })).toBe(true);
    expect(allow({ url: "about:blank", isTopFrame: true })).toBe(true);
    expect(allow({ url: "https://tile.openstreetmap.org/14/1/2.png", isTopFrame: false })).toBe(true); // the page's own pieces
    expect(allow({ url: "https://www.openstreetmap.org/copyright", isTopFrame: true })).toBe(false);
    expect(allow({ url: "https://evil.example/", isTopFrame: true })).toBe(false);
  });
});
