"use client";

import { connectRealtime, type Realtime, type RealtimeMessage, type UnknownMessage } from "@ridehail/api";
import { useQueryClient } from "@tanstack/react-query";
import { createContext, type ReactNode, useContext, useEffect, useState } from "react";
import { useNotices } from "@/components/notices";
import { fetchRealtimeToken } from "@/lib/api";
import { money } from "@/lib/format";
import { keys } from "@/lib/queries";

export type Connection = "connecting" | "live" | "reconnecting";
const ConnectionContext = createContext<Connection>("connecting");
export const useConnection = () => useContext(ConnectionContext);

/**
 * The customer's push connection (trip updates, the rider's live position, payment results). A push means "something
 * changed": the app reads the truth over REST, and does so again every time the connection comes back, since nothing is
 * replayed. The rider's position is the one thing used straight from the push, because it arrives every couple of seconds.
 */
export function RealtimeProvider({ children }: { children: ReactNode }) {
  const qc = useQueryClient();
  const { show } = useNotices();
  const [connection, setConnection] = useState<Connection>("connecting");

  useEffect(() => {
    let live: Realtime | undefined;
    let cancelled = false;

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

    void (async () => {
      const first = await fetchRealtimeToken();
      if (!first || cancelled) return;
      live = connectRealtime({
        url: first.url,
        role: "CUSTOMER",
        // asked again before every reconnect, so a token that expired while the phone was offline is replaced
        getToken: async () => (await fetchRealtimeToken())?.token ?? null,
        onMessage,
        onConnected: () => {
          setConnection("live");
          void qc.invalidateQueries();
        },
        onDisconnected: () => setConnection("reconnecting"),
      });
    })();

    return () => {
      cancelled = true;
      void live?.close();
    };
  }, [qc, show]);

  return <ConnectionContext.Provider value={connection}>{children}</ConnectionContext.Provider>;
}
