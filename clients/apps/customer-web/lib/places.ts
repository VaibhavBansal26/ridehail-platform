export type Place = { id: string; name: string; lat: number; lon: number };

/**
 * Drop-off choices until the product has an address search: a list of well-known places in the launch city, plus typed
 * coordinates. A real search needs a geocoding provider (a key, a bill and a privacy decision), which is not made here.
 */
export const PLACES: Place[] = [
  { id: "mg-road", name: "MG Road", lat: 12.9756, lon: 77.6068 },
  { id: "indiranagar", name: "Indiranagar", lat: 12.9784, lon: 77.6408 },
  { id: "koramangala", name: "Koramangala", lat: 12.9352, lon: 77.6245 },
  { id: "whitefield", name: "Whitefield", lat: 12.9698, lon: 77.7499 },
  { id: "jayanagar", name: "Jayanagar", lat: 12.925, lon: 77.5938 },
  { id: "airport", name: "Kempegowda Airport", lat: 13.1986, lon: 77.7066 },
];
