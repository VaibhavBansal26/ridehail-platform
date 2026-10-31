"use client";

import type { Trip } from "@ridehail/api";
import { Navigation } from "lucide-react";
import { Button, Card, ErrorNote } from "@/components/ui";
import { directionsUrl, money } from "@/lib/format";
import { useTripStep } from "@/lib/queries";

const STEP = {
  ACCEPTED: { title: "Head to the pickup", button: "I've arrived", step: "arrive", target: "pickup" },
  ARRIVED: { title: "Waiting for the customer", button: "Start trip", step: "start", target: "pickup" },
  IN_PROGRESS: { title: "Drive to the drop-off", button: "Complete trip", step: "complete", target: "drop" },
} as const;

/** The trip the rider is on: where to go next, and the one button that moves it along. */
export function TripPanel({ trip, onCompleted }: { trip: Trip; onCompleted: (trip: Trip) => void }) {
  const step = useTripStep();
  const current = STEP[trip.status as keyof typeof STEP];
  if (!current) return null;

  const dest = current.target === "pickup" ? { lat: trip.pickupLat, lon: trip.pickupLon } : { lat: trip.dropLat, lon: trip.dropLon };

  return (
    <Card>
      <div className="text-xs font-medium tracking-wide text-muted uppercase">Current trip · {money(trip.fare, trip.currency)}</div>
      <h2 className="mt-1 text-xl font-semibold">{current.title}</h2>
      <a
        href={directionsUrl(dest.lat, dest.lon)}
        target="_blank"
        rel="noreferrer"
        className="mt-3 inline-flex min-h-11 items-center gap-2 rounded-lg border border-line px-3 text-sm font-medium hover:bg-surface-2"
      >
        <Navigation className="size-4" aria-hidden /> Navigate to {current.target === "pickup" ? "pickup" : "drop-off"}
      </a>
      {step.error ? (
        <div className="mt-3">
          <ErrorNote>{step.error.message}</ErrorNote>
        </div>
      ) : null}
      <Button
        className="mt-4 w-full"
        busy={step.isPending}
        onClick={() =>
          step.mutate(
            { tripId: trip.id, step: current.step },
            { onSuccess: (done) => done.status === "COMPLETED" && onCompleted(done) },
          )
        }
      >
        {current.button}
      </Button>
    </Card>
  );
}
