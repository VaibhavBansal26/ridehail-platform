"use client";

import type { Trip } from "@ridehail/api";
import { Button, Card, ErrorNote } from "@/components/ui";
import { distanceKm, km, money, titleCase } from "@/lib/format";
import { useCancel, useRiderLocation } from "@/lib/queries";

/** The trip as it stands: finding a rider, who is coming and how far away they are, or the ride itself. */
export function TripView({ trip }: { trip: Trip }) {
  const cancel = useCancel();
  const tracking = trip.status === "ACCEPTED" || trip.status === "ARRIVED" || trip.status === "IN_PROGRESS";
  const where = useRiderLocation(trip.id, tracking);
  const rider = trip.riderName ?? "Your rider";

  let title = "Finding you a rider…";
  let detail = "We are asking the nearest riders. This usually takes under a minute.";
  if (trip.status === "ACCEPTED" || trip.status === "ARRIVED" || trip.status === "IN_PROGRESS") {
    const target = trip.status === "IN_PROGRESS" ? { lat: trip.dropLat, lon: trip.dropLon } : { lat: trip.pickupLat, lon: trip.pickupLon };
    const away = where.data ? km(distanceKm(where.data, target)) : null;
    if (trip.status === "ACCEPTED") {
      title = `${rider} is on the way`;
      detail = away ? `${away} from your pickup point.` : "Waiting for their position…";
    } else if (trip.status === "ARRIVED") {
      title = `${rider} has arrived`;
      detail = "They are at your pickup point.";
    } else {
      title = "On your way";
      detail = away ? `${away} from your destination.` : "Enjoy the ride.";
    }
  }
  const canCancel = trip.status === "REQUESTED" || trip.status === "ACCEPTED" || trip.status === "ARRIVED";

  return (
    <Card>
      <div className="text-xs font-medium tracking-wide text-muted uppercase">
        {titleCase(trip.status)} · {money(trip.fare, trip.currency)}
      </div>
      <h2 className="mt-1 text-xl font-semibold">{title}</h2>
      <p className="mt-1 text-sm text-muted">{detail}</p>
      {trip.riderName ? (
        <p className="mt-3 text-sm">
          {trip.riderName}
          {trip.riderVehicleType ? ` · ${titleCase(trip.riderVehicleType)}` : ""}
        </p>
      ) : null}
      {cancel.error ? <div className="mt-3"><ErrorNote>{cancel.error.message}</ErrorNote></div> : null}
      {canCancel ? (
        <Button variant="secondary" className="mt-4 w-full" busy={cancel.isPending} onClick={() => cancel.mutate(trip.id)}>
          Cancel ride
        </Button>
      ) : null}
    </Card>
  );
}
