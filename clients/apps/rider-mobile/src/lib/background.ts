import { type Fix, LocationThrottle } from "@ridehail/api";
import * as Location from "expo-location";
import * as TaskManager from "expo-task-manager";
import { AppState, Platform } from "react-native";
import { api } from "@/lib/api";

/**
 * Reporting while the screen is off or another app is in front, which is when a driver is actually driving. The OS runs
 * this task even when the app is in the background; on Android a foreground-service notification keeps it alive.
 * Needs a development or production build: Expo Go cannot run background location.
 */
export const LOCATION_TASK = "ridehail-rider-location";

type Located = { coords: { latitude: number; longitude: number; heading?: number | null } };

/**
 * What to do with a batch of positions the OS delivered: send only the latest (older ones are stale), not faster than the
 * server allows, and not at all while the app is open, since the foreground reporter is already doing it.
 */
export function createBackgroundHandler(deps: { send: (fix: Fix) => Promise<unknown>; isForeground: () => boolean; now?: () => number }) {
  const now = deps.now ?? Date.now;
  const throttle = new LocationThrottle(4000);
  return async (locations: Located[]): Promise<void> => {
    if (deps.isForeground()) return;
    const last = locations.at(-1);
    if (!last) return;
    const { latitude, longitude, heading } = last.coords;
    const fix: Fix = { lat: latitude, lon: longitude, ...(heading !== null && heading !== undefined && heading >= 0 ? { heading } : {}) };
    const toSend = throttle.take(fix, now());
    if (!toSend) return;
    try {
      await deps.send(toSend);
    } catch (e) {
      const err = e as { isRateLimited?: boolean; retryAfterSeconds?: number };
      if (err?.isRateLimited) throttle.backOff(err.retryAfterSeconds ?? 5, now());
      // anything else: drop it, the next position follows in seconds
    }
  };
}

// Must run when the app starts, at the top level of a module the root layout imports, so the OS can wake it headless.
if (Platform.OS !== "web") {
  const handle = createBackgroundHandler({
    send: (fix) => api.rider.updateLocation(fix.lat, fix.lon, fix.heading),
    isForeground: () => AppState.currentState === "active",
  });
  TaskManager.defineTask<{ locations: Located[] }>(LOCATION_TASK, async ({ data, error }) => {
    if (error || !data) return;
    await handle(data.locations);
  });
}

/** Starts background reporting. False if the rider has not allowed "Always" location, or the platform cannot do it. */
export async function startBackgroundTracking(): Promise<boolean> {
  if (Platform.OS === "web") return false;
  try {
    if ((await Location.getForegroundPermissionsAsync()).status !== "granted") return false;
    if ((await Location.requestBackgroundPermissionsAsync()).status !== "granted") return false;
    if (!(await Location.hasStartedLocationUpdatesAsync(LOCATION_TASK))) {
      await Location.startLocationUpdatesAsync(LOCATION_TASK, {
        accuracy: Location.Accuracy.High,
        timeInterval: 4000,
        distanceInterval: 10,
        pausesUpdatesAutomatically: false,
        showsBackgroundLocationIndicator: true,
        foregroundService: { notificationTitle: "You're online", notificationBody: "Ridehail is sharing your location while you are online." },
      });
    }
    return true;
  } catch {
    return false;
  }
}

export async function stopBackgroundTracking(): Promise<void> {
  if (Platform.OS === "web") return;
  try {
    if (await Location.hasStartedLocationUpdatesAsync(LOCATION_TASK)) await Location.stopLocationUpdatesAsync(LOCATION_TASK);
  } catch {
    /* already stopped */
  }
}
