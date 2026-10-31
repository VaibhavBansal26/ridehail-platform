import { parseCoordinates } from "@/lib/position";
import { PLACES } from "@/lib/places";

describe("parseCoordinates", () => {
  it("accepts a real position", () => {
    expect(parseCoordinates("12.9716", "77.5946")).toEqual({ lat: 12.9716, lon: 77.5946 });
    expect(parseCoordinates(" -33.86 ", "151.2")).toEqual({ lat: -33.86, lon: 151.2 });
    expect(parseCoordinates("0", "0")).toEqual({ lat: 0, lon: 0 });
  });
  it("refuses blanks, words and impossible values", () => {
    for (const [la, lo] of [["", ""], ["12", ""], ["abc", "1"], ["91", "0"], ["0", "181"], ["NaN", "1"], ["Infinity", "1"]]) {
      expect(parseCoordinates(la!, lo!)).toBeNull();
    }
  });
});

describe("the drop-off places", () => {
  it("are valid, distinct points", () => {
    expect(new Set(PLACES.map((p) => p.id)).size).toBe(PLACES.length);
    for (const p of PLACES) expect(parseCoordinates(String(p.lat), String(p.lon))).not.toBeNull();
  });
});
