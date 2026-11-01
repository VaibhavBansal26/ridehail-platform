"use client";

import type { Quote, VehicleType } from "@ridehail/api";
import Link from "next/link";
import { type FormEvent, useEffect, useState } from "react";
import { RideMap } from "@/components/ride-map";
import { Button, Card, ErrorNote, Field, inputClass } from "@/components/ui";
import { VehicleIcon } from "@/components/vehicle-art";
import { km, money, secondsUntil } from "@/lib/format";
import { currentPosition, type Fix, parseCoordinates } from "@/lib/position";
import { PLACES, type Place } from "@/lib/places";
import { useBook, useCustomer, usePlaceSearch, useQuote, useRoad } from "@/lib/queries";

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
  const [dest, setDest] = useState<Pick<Place, "name" | "lat" | "lon">>(PLACES[0]!);
  const [term, setTerm] = useState("");
  const search = usePlaceSearch();
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

  function runSearch(e?: FormEvent) {
    e?.preventDefault();
    const q = term.trim();
    if (q.length >= 3) search.mutate({ q, near: pickup ?? undefined });
  }

  function getFare() {
    setFormError(null);
    if (!pickup) return setFormError("Set your pickup point first.");
    quote.mutate({ vehicleType: vehicle, pickupLat: pickup.lat, pickupLon: pickup.lon, dropLat: dest.lat, dropLon: dest.lon });
  }

  const noCard = customer.data && !customer.data.hasPaymentMethod;
  const road = useRoad(pickup, dest);
  const popular = PLACES.find((p) => p.lat === dest.lat && p.lon === dest.lon);

  return (
    <>
      <RideMap height="h-44" pickup={pickup} drop={dest} route={road.data} label="Map of your pickup and drop-off" />
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

          <form onSubmit={runSearch} role="search" className="space-y-2">
            <Field label="Search for an address or place">
              <div className="flex gap-2">
                <input type="search" value={term} onChange={(e) => setTerm(e.target.value)} placeholder="e.g. Cubbon Park" autoComplete="off" className={inputClass} />
                <Button type="submit" variant="secondary" busy={search.isPending} disabled={term.trim().length < 3}>Search</Button>
              </div>
            </Field>
            {search.error ? <ErrorNote>Search isn&apos;t available right now. Choose from the popular places below.</ErrorNote> : null}
            {search.data && search.data.length === 0 ? <p className="text-sm text-muted">Nothing found. Try another name, or choose a popular place.</p> : null}
            {search.data && search.data.length > 0 ? (
              <ul aria-label="Search results" className="divide-y divide-line overflow-hidden rounded-lg border border-line">
                {search.data.map((p, i) => (
                  <li key={`${p.lat},${p.lon},${i}`}>
                    <button type="button" onClick={() => { setDest(p); search.reset(); }} className="block min-h-11 w-full px-3 py-2 text-left hover:bg-surface-2">
                      <span className="block text-sm font-medium">{p.name}</span>
                      <span className="block truncate text-xs text-muted">{p.address}</span>
                    </button>
                  </li>
                ))}
              </ul>
            ) : null}
          </form>

          <Field label="Drop-off">
            <select
              value={popular ? popular.id : "chosen"}
              onChange={(e) => { const p = PLACES.find((x) => x.id === e.target.value); if (p) setDest(p); }}
              className={inputClass}
            >
              {popular ? null : <option value="chosen">{dest.name}</option>}
              {PLACES.map((p) => (
                <option key={p.id} value={p.id}>{p.name}</option>
              ))}
            </select>
          </Field>

          <div>
            <div className="mb-1 text-sm font-medium">Ride type</div>
            <div role="radiogroup" className="grid grid-cols-4 gap-2">
              {VEHICLES.map(([v, label]) => (
                <button key={v} role="radio" aria-checked={vehicle === v} onClick={() => setVehicle(v)} className={`flex min-h-11 flex-col items-center gap-0.5 rounded-lg border py-1.5 text-sm font-medium ${vehicle === v ? "border-accent text-accent" : "border-line"}`}>
                  <VehicleIcon type={v} size={34} />
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
