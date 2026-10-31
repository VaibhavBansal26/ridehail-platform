import { connectRealtime, money, type Realtime, type RealtimeMessage, toWebSocketUrl, type UnknownMessage } from "@ridehail/api";
import { useQueryClient } from "@tanstack/react-query";
import { createContext, type ReactNode, useContext, useEffect, useState } from "react";
import { auth } from "@/lib/api";
import { API_URL } from "@/lib/config";
import { useNotices } from "@/lib/notices";
import { keys } from "@/lib/queries";

export type Connection = "connecting" | "live" | "reconnecting";
const ConnectionContext = createContext<Connection>("connecting");
export const useConnection = () => useContext(ConnectionContext);

/**
 * The customer's push connection (trip updates, the rider's live position, payment results). A push only ever means "something changed": the app
 * then reads the truth over REST, and does so again every time the connection comes back, since nothing is replayed.
 */
export function RealtimeProvider({ children }: { children: ReactNode }) {
  const qc = useQueryClient();
  const { show } = useNotices();
  const [connection, setConnection] = useState<Connection>("connecting");

  useEffect(() => {
    const onMessage = (m: RealtimeMessage | UnknownMessage) => {
      switch (m.type) {
        case "RIDER_LOCATION": {
          const key = keys.riderLocation(m.tripId);
          const previous = qc.getQueryData<{ at: string } | null>(key);
          // messages are not guaranteed to arrive in order across reconnects: never go back to an older position
          if (!previous || previous.at <= m.at) qc.setQueryData(key, { tripId: m.tripId, status: m.status, lat: m.lat, lon: m.lon, at: m.at });
          break;
        }
        case "TRIP_ACCEPTED":
          show(`${"riderName" in m && m.riderName ? m.riderName : "A rider"} accepted your trip.`, "ok");
          void qc.invalidateQueries();
          break;
        case "RIDER_ARRIVED":
          show("Your rider has arrived.", "ok");
          void qc.invalidateQueries();
          break;
        case "TRIP_CANCELLED":
          show("This trip was cancelled.", "bad");
          void qc.invalidateQueries();
          break;
        case "NO_RIDERS_AVAILABLE":
          show("No riders are available nearby right now. Try again in a moment.", "bad");
          void qc.invalidateQueries();
          break;
        case "TRIP_STARTED":
        case "TRIP_COMPLETED":
        case "TRIP_REQUESTED":
          void qc.invalidateQueries();
          break;
        case "PAYMENT_CAPTURED":
          if ("amount" in m && typeof m.amount === "number") show(`Your payment of ${money(m.amount, m.currency)} went through.`, "ok");
          void qc.invalidateQueries();
          break;
        case "PAYMENT_FAILED":
          show("We could not charge your card. Update your card to keep riding.", "bad");
          void qc.invalidateQueries();
          break;
        default:
          break;
      }
    };

    const live: Realtime = connectRealtime({
      url: toWebSocketUrl(API_URL),
      role: "CUSTOMER",
      // asked again before every reconnect: a token that expired while the phone was offline is renewed first
      getToken: () => auth.accessToken(),
      onMessage,
      onConnected: () => {
        setConnection("live");
        void qc.invalidateQueries();
      },
      onDisconnected: () => setConnection("reconnecting"),
    });
    return () => {
      void live.close();
    };
  }, [qc, show]);

  return <ConnectionContext.Provider value={connection}>{children}</ConnectionContext.Provider>;
}
