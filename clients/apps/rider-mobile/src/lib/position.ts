import { type Fix, type PositionSource, startLocationReporting, type TrackingState } from "@ridehail/api";
import * as Location from "expo-location";
import { useEffect, useRef, useState } from "react";

const DENIED = "Location access is off. Turn it on for this app in Settings to go online.";

function toFix(loc: Location.LocationObject): Fix {
  const { latitude, longitude, heading } = loc.coords;
  // heading is -1 or null when the phone does not know which way it is going
  return { lat: latitude, lon: longitude, ...(heading !== null && heading !== undefined && heading >= 0 ? { heading } : {}) };
}

/** The phone's location services while the app is open. (Background reporting is background.ts.) */
export const phonePosition: PositionSource = {
  watch(onFix, onProblem) {
    let sub: Location.LocationSubscription | undefined;
    let stopped = false;
    void (async () => {
      try {
        const perm = await Location.requestForegroundPermissionsAsync();
        if (perm.status !== "granted") return onProblem({ denied: true, message: DENIED });
        const s = await Location.watchPositionAsync(
          { accuracy: Location.Accuracy.High, distanceInterval: 5, timeInterval: 2000 },
          (loc) => onFix(toFix(loc)),
          (reason) => onProblem({ denied: false, message: reason || "Your position is not available right now." }),
        );
        if (stopped) s.remove();
        else sub = s;
      } catch {
        onProblem({ denied: false, message: "Your position is not available right now." });
      }
    })();
    return () => {
      stopped = true;
      sub?.remove();
    };
  },
  async current() {
    const perm = await Location.requestForegroundPermissionsAsync();
    if (perm.status !== "granted") throw { denied: true, message: DENIED };
    try {
      return toFix(await Location.getCurrentPositionAsync({ accuracy: Location.Accuracy.High }));
    } catch {
      throw { denied: false, message: "Your position is not available right now." };
    }
  },
};

/** A position the rider typed in: for a simulator without a route, or trying the app without driving anywhere. */
export function manualPosition(fix: Fix): PositionSource {
  return {
    watch(onFix) {
      onFix(fix);
      const id = setInterval(() => onFix(fix), 4000);
      return () => clearInterval(id);
    },
    current: () => Promise.resolve(fix),
  };
}

/** While `enabled`, watches the position and sends it every few seconds (see startLocationReporting). */
export function useLocationReporter(opts: {
  enabled: boolean;
  source: PositionSource;
  send: (fix: Fix) => Promise<unknown>;
  intervalMs?: number;
}): { state: TrackingState; message: string | null } {
  const { enabled, source, intervalMs } = opts;
  const [status, setStatus] = useState<{ state: TrackingState; message: string | null }>({ state: "idle", message: null });
  const send = useRef(opts.send);
  useEffect(() => {
    send.current = opts.send;
  });

  useEffect(() => {
    if (!enabled) return;
    return startLocationReporting({
      source,
      intervalMs,
      send: (fix) => send.current(fix),
      onState: (state, message) => setStatus({ state, message }),
    });
  }, [enabled, source, intervalMs]);

  return status;
}
