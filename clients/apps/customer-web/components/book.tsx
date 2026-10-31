"use client";

import type { Quote, VehicleType } from "@ridehail/api";
import Link from "next/link";
import { type FormEvent, useEffect, useState } from "react";
import { Button, Card, ErrorNote, Field, inputClass } from "@/components/ui";
import { km, money, secondsUntil } from "@/lib/format";
import { currentPosition, type Fix, parseCoordinates } from "@/lib/position";
import { PLACES } from "@/lib/places";
import { useBook, useCustomer, useQuote } from "@/lib/queries";

const VEHICLES: [VehicleType, string][] = [
  ["BIKE", "Bike"],
  ["AUTO", "Auto"],
  ["SEDAN", "Sedan"],
  ["SUV", "SUV"],
];

function useSecondsLeft(expiresAt: string | undefined): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (!expiresAt) return;
    const id = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(id);
  }, [expiresAt]);
  return expiresAt ? secondsUntil(expiresAt, now) : 0;
}

/** Choose where from and to, see the fare the server works out, and request the ride. The price is never computed here. */
export function Book() {
  const customer = useCustomer();
  const quote = useQuote();
  const book = useBook();

  const [pickup, setPickup] = useState<Fix | null>(null);
  const [pickupError, setPickupError] = useState<string | null>(null);
  const [locating, setLocating] = useState(false);
  const [dropId, setDropId] = useState(PLACES[0]!.id);
  const [vehicle, setVehicle] = useState<VehicleType>("SEDAN");
  const [formError, setFormError] = useState<string | null>(null);

  const left = useSecondsLeft(quote.data?.expiresAt);
  const live: Quote | null = quote.data && left > 0 ? quote.data : null;

  async function useMyLocation() {
    setLocating(true);
    setPickupError(null);
    try {
      setPickup(await currentPosition());
    } catch (e) {
      setPickupError((e as Error).message);
    }
    setLocating(false);
  }

  function setByHand(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const f = new FormData(e.currentTarget);
    const fix = parseCoordinates(String(f.get("lat")), String(f.get("lon")));
    if (!fix) return setPickupError("Enter a latitude between -90 and 90 and a longitude between -180 and 180.");
    setPickupError(null);
    setPickup(fix);
  }

  function getFare() {
    setFormError(null);
    const drop = PLACES.find((p) => p.id === dropId);
    if (!pickup) return setFormError("Set your pickup point first.");
    if (!drop) return setFormError("Choose where you are going.");
    quote.mutate({ vehicleType: vehicle, pickupLat: pickup.lat, pickupLon: pickup.lon, dropLat: drop.lat, dropLon: drop.lon });
  }

  const noCard = customer.data && !customer.data.hasPaymentMethod;

  return (
    <>
      <Card title="Where to?">
        <div className="space-y-4">
          <div>
            <div className="mb-1 text-sm font-medium">Pickup</div>
            {pickup ? (
              <p className="text-sm">
                {pickup.lat.toFixed(4)}, {pickup.lon.toFixed(4)}{" "}
                <button className="text-accent underline" onClick={() => setPickup(null)}>
                  Change
                </button>
              </p>
            ) : (
              <Button variant="secondary" className="w-full" busy={locating} onClick={useMyLocation}>
                Use my location
              </Button>
            )}
            {pickupError ? <div className="mt-2"><ErrorNote>{pickupError}</ErrorNote></div> : null}
            {!pickup ? (
              <details className="mt-2 rounded-lg border border-line px-3 py-2 text-sm" open={pickupError !== null}>
                <summary className="min-h-9 cursor-pointer py-1.5 font-medium">Enter pickup coordinates</summary>
                <form onSubmit={setByHand} className="mt-2 space-y-3 pb-1">
                  <div className="grid grid-cols-2 gap-2">
                    <Field label="Latitude"><input name="lat" inputMode="decimal" defaultValue="12.9716" className={inputClass} /></Field>
                    <Field label="Longitude"><input name="lon" inputMode="decimal" defaultValue="77.5946" className={inputClass} /></Field>
                  </div>
                  <Button type="submit" variant="secondary" className="w-full">Use this pickup point</Button>
                </form>
              </details>
            ) : null}
          </div>

          <Field label="Drop-off">
            <select value={dropId} onChange={(e) => setDropId(e.target.value)} className={inputClass}>
              {PLACES.map((p) => (
                <option key={p.id} value={p.id}>{p.name}</option>
              ))}
            </select>
          </Field>

          <div>
            <div className="mb-1 text-sm font-medium">Ride type</div>
            <div role="radiogroup" className="grid grid-cols-4 gap-2">
              {VEHICLES.map(([v, label]) => (
                <button key={v} role="radio" aria-checked={vehicle === v} onClick={() => setVehicle(v)} className={`min-h-11 rounded-lg border text-sm font-medium ${vehicle === v ? "border-accent text-accent" : "border-line"}`}>
                  {label}
                </button>
              ))}
            </div>
          </div>

          {formError ? <ErrorNote>{formError}</ErrorNote> : null}
          {quote.error ? <ErrorNote>{quote.error.message}</ErrorNote> : null}
          <Button className="w-full" busy={quote.isPending} onClick={getFare}>
            See fare
          </Button>
        </div>
      </Card>

      {live ? (
        <Card title="Your fare">
          <div className="flex items-baseline justify-between">
            <div className="text-3xl font-semibold">{money(live.total, live.currency)}</div>
            <div className="text-xs text-muted tabular-nums">valid for {left}s</div>
          </div>
          <p className="mt-1 text-sm text-muted">
            {km(live.distanceKm)} · about {Math.round(live.durationMin)} min
            {live.surgeActive ? ` · ${live.surgeMultiplier.toFixed(1)}× busy-time pricing` : ""}
          </p>
          {noCard ? (
            <p className="mt-3 text-sm">
              Add a card to ride. <Link href="/account" className="text-accent underline">Add a card</Link>
            </p>
          ) : null}
          {book.error ? <div className="mt-3"><ErrorNote>{book.error.message}</ErrorNote></div> : null}
          <Button className="mt-4 w-full" busy={book.isPending} disabled={!!noCard} onClick={() => book.mutate(live.quoteId)}>
            Request ride
          </Button>
        </Card>
      ) : quote.data ? (
        <p className="text-center text-sm text-muted">That fare has expired. Tap See fare for a fresh one.</p>
      ) : null}
    </>
  );
}
