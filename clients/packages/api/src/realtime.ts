import { Client, type IFrame } from "@stomp/stompjs";
import { parseRealtime, type RealtimeMessage, type UnknownMessage } from "./schemas";

/** The per-user streams. The server addresses each person's own queue; clients only ever see their own messages. */
export const Destinations = {
  offers: "/user/topic/offers",
  trips: "/user/topic/trips",
  location: "/user/topic/location",
} as const;

/** The server disconnects a client that subscribes to a stream that is not for its role, so subscribe by role. */
export const streamsFor = (role: "RIDER" | "CUSTOMER"): string[] =>
  role === "RIDER" ? [Destinations.offers, Destinations.trips] : [Destinations.trips, Destinations.location];

/** http(s)://host[:port] to ws(s)://host[:port]/ws */
export function toWebSocketUrl(httpBase: string): string {
  return httpBase.replace(/\/$/, "").replace(/^http/, "ws") + "/ws";
}

export interface RealtimeOptions {
  /** ws(s)://host/ws, see toWebSocketUrl. */
  url: string;
  role: "RIDER" | "CUSTOMER";
  /** Called before every (re)connect, so a token that expired while offline is replaced. Return null to not connect. */
  getToken: () => Promise<string | null> | string | null;
  onMessage: (message: RealtimeMessage | UnknownMessage, destination: string) => void;
  /**
   * Called each time the connection is up. Pushes are best-effort and nothing is replayed, so on every call
   * (the first one included) refetch current state over REST, then rely on pushes.
   */
  onConnected?: (info: { reconnect: boolean }) => void;
  onDisconnected?: () => void;
  /** The server rejected the connection or a frame (typically an expired token): refresh the session here. */
  onError?: (message: string) => void;
  /** For tests, or React Native if a polyfilled WebSocket is needed. */
  webSocketFactory?: () => WebSocket;
}

export interface Realtime {
  close(): Promise<void>;
}

/**
 * One STOMP-over-WebSocket connection with automatic reconnect. Heartbeats match the server's (10 s) so a phone that
 * walked into a tunnel is noticed and reconnected rather than left looking connected.
 */
export function connectRealtime(opts: RealtimeOptions): Realtime {
  let everConnected = false;
  const client = new Client({
    brokerURL: opts.webSocketFactory ? undefined : opts.url,
    webSocketFactory: opts.webSocketFactory,
    reconnectDelay: 3000,
    heartbeatIncoming: 10_000,
    heartbeatOutgoing: 10_000,
    // React Native's WebSocket needs binary frames and does not deliver the trailing NULL
    forceBinaryWSFrames: true,
    appendMissingNULLonIncoming: true,
  });

  client.beforeConnect = async () => {
    const token = await opts.getToken();
    if (!token) {
      await client.deactivate();
      return;
    }
    client.connectHeaders = { Authorization: `Bearer ${token}` };
  };

  client.onConnect = () => {
    for (const destination of streamsFor(opts.role)) {
      client.subscribe(destination, (frame) => {
        let raw: unknown;
        try {
          raw = JSON.parse(frame.body);
        } catch {
          raw = frame.body;
        }
        opts.onMessage(parseRealtime(raw), destination);
      });
    }
    const reconnect = everConnected;
    everConnected = true;
    opts.onConnected?.({ reconnect });
  };

  client.onWebSocketClose = () => opts.onDisconnected?.();
  client.onStompError = (frame: IFrame) => opts.onError?.(frame.headers["message"] ?? "Realtime connection refused");

  client.activate();
  return { close: () => client.deactivate() };
}
