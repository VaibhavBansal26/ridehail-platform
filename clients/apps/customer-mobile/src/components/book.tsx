import { km, money, type Quote, secondsUntil, type VehicleType } from "@ridehail/api";
import { Link } from "expo-router";
import { useEffect, useState } from "react";
import { Pressable, Text, View } from "react-native";
import { RideMap } from "@/components/ride-map";
import { Button, Card, ErrorNote, Field, Muted, useTheme } from "@/components/ui";
import { registerForPush } from "@/lib/push";
import { PLACES, type Place } from "@/lib/places";
import { currentPosition, type Fix, parseCoordinates } from "@/lib/position";
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

function Choice({ label, selected, onPress, role }: { label: string; selected: boolean; onPress: () => void; role: "radio" | "button" }) {
  const t = useTheme();
  return (
    <Pressable accessibilityRole={role} accessibilityState={{ selected }} onPress={onPress} style={{ minHeight: 44, borderRadius: 10, borderWidth: 1, borderColor: selected ? t.accent : t.line, paddingHorizontal: 12, alignItems: "center", justifyContent: "center", flexGrow: 1 }}>
      <Text style={{ color: selected ? t.accent : t.text, fontWeight: "600" }}>{label}</Text>
    </Pressable>
  );
}

/** Choose where from and to, see the fare the server works out, and request the ride. The price is never computed here. */
export function Book() {
  const t = useTheme();
  const customer = useCustomer();
  const quote = useQuote();
  const book = useBook();

  const [pickup, setPickup] = useState<Fix | null>(null);
  const [pickupError, setPickupError] = useState<string | null>(null);
  const [locating, setLocating] = useState(false);
  const [lat, setLat] = useState("12.9716");
  const [lon, setLon] = useState("77.5946");
  const [dest, setDest] = useState<Pick<Place, "name" | "lat" | "lon">>(PLACES[0]!);
  const [term, setTerm] = useState("");
  const search = usePlaceSearch();
  const [vehicle, setVehicle] = useState<VehicleType>("SEDAN");
  const [formError, setFormError] = useState<string | null>(null);

  const road = useRoad(pickup, dest);
  const left = useSecondsLeft(quote.data?.expiresAt);
  const live: Quote | null = quote.data && left > 0 ? quote.data : null;

  async function locateMe() {
    setLocating(true);
    setPickupError(null);
    try {
      setPickup(await currentPosition());
    } catch (e) {
      setPickupError((e as Error).message);
    }
    setLocating(false);
  }

  function setByHand() {
    const fix = parseCoordinates(lat, lon);
    if (!fix) return setPickupError("Enter a latitude between -90 and 90 and a longitude between -180 and 180.");
    setPickupError(null);
    setPickup(fix);
  }

  function runSearch() {
    const q = term.trim();
    if (q.length >= 3) search.mutate({ q, near: pickup ?? undefined });
  }

  function getFare() {
    setFormError(null);
    if (!pickup) return setFormError("Set your pickup point first.");
    quote.mutate({ vehicleType: vehicle, pickupLat: pickup.lat, pickupLon: pickup.lon, dropLat: dest.lat, dropLon: dest.lon });
  }

  const noCard = customer.data && !customer.data.hasPaymentMethod;

  return (
    <>
      <RideMap height={190} pickup={pickup} drop={dest} route={road.data} label="Map of your pickup and drop-off" />
      <Card title="Where to?">
        <Text style={{ color: t.text, fontWeight: "600" }}>Pickup</Text>
        {pickup ? (
          <Text style={{ color: t.text }}>
            {pickup.lat.toFixed(4)}, {pickup.lon.toFixed(4)}{"  "}
            <Text accessibilityRole="button" onPress={() => setPickup(null)} style={{ color: t.accent, textDecorationLine: "underline" }}>
              Change
            </Text>
          </Text>
        ) : (
          <>
            <Button label="Use my location" variant="secondary" busy={locating} onPress={() => void locateMe()} />
            {pickupError ? <ErrorNote>{pickupError}</ErrorNote> : null}
            <Muted>Or enter your pickup point:</Muted>
            <View style={{ flexDirection: "row", gap: 8 }}>
              <View style={{ flex: 1 }}><Field label="Latitude" value={lat} onChangeText={setLat} keyboardType="numbers-and-punctuation" /></View>
              <View style={{ flex: 1 }}><Field label="Longitude" value={lon} onChangeText={setLon} keyboardType="numbers-and-punctuation" /></View>
            </View>
            <Button label="Use this pickup point" variant="secondary" onPress={setByHand} />
          </>
        )}

        <Text style={{ color: t.text, fontWeight: "600" }}>Drop-off</Text>
        <Field label="Search for an address or place" value={term} onChangeText={setTerm} placeholder="e.g. Cubbon Park" returnKeyType="search" onSubmitEditing={runSearch} autoCorrect={false} />
        <Button label="Search" variant="secondary" busy={search.isPending} disabled={term.trim().length < 3} onPress={runSearch} />
        {search.error ? <ErrorNote>Search isn&apos;t available right now. Choose from the popular places below.</ErrorNote> : null}
        {search.data && search.data.length === 0 ? <Muted>Nothing found. Try another name, or choose a popular place.</Muted> : null}
        {search.data && search.data.length > 0 ? (
          <View accessibilityLabel="Search results" style={{ borderWidth: 1, borderColor: t.line, borderRadius: 10, overflow: "hidden" }}>
            {search.data.map((p, i) => (
              <Pressable key={`${p.lat},${p.lon},${i}`} accessibilityRole="button" accessibilityLabel={`${p.name}, ${p.address}`} onPress={() => { setDest(p); search.reset(); }} style={{ minHeight: 44, paddingHorizontal: 12, paddingVertical: 8, borderTopWidth: i === 0 ? 0 : 1, borderColor: t.line }}>
                <Text style={{ color: t.text, fontWeight: "600" }}>{p.name}</Text>
                <Text numberOfLines={1} style={{ color: t.muted, fontSize: 12 }}>{p.address}</Text>
              </Pressable>
            ))}
          </View>
        ) : null}
        <Muted>{`Going to: ${dest.name}`}</Muted>
        <Muted>Popular places:</Muted>
        <View style={{ flexDirection: "row", flexWrap: "wrap", gap: 8 }}>
          {PLACES.map((p) => (
            <Choice key={p.id} role="radio" label={p.name} selected={dest.lat === p.lat && dest.lon === p.lon} onPress={() => setDest(p)} />
          ))}
        </View>

        <Text style={{ color: t.text, fontWeight: "600" }}>Ride type</Text>
        <View style={{ flexDirection: "row", gap: 8 }}>
          {VEHICLES.map(([v, label]) => (
            <Choice key={v} role="radio" label={label} selected={vehicle === v} onPress={() => setVehicle(v)} />
          ))}
        </View>

        {formError ? <ErrorNote>{formError}</ErrorNote> : null}
        {quote.error ? <ErrorNote>{quote.error.message}</ErrorNote> : null}
        <Button label="See fare" busy={quote.isPending} onPress={getFare} />
      </Card>

      {live ? (
        <Card title="Your fare">
          <View style={{ flexDirection: "row", justifyContent: "space-between", alignItems: "baseline" }}>
            <Text style={{ color: t.text, fontSize: 32, fontWeight: "700" }}>{money(live.total, live.currency)}</Text>
            <Muted>{`valid for ${left}s`}</Muted>
          </View>
          <Muted>{`${km(live.distanceKm)} · about ${Math.round(live.durationMin)} min${live.surgeActive ? ` · ${live.surgeMultiplier.toFixed(1)}× busy-time pricing` : ""}`}</Muted>
          {noCard ? (
            <Text style={{ color: t.text }}>
              Add a card to ride.{" "}
              <Link href="/account" style={{ color: t.accent, textDecorationLine: "underline" }}>Add a card</Link>
            </Text>
          ) : null}
          {book.error ? <ErrorNote>{book.error.message}</ErrorNote> : null}
          <Button label="Request ride" busy={book.isPending} disabled={!!noCard} onPress={() => book.mutate(live.quoteId, { onSuccess: () => void registerForPush({ prompt: true }) })} />
        </Card>
      ) : quote.data ? (
        <Muted style={{ textAlign: "center" }}>That fare has expired. Tap See fare for a fresh one.</Muted>
      ) : null}
    </>
  );
}
