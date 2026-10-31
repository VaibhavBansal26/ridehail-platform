import type { Fix, Trip } from "@ridehail/api";
import { money } from "@ridehail/api";
import { useEffect, useMemo, useState } from "react";
import { Text } from "react-native";
import { ManualPosition } from "@/components/manual-position";
import { OfferCard } from "@/components/offer-card";
import { TripPanel } from "@/components/trip-panel";
import { Button, Card, ErrorNote, Muted, useTheme } from "@/components/ui";
import { api } from "@/lib/api";
import { startBackgroundTracking, stopBackgroundTracking } from "@/lib/background";
import { manualPosition, phonePosition, useLocationReporter } from "@/lib/position";
import { useActiveTrip, useGoOffline, useGoOnline, useOffers, useRider } from "@/lib/queries";

export function Drive() {
  const t = useTheme();
  const rider = useRider();
  const trip = useActiveTrip();
  const goOnline = useGoOnline();
  const goOffline = useGoOffline();

  const [manual, setManual] = useState<Fix | null>(null);
  const source = useMemo(() => (manual ? manualPosition(manual) : phonePosition), [manual]);
  const [positionError, setPositionError] = useState<string | null>(null);
  const [finished, setFinished] = useState<Trip | null>(null);

  const online = !!rider.data && rider.data.status !== "OFFLINE";
  const activeTrip = trip.data ?? null;
  const offers = useOffers(online && !activeTrip);

  // While the app is open the position goes to the server every few seconds: dispatch needs it, and during a trip so does the
  // customer's map. With "Always" location allowed it keeps going with the screen off (see background.ts).
  const tracking = useLocationReporter({
    enabled: online,
    source,
    send: (f) => api.rider.updateLocation(f.lat, f.lon, f.heading),
  });
  const [background, setBackground] = useState<boolean | null>(null);
  useEffect(() => {
    if (!online) {
      void stopBackgroundTracking();
      return;
    }
    let live = true;
    void startBackgroundTracking().then((ok) => live && setBackground(ok));
    return () => {
      live = false;
    };
  }, [online]);

  async function beginShift() {
    setPositionError(null);
    setFinished(null);
    try {
      goOnline.mutate(await source.current());
    } catch (e) {
      setPositionError((e as { message?: string }).message ?? "Your position is not available.");
    }
  }

  if (rider.isPending || trip.isPending) return <Muted style={{ textAlign: "center", paddingVertical: 40 }}>Loading…</Muted>;
  if (rider.isError) return <ErrorNote>{rider.error.message}</ErrorNote>;
  if (activeTrip) return <TripPanel trip={activeTrip} onCompleted={setFinished} />;

  const problem = tracking.message ?? positionError;
  const lostPosition = online && (tracking.state === "denied" || tracking.state === "unavailable");

  return (
    <>
      {finished ? (
        <Card>
          <Text style={{ color: t.text, fontSize: 22, fontWeight: "700" }}>Trip complete</Text>
          <Muted>{`Fare ${money(finished.fare, finished.currency)}. Your share shows in Earnings once the customer's payment goes through.`}</Muted>
          <Button label="Back to driving" variant="secondary" onPress={() => setFinished(null)} />
        </Card>
      ) : null}

      {!online ? (
        <Card>
          <Text style={{ color: t.text, fontSize: 22, fontWeight: "700" }}>You&apos;re offline</Text>
          <Muted>Go online to get trip requests near you. Your position is shared while you are online.</Muted>
          {problem ? <ErrorNote>{problem}</ErrorNote> : null}
          {goOnline.error ? <ErrorNote>{goOnline.error.message}</ErrorNote> : null}
          <Button label="Go online" busy={goOnline.isPending} onPress={() => void beginShift()} />
        </Card>
      ) : (
        <Card>
          <Text style={{ color: t.text, fontSize: 22, fontWeight: "700" }}>You&apos;re online</Text>
          <Muted>{tracking.state === "tracking" ? "Looking for trip requests near you…" : "Waiting for your position…"}</Muted>
          {problem ? <ErrorNote>{problem}</ErrorNote> : null}
          {lostPosition ? <Text style={{ color: t.warnFg, fontSize: 14 }}>We can&apos;t see where you are, so you may be offered trips that are far from you. Fix your location, or go offline.</Text> : null}
          {background === false && tracking.state === "tracking" ? (
            <Muted>Allow location &quot;Always&quot; in Settings so you stay visible to dispatch with the screen off.</Muted>
          ) : null}
          {goOffline.error ? <ErrorNote>{goOffline.error.message}</ErrorNote> : null}
          <Button label="Go offline" variant="secondary" busy={goOffline.isPending} onPress={() => goOffline.mutate()} />
        </Card>
      )}

      {online ? (offers.data ?? []).map((offer) => <OfferCard key={offer.offerId} offer={offer} />) : null}

      <ManualPosition value={manual} onChange={setManual} />
    </>
  );
}
