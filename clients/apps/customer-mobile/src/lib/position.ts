import * as Location from "expo-location";

export type Fix = { lat: number; lon: number };

/** The phone's current position, once. (The customer's position is only needed to say where to be picked up.) */
export async function currentPosition(): Promise<Fix> {
  const perm = await Location.requestForegroundPermissionsAsync();
  if (perm.status !== "granted") throw new Error("Location access is off. Turn it on in Settings, or enter your pickup point below.");
  try {
    const p = await Location.getCurrentPositionAsync({ accuracy: Location.Accuracy.High });
    return { lat: p.coords.latitude, lon: p.coords.longitude };
  } catch {
    throw new Error("Your position is not available right now. Enter your pickup point below.");
  }
}

export function parseCoordinates(lat: string, lon: string): Fix | null {
  const la = Number(lat), lo = Number(lon);
  return lat.trim() !== "" && lon.trim() !== "" && Number.isFinite(la) && Number.isFinite(lo) && Math.abs(la) <= 90 && Math.abs(lo) <= 180 ? { lat: la, lon: lo } : null;
}
