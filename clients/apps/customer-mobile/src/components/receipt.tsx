import { money, titleCase, type Trip } from "@ridehail/api";
import { Link } from "expo-router";
import { Text, View } from "react-native";
import { Button, Card, Muted, StatusBadge, useTheme } from "@/components/ui";

/** What happened to the trip that just ended: a receipt with how the payment stands, or why it did not happen. */
export function Receipt({ trip, onDone }: { trip: Trip; onDone: () => void }) {
  const t = useTheme();
  if (trip.status === "CANCELLED" || trip.status === "NO_RIDERS_AVAILABLE") {
    return (
      <Card>
        <Text accessibilityRole="header" style={{ color: t.text, fontSize: 22, fontWeight: "700" }}>{trip.status === "CANCELLED" ? "Ride cancelled" : "No riders available"}</Text>
        <Muted>{trip.status === "CANCELLED" ? "You have not been charged." : "No rider was free nearby. You have not been charged. Try again in a moment."}</Muted>
        <Button label="Done" variant="secondary" onPress={onDone} />
      </Card>
    );
  }
  const failed = trip.paymentStatus === "REQUIRES_ATTENTION";
  return (
    <Card>
      <Text accessibilityRole="header" style={{ color: t.text, fontSize: 22, fontWeight: "700" }}>Trip complete</Text>
      <View style={{ flexDirection: "row", justifyContent: "space-between", alignItems: "center" }}>
        <Text style={{ color: t.text, fontSize: 26, fontWeight: "700" }}>{money(trip.fare, trip.currency)}</Text>
        {trip.paymentStatus ? <StatusBadge status={trip.paymentStatus} /> : null}
      </View>
      <Muted>
        {failed
          ? "We could not charge your card. Update your card, and we will try again."
          : trip.paymentStatus === "CAPTURED"
            ? "Paid. Thanks for riding."
            : `Your card is being charged${trip.paymentStatus ? ` (${titleCase(trip.paymentStatus)})` : ""}.`}
      </Muted>
      {failed ? <Link href="/account" style={{ color: t.accent, fontWeight: "600", textDecorationLine: "underline" }}>Update card</Link> : null}
      <Button label="Done" variant="secondary" onPress={onDone} />
    </Card>
  );
}
