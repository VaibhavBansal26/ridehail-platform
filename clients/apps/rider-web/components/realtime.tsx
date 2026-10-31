"use client";

import { connectRealtime, type Realtime, type RealtimeMessage, type UnknownMessage } from "@ridehail/api";
import { useQueryClient } from "@tanstack/react-query";
import { createContext, type ReactNode, useContext, useEffect, useState } from "react";
import { useNotices } from "@/components/notices";
import { fetchRealtimeToken } from "@/lib/api";
import { money } from "@/lib/format";

export type Connection = "connecting" | "live" | "reconnecting";
const ConnectionContext = createContext<Connection>("connecting");
export const useConnection = () => useContext(ConnectionContext);

/**
 * The rider's push connection (offers, cancellations, earnings). A push only ever means "something changed": the app
 * then fetches the truth over REST, and does so again every time the connection comes back, since nothing is replayed.
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
        case "OFFER_RECEIVED":
        case "OFFER_EXPIRED":
        case "OFFER_WITHDRAWN":
          void qc.invalidateQueries({ queryKey: ["offers"] });
          break;
        case "TRIP_CANCELLED":
          show("The customer cancelled this trip.", "bad");
          void qc.invalidateQueries();
          break;
        case "EARNING_CREDITED":
          if ("amount" in m && typeof m.amount === "number") show(`You earned ${money(m.amount, m.currency)}.`, "ok");
          void qc.invalidateQueries({ queryKey: ["earnings"] });
          break;
        case "BANK_PAYOUT_FAILED":
          show("Your bank rejected a payout. Update your bank details to withdraw again.", "bad");
          void qc.invalidateQueries({ queryKey: ["earnings"] });
          void qc.invalidateQueries({ queryKey: ["payouts"] });
          break;
        case "FUNDS_ON_HOLD":
        case "HOLD_ENDED":
          void qc.invalidateQueries({ queryKey: ["earnings"] });
          break;
        default:
          break; // trip updates for customers, unknown messages: nothing for a rider to do
      }
    };

    void (async () => {
      const first = await fetchRealtimeToken();
      if (!first || cancelled) return;
      live = connectRealtime({
        url: first.url,
        role: "RIDER",
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
