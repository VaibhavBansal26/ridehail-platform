import { directionsUrl, money, type Trip } from "@ridehail/api";
import { Linking, Text } from "react-native";
import { Button, Card, ErrorNote, Muted, useTheme } from "@/components/ui";
import { useTripStep } from "@/lib/queries";

const STEP = {
  ACCEPTED: { title: "Head to the pickup", button: "I've arrived", step: "arrive", target: "pickup" },
  ARRIVED: { title: "Waiting for the customer", button: "Start trip", step: "start", target: "pickup" },
  IN_PROGRESS: { title: "Drive to the drop-off", button: "Complete trip", step: "complete", target: "drop" },
} as const;

/** The trip the rider is on: where to go next, and the one button that moves it along. */
export function TripPanel({ trip, onCompleted }: { trip: Trip; onCompleted: (trip: Trip) => void }) {
  const t = useTheme();
  const step = useTripStep();
  const current = STEP[trip.status as keyof typeof STEP];
  if (!current) return null;
  const dest = current.target === "pickup" ? { lat: trip.pickupLat, lon: trip.pickupLon } : { lat: trip.dropLat, lon: trip.dropLon };

  return (
    <Card>
      <Muted>{`CURRENT TRIP · ${money(trip.fare, trip.currency)}`}</Muted>
      <Text accessibilityRole="header" style={{ color: t.text, fontSize: 22, fontWeight: "700" }}>
        {current.title}
      </Text>
      <Button label={`Navigate to ${current.target === "pickup" ? "pickup" : "drop-off"}`} variant="secondary" onPress={() => void Linking.openURL(directionsUrl(dest.lat, dest.lon))} />
      {step.error ? <ErrorNote>{step.error.message}</ErrorNote> : null}
      <Button
        label={current.button}
        busy={step.isPending}
        onPress={() => step.mutate({ tripId: trip.id, step: current.step }, { onSuccess: (done) => done.status === "COMPLETED" && onCompleted(done) })}
      />
    </Card>
  );
}
