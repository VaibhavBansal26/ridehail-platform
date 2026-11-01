import { describe, expect, it } from "vitest";
import { nearTheMiddle } from "./live-map";

describe("nearTheMiddle", () => {
  it("drops a stray position far from the rest", () => {
    const city: [number, number][] = [[12.97, 77.59], [12.95, 77.6], [12.99, 77.62], [23.0, 72.5]];
    expect(nearTheMiddle(city)).toEqual(city.slice(0, 3));
  });
  it("keeps everything when there are too few points to tell", () => {
    const two: [number, number][] = [[12.97, 77.59], [23.0, 72.5]];
    expect(nearTheMiddle(two)).toEqual(two);
  });
});
