import { useState } from "react";
import { Book } from "@/components/book";
import { Receipt } from "@/components/receipt";
import { TripView } from "@/components/trip-view";
import { ErrorNote, Muted } from "@/components/ui";
import { useActiveTrip, useTrip } from "@/lib/queries";

export function Home() {
  const active = useActiveTrip();
  // when the active trip disappears, it ended: remember which one, and show how it ended until it is dismissed
  const [lastId, setLastId] = useState<string | null>(null);
  const [dismissed, setDismissed] = useState<string | null>(null);
  // (adjusting state while rendering, React's way of deriving state from a changed value)
  if (active.data && active.data.id !== lastId) setLastId(active.data.id);
  const ended = useTrip(lastId, !active.data && !!lastId && lastId !== dismissed);

  if (active.isPending) return <Muted style={{ textAlign: "center", paddingVertical: 40 }}>Loading…</Muted>;
  if (active.isError) return <ErrorNote>{active.error.message}</ErrorNote>;
  if (active.data) return <TripView trip={active.data} />;
  if (lastId && lastId !== dismissed && ended.data && ended.data.status !== "REQUESTED") {
    return <Receipt trip={ended.data} onDone={() => setDismissed(lastId)} />;
  }
  return <Book />;
}
