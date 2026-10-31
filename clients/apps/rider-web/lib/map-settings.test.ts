import { describe, expect, it } from "vitest";
import { DEFAULT_TILE_URL, mapSettingsFromEnv, tileOrigin } from "@/lib/map-settings";

describe("map settings", () => {
  it("defaults to OpenStreetMap and can be pointed at another tile server", () => {
    expect(mapSettingsFromEnv({}).tileUrl).toBe(DEFAULT_TILE_URL);
    expect(mapSettingsFromEnv({ MAP_TILE_URL: " https://tiles.example.com/{z}/{x}/{y}.png ", MAP_TILE_ATTRIBUTION: "Example" })).toEqual({
      tileUrl: "https://tiles.example.com/{z}/{x}/{y}.png",
      attribution: "Example",
    });
  });

  it("lets the Content-Security-Policy allow exactly the tile server and nothing else", () => {
    expect(tileOrigin(DEFAULT_TILE_URL)).toBe("https://tile.openstreetmap.org");
    expect(tileOrigin("https://{s}.tiles.example.com/{z}/{x}/{y}.png")).toBe("https://*.tiles.example.com");
    expect(tileOrigin("not a url")).toBe("");
  });
});
