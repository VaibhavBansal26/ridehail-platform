import { describe, expect, it } from "vitest";
import { LEAFLET_CSS, LEAFLET_JS, mapPageHtml, mapStateScript } from "./map-page";

const options = { tileUrl: "https://tile.openstreetmap.org/{z}/{x}/{y}.png", attribution: "\u00a9 OpenStreetMap contributors" };

describe("the map page", () => {
  it("loads Leaflet with integrity hashes, so a tampered copy is refused", () => {
    const html = mapPageHtml(options);
    expect(html).toContain(`src="${LEAFLET_JS.url}" integrity="${LEAFLET_JS.integrity}"`);
    expect(html).toContain(`href="${LEAFLET_CSS.url}" integrity="${LEAFLET_CSS.integrity}"`);
    expect(LEAFLET_JS.integrity).toMatch(/^sha256-[A-Za-z0-9+/]{43}=$/);
    expect(LEAFLET_CSS.integrity).toMatch(/^sha256-[A-Za-z0-9+/]{43}=$/);
  });

  it("tells the app when it is ready, and takes its state from window.setState", () => {
    const html = mapPageHtml(options);
    expect(html).toContain("window.ReactNativeWebView.postMessage('ready')");
    expect(html).toContain("window.setState = function");
  });

  it("carries the tile address and every picture, and nothing from outside can end the script early", () => {
    const html = mapPageHtml({ tileUrl: options.tileUrl, attribution: '</script><script>alert(1)</script> & "quotes"' });
    expect(html).toContain("https://tile.openstreetmap.org/{z}/{x}/{y}.png");
    for (const picture of ["BIKE", "AUTO", "SEDAN", "SUV", "pickup", "drop"]) expect(html).toContain(`"${picture}":"\\u003csvg`); // pictures are escaped for the page
    expect(html).not.toContain("</script><script>alert(1)");
    expect(html.split("</script>")).toHaveLength(3); // Leaflet's tag and the page's own: exactly two
  });
});

describe("the state script", () => {
  it("gives the page the state as data, in a form injectJavaScript accepts", () => {
    const script = mapStateScript({ pickup: { lat: 12.97, lon: 77.59 }, car: { lat: 12.98, lon: 77.6, heading: 90, vehicle: "SEDAN" }, route: [[12.97, 77.59], [12.98, 77.6]] });
    expect(script.startsWith("window.setState({")).toBe(true);
    expect(script.endsWith(");true;")).toBe(true);
    expect(script).toContain('"vehicle":"SEDAN"');
  });

  it("cannot be used to run code, whatever a place is called", () => {
    const script = mapStateScript({ pickup: { lat: 1, lon: 2 }, route: null });
    expect(script).not.toContain("<");
    const hostile = JSON.stringify({ name: "</script>\u2028\u2029" });
    expect(mapStateScript({ pickup: { lat: 1, lon: 2, ...(JSON.parse(hostile) as object) } } as never)).not.toMatch(/<\/script>|\u2028|\u2029/);
  });
});
