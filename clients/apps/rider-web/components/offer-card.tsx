"use client";

import type { Offer } from "@ridehail/api";
import { useEffect, useState } from "react";
import { Button, Card, ErrorNote } from "@/components/ui";
import { km, money, secondsUntil } from "@/lib/format";
import { useOfferAnswer } from "@/lib/queries";

/** Seconds left on an offer, ticking each second; 0 means it has expired and will be withdrawn by the server. */
export function useSecondsLeft(expiresAt: string, tick = 1000): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), tick);
    return () => clearInterval(id);
  }, [tick]);
  return secondsUntil(expiresAt, now);
}

export function OfferCard({ offer }: { offer: Offer }) {
  const left = useSecondsLeft(offer.expiresAt);
  const answer = useOfferAnswer();
  const surge = offer.surgeMultiplier > 1;

  if (left === 0) return null; // the server expires it too; hiding it now avoids a tap that can only fail

  return (
    <Card>
      <div className="flex items-start justify-between gap-3">
        <div>
          <div className="text-2xl font-semibold">{money(offer.fare, offer.currency)}</div>
          <div className="text-sm text-muted">
            {km(offer.distanceToPickupKm)} to pickup{surge ? ` · ${offer.surgeMultiplier.toFixed(1)}× surge` : ""}
          </div>
        </div>
        <div aria-label={`${left} seconds left`} className="rounded-full bg-[var(--warn-bg)] px-3 py-1 text-sm font-semibold text-[var(--warn-fg)] tabular-nums">
          {left}s
        </div>
      </div>
      {answer.error ? (
        <div className="mt-3">
          <ErrorNote>{answer.error.message}</ErrorNote>
        </div>
      ) : null}
      <div className="mt-4 grid grid-cols-2 gap-2">
        <Button variant="secondary" disabled={answer.isPending} onClick={() => answer.mutate({ offerId: offer.offerId, accept: false })}>
          Decline
        </Button>
        <Button busy={answer.isPending} onClick={() => answer.mutate({ offerId: offer.offerId, accept: true })}>
          Accept
        </Button>
      </div>
    </Card>
  );
}
