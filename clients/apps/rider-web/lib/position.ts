import { useEffect, useRef, useState } from "react";

import { type Fix, type PositionProblem, type PositionSource, startLocationReporting, type TrackingState } from "@ridehail/api";

export type { Fix, PositionProblem, PositionSource, TrackingState };

function toFix(p: GeolocationPosition): Fix {
  const { latitude, longitude, heading } = p.coords;
  return { lat: latitude, lon: longitude, ...(heading !== null && Number.isFinite(heading) ? { heading } : {}) };
}

function problem(e: GeolocationPositionError): PositionProblem {
  return e.code === e.PERMISSION_DENIED
    ? { denied: true, message: "Location access is blocked. Allow it in your browser's site settings to go online." }
    : { denied: false, message: "Your position is not available right now." };
}

export const browserPosition: PositionSource = {
  watch(onFix, onProblem) {
    if (typeof navigator === "undefined" || !navigator.geolocation) {
      onProblem({ denied: false, message: "This browser cannot share a position." });
      return () => undefined;
    }
    const id = navigator.geolocation.watchPosition((p) => onFix(toFix(p)), (e) => onProblem(problem(e)), { enableHighAccuracy: true, maximumAge: 2000 });
    return () => navigator.geolocation.clearWatch(id);
  },
  current() {
    return new Promise((resolve, reject) => {
      if (typeof navigator === "undefined" || !navigator.geolocation) return reject({ denied: false, message: "This browser cannot share a position." });
      navigator.geolocation.getCurrentPosition((p) => resolve(toFix(p)), (e) => reject(problem(e)), { enableHighAccuracy: true, timeout: 10_000 });
    });
  },
};

/** While `enabled`, watches the position and sends it every few seconds (see startLocationReporting). */
export function useLocationReporter(opts: {
  enabled: boolean;
  source: PositionSource;
  send: (fix: Fix) => Promise<void>;
  intervalMs?: number;
}): { state: TrackingState; message: string | null; last: Fix | null } {
  const { enabled, source, intervalMs } = opts;
  const [status, setStatus] = useState<{ state: TrackingState; message: string | null }>({ state: "idle", message: null });
  const [last, setLast] = useState<Fix | null>(null);
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
      onFix: setLast,
      onState: (state, message) => setStatus({ state, message }),
    });
  }, [enabled, source, intervalMs]);

  return { ...status, last };
}

/** A position the rider typed in: for a desktop, a phone whose GPS is off, or trying the app without driving anywhere. */
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

const MANUAL_KEY = "rr_manual_position";

/** The hand-set position survives a reload (this tab only); storage can be unavailable, and then it simply does not. */
export function loadManualPosition(): Fix | null {
  try {
    const raw = typeof window === "undefined" ? null : window.sessionStorage.getItem(MANUAL_KEY);
    const v = raw ? (JSON.parse(raw) as Partial<Fix>) : null;
    return v && Number.isFinite(v.lat) && Number.isFinite(v.lon) ? { lat: v.lat!, lon: v.lon! } : null;
  } catch {
    return null;
  }
}

export function saveManualPosition(fix: Fix | null): void {
  try {
    if (fix) window.sessionStorage.setItem(MANUAL_KEY, JSON.stringify({ lat: fix.lat, lon: fix.lon }));
    else window.sessionStorage.removeItem(MANUAL_KEY);
  } catch {
    /* private mode or storage blocked: the position is just not remembered */
  }
}
