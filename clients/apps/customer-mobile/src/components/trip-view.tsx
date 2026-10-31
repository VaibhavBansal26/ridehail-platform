import { distanceKm, km, money, titleCase, type Trip } from "@ridehail/api";
import { Text } from "react-native";
import { Button, Card, ErrorNote, Muted, useTheme } from "@/components/ui";
import { useCancel, useRiderLocation } from "@/lib/queries";

/** The trip as it stands: finding a rider, who is coming and how far away they are, or the ride itself. */
export function TripView({ trip }: { trip: Trip }) {
  const t = useTheme();
  const cancel = useCancel();
  const tracking = trip.status === "ACCEPTED" || trip.status === "ARRIVED" || trip.status === "IN_PROGRESS";
  const where = useRiderLocation(trip.id, tracking);
  const rider = trip.riderName ?? "Your rider";

  let title = "Finding you a rider…";
  let detail = "We are asking the nearest riders. This usually takes under a minute.";
  if (tracking) {
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
      <Muted>{`${titleCase(trip.status).toUpperCase()} · ${money(trip.fare, trip.currency)}`}</Muted>
      <Text accessibilityRole="header" style={{ color: t.text, fontSize: 22, fontWeight: "700" }}>{title}</Text>
      <Muted>{detail}</Muted>
      {trip.riderName ? <Text style={{ color: t.text }}>{`${trip.riderName}${trip.riderVehicleType ? ` · ${titleCase(trip.riderVehicleType)}` : ""}`}</Text> : null}
      {cancel.error ? <ErrorNote>{cancel.error.message}</ErrorNote> : null}
      {canCancel ? <Button label="Cancel ride" variant="secondary" busy={cancel.isPending} onPress={() => cancel.mutate(trip.id)} /> : null}
    </Card>
  );
}
