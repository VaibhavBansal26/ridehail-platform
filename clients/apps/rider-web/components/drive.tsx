"use client";

import type { Trip } from "@ridehail/api";
import { useMemo, useState } from "react";
import { ManualPosition } from "@/components/manual-position";
import { OfferCard } from "@/components/offer-card";
import { TripPanel } from "@/components/trip-panel";
import { Button, Card, ErrorNote } from "@/components/ui";
import { api } from "@/lib/api";
import { money } from "@/lib/format";
import { browserPosition, type Fix, loadManualPosition, manualPosition, saveManualPosition, useLocationReporter } from "@/lib/position";
import { useActiveTrip, useGoOffline, useGoOnline, useOffers, useRider } from "@/lib/queries";

export function Drive() {
  const rider = useRider();
  const trip = useActiveTrip();
  const goOnline = useGoOnline();
  const goOffline = useGoOffline();

  const [manual, setManualState] = useState<Fix | null>(loadManualPosition);
  const setManual = (fix: Fix | null) => {
    saveManualPosition(fix);
    setManualState(fix);
  };
  const source = useMemo(() => (manual ? manualPosition(manual) : browserPosition), [manual]);
  const [positionError, setPositionError] = useState<string | null>(null);
  const [finished, setFinished] = useState<Trip | null>(null);

  const online = !!rider.data && rider.data.status !== "OFFLINE";
  const activeTrip = trip.data ?? null;
  const offers = useOffers(online && !activeTrip);

  // While online the position goes to the server every few seconds: dispatch needs it, and during a trip so does the customer's map.
  const tracking = useLocationReporter({
    enabled: online,
    source,
    send: async (f) => {
      await api.rider.updateLocation(f.lat, f.lon, f.heading);
    },
  });

  async function beginShift() {
    setPositionError(null);
    setFinished(null);
    try {
      goOnline.mutate(await source.current());
    } catch (e) {
      setPositionError((e as { message?: string }).message ?? "Your position is not available.");
    }
  }

  if (rider.isPending || trip.isPending) return <p className="py-10 text-center text-sm text-muted">Loading…</p>;
  if (rider.isError) return <ErrorNote>{rider.error.message}</ErrorNote>;

  if (activeTrip) return <TripPanel trip={activeTrip} onCompleted={setFinished} />;

  const pending = offers.data ?? [];
  const problem = tracking.message ?? positionError;
  const lostPosition = online && (tracking.state === "denied" || tracking.state === "unavailable");
  const showManual = positionError !== null || lostPosition;

  return (
    <>
      {finished ? (
        <Card>
          <h2 className="text-xl font-semibold">Trip complete</h2>
          <p className="mt-1 text-sm text-muted">
            Fare {money(finished.fare, finished.currency)}. Your share shows in Earnings once the customer&apos;s payment goes through.
          </p>
          <Button variant="secondary" className="mt-3 w-full" onClick={() => setFinished(null)}>
            Back to driving
          </Button>
        </Card>
      ) : null}

      {!online ? (
        <Card>
          <h2 className="text-xl font-semibold">You&apos;re offline</h2>
          <p className="mt-1 text-sm text-muted">Go online to get trip requests near you. Your position is shared while you are online.</p>
          {problem ? <div className="mt-3"><ErrorNote>{problem}</ErrorNote></div> : null}
          {goOnline.error ? <div className="mt-3"><ErrorNote>{goOnline.error.message}</ErrorNote></div> : null}
          <Button className="mt-4 w-full" busy={goOnline.isPending} onClick={beginShift}>
            Go online
          </Button>
        </Card>
      ) : (
        <Card>
          <h2 className="text-xl font-semibold">You&apos;re online</h2>
          <p className="mt-1 text-sm text-muted">
            {tracking.state === "tracking" ? "Looking for trip requests near you…" : "Waiting for your position…"}
          </p>
          {problem ? <div className="mt-3"><ErrorNote>{problem}</ErrorNote></div> : null}
          {lostPosition ? (
            <p role="alert" className="mt-3 text-sm text-[var(--warn-fg)]">
              We can&apos;t see where you are, so you may be offered trips that are far from you. Fix your location below, or go offline.
            </p>
          ) : null}
          {goOffline.error ? <div className="mt-3"><ErrorNote>{goOffline.error.message}</ErrorNote></div> : null}
          <Button variant="secondary" className="mt-4 w-full" busy={goOffline.isPending} onClick={() => goOffline.mutate()}>
            Go offline
          </Button>
        </Card>
      )}

      {online
        ? pending.map((offer) => <OfferCard key={offer.offerId} offer={offer} />)
        : null}

      <ManualPosition value={manual} onChange={setManual} open={showManual} />
    </>
  );
}
