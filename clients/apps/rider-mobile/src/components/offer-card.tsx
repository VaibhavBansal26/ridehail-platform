import { km, money, type Offer, secondsUntil } from "@ridehail/api";
import { useEffect, useState } from "react";
import { Text, View } from "react-native";
import { Button, Card, ErrorNote, Muted, useTheme } from "@/components/ui";
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
  const t = useTheme();
  const left = useSecondsLeft(offer.expiresAt);
  const answer = useOfferAnswer();
  if (left === 0) return null; // the server expires it too; hiding it now avoids a tap that can only fail

  return (
    <Card>
      <View style={{ flexDirection: "row", justifyContent: "space-between", alignItems: "flex-start" }}>
        <View>
          <Text style={{ color: t.text, fontSize: 26, fontWeight: "700" }}>{money(offer.fare, offer.currency)}</Text>
          <Muted>
            {km(offer.distanceToPickupKm)} to pickup{offer.surgeMultiplier > 1 ? ` · ${offer.surgeMultiplier.toFixed(1)}× surge` : ""}
          </Muted>
        </View>
        <View accessibilityLabel={`${left} seconds left`} style={{ backgroundColor: t.warnBg, borderRadius: 999, paddingHorizontal: 12, paddingVertical: 4 }}>
          <Text style={{ color: t.warnFg, fontWeight: "700" }}>{left}s</Text>
        </View>
      </View>
      {answer.error ? <ErrorNote>{answer.error.message}</ErrorNote> : null}
      <View style={{ flexDirection: "row", gap: 8 }}>
        <View style={{ flex: 1 }}>
          <Button label="Decline" variant="secondary" disabled={answer.isPending} onPress={() => answer.mutate({ offerId: offer.offerId, accept: false })} />
        </View>
        <View style={{ flex: 1 }}>
          <Button label="Accept" busy={answer.isPending} onPress={() => answer.mutate({ offerId: offer.offerId, accept: true })} />
        </View>
      </View>
    </Card>
  );
}
