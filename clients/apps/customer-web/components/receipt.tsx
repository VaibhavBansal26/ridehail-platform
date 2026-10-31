"use client";

import type { Trip } from "@ridehail/api";
import Link from "next/link";
import { Button, Card, StatusBadge } from "@/components/ui";
import { money, titleCase } from "@/lib/format";

/** What happened to the trip that just ended: a receipt with how the payment stands, or why it did not happen. */
export function Receipt({ trip, onDone }: { trip: Trip; onDone: () => void }) {
  if (trip.status === "CANCELLED" || trip.status === "NO_RIDERS_AVAILABLE") {
    return (
      <Card>
        <h2 className="text-xl font-semibold">{trip.status === "CANCELLED" ? "Ride cancelled" : "No riders available"}</h2>
        <p className="mt-1 text-sm text-muted">
          {trip.status === "CANCELLED" ? "You have not been charged." : "No rider was free nearby. You have not been charged. Try again in a moment."}
        </p>
        <Button variant="secondary" className="mt-3 w-full" onClick={onDone}>Done</Button>
      </Card>
    );
  }
  const failed = trip.paymentStatus === "REQUIRES_ATTENTION";
  return (
    <Card>
      <h2 className="text-xl font-semibold">Trip complete</h2>
      <div className="mt-2 flex items-center justify-between">
        <span className="text-2xl font-semibold">{money(trip.fare, trip.currency)}</span>
        {trip.paymentStatus ? <StatusBadge status={trip.paymentStatus} /> : null}
      </div>
      <p className="mt-2 text-sm text-muted">
        {failed
          ? "We could not charge your card. Update your card, and we will try again."
          : trip.paymentStatus === "CAPTURED"
            ? "Paid. Thanks for riding."
            : `Your card is being charged${trip.paymentStatus ? ` (${titleCase(trip.paymentStatus)})` : ""}.`}
      </p>
      {failed ? (
        <Link href="/account" className="mt-3 inline-flex min-h-11 w-full items-center justify-center rounded-lg bg-accent px-4 text-sm font-medium text-accent-ink">
          Update card
        </Link>
      ) : null}
      <Button variant="secondary" className="mt-3 w-full" onClick={onDone}>Done</Button>
    </Card>
  );
}
