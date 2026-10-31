export type Fix = { lat: number; lon: number; heading?: number };
export type PositionProblem = { denied: boolean; message: string };

/** Where positions come from: the browser's geolocation on the web, the phone's location services in the app, a fake in tests. */
export interface PositionSource {
  watch(onFix: (fix: Fix) => void, onProblem: (p: PositionProblem) => void): () => void;
  current(): Promise<Fix>;
}

/**
 * Decides which positions to send. The server accepts about one a second and drops the rest, so we send one every few
 * seconds, always the latest, and after a 429 stay quiet for as long as the server asked.
 */
export class LocationThrottle {
  private lastSent = -Infinity;
  private quietUntil = -Infinity;
  constructor(private readonly intervalMs = 4000) {}

  /** The position to send now, or null to hold it back. */
  take(fix: Fix, now: number): Fix | null {
    if (now < this.quietUntil || now - this.lastSent < this.intervalMs) return null;
    this.lastSent = now;
    return fix;
  }

  backOff(seconds: number, now: number) {
    this.quietUntil = now + seconds * 1000;
  }
}

export type TrackingState = "idle" | "tracking" | "denied" | "unavailable";

export interface ReporterEvents {
  onState: (state: TrackingState, message: string | null) => void;
  onFix?: (fix: Fix) => void;
}

/**
 * Watches `source` and sends a position every few seconds. Positions that fail to send are dropped, not queued: a stale
 * position is worse than none, and the next one follows within seconds. Returns the function that stops it.
 */
export function startLocationReporting(opts: {
  source: PositionSource;
  send: (fix: Fix) => Promise<unknown>;
  intervalMs?: number;
  now?: () => number;
} & ReporterEvents): () => void {
  const now = opts.now ?? Date.now;
  const throttle = new LocationThrottle(opts.intervalMs);
  const stop = opts.source.watch(
    (fix) => {
      opts.onState("tracking", null);
      opts.onFix?.(fix);
      const toSend = throttle.take(fix, now());
      if (toSend) {
        opts.send(toSend).catch((e: { isRateLimited?: boolean; retryAfterSeconds?: number }) => {
          if (e?.isRateLimited) throttle.backOff(e.retryAfterSeconds ?? 5, now());
        });
      }
    },
    (p) => opts.onState(p.denied ? "denied" : "unavailable", p.message),
  );
  return () => {
    stop();
    opts.onState("idle", null);
  };
}
