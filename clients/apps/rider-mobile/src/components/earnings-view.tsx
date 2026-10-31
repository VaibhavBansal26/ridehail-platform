import { dateTime, money } from "@ridehail/api";
import { useState } from "react";
import { Linking, Text, View } from "react-native";
import { Badge, Button, Card, ErrorNote, Muted, Stat, StatusBadge, useTheme } from "@/components/ui";
import { useEarnings, usePayouts, useRequestPayout, useStartOnboarding } from "@/lib/queries";

const PAYOUT_NOTE: Record<string, string> = {
  AWAITING_FUNDS: "Waiting for card payments to settle with our payment provider, which takes a few days. It goes out by itself.",
  PENDING: "On its way to your account.",
  PAID: "Sent to your payout account.",
  FAILED: "This one did not go through. The money is back in your balance.",
  REQUIRES_ATTENTION: "We are checking this one. Your money is safe and reserved.",
};

export function EarningsView() {
  const t = useTheme();
  const earnings = useEarnings();
  const payouts = usePayouts();
  const withdraw = useRequestPayout();
  const onboarding = useStartOnboarding();
  const [sent, setSent] = useState<string | null>(null);

  if (earnings.isPending) return <Muted style={{ textAlign: "center", paddingVertical: 40 }}>Loading…</Muted>;
  if (earnings.isError) return <ErrorNote>{earnings.error.message}</ErrorNote>;
  const e = earnings.data;

  return (
    <>
      {e.payoutBlockedReason ? <ErrorNote>{e.payoutBlockedReason}</ErrorNote> : null}

      <Card>
        <Stat label="Available to withdraw" value={money(e.available, e.currency)} big />
        <View style={{ flexDirection: "row", gap: 24 }}>
          <Stat label="On its way to you" value={money(e.payoutsInProgress, e.currency)} />
          <Stat label="On hold" value={money(e.onHold, e.currency)} warn={e.onHold > 0} />
        </View>
        {e.onHold > 0 ? <Muted>Money on hold is waiting for a payment dispute to be decided. It is released or adjusted when it is.</Muted> : null}
        {withdraw.error ? <ErrorNote>{withdraw.error.message}</ErrorNote> : null}
        {onboarding.error ? <ErrorNote>{onboarding.error.message}</ErrorNote> : null}
        {sent ? <Text accessibilityRole="summary" style={{ color: t.text }}>{sent}</Text> : null}

        {e.hasPayoutAccount ? (
          <Button
            label={`Withdraw ${money(e.available, e.currency)}`}
            busy={withdraw.isPending}
            disabled={e.available <= 0 || !!e.payoutBlockedReason}
            onPress={() => withdraw.mutate(undefined, { onSuccess: (p) => setSent(`${money(p.amount, p.currency)}: ${PAYOUT_NOTE[p.status] ?? p.status}`) })}
          />
        ) : (
          <>
            <Muted>To withdraw, set up where we send your money. You&apos;ll give your details to our payment provider, not to us.</Muted>
            {/* our provider hosts the identity and bank forms; we never see them */}
            <Button label="Set up payouts" busy={onboarding.isPending} onPress={() => onboarding.mutate(undefined, { onSuccess: (r) => void Linking.openURL(r.url) })} />
          </>
        )}
      </Card>

      <Card title="Withdrawals">
        {payouts.isPending ? (
          <Muted>Loading…</Muted>
        ) : (payouts.data ?? []).length === 0 ? (
          <Muted>No withdrawals yet.</Muted>
        ) : (
          payouts.data!.map((p) => (
            <View key={p.id} style={{ gap: 2 }}>
              <View style={{ flexDirection: "row", justifyContent: "space-between", alignItems: "center" }}>
                <Text style={{ color: t.text, fontWeight: "600" }}>{money(p.amount, p.currency)}</Text>
                <StatusBadge status={p.status} />
              </View>
              <Muted>{`${dateTime(p.createdAt)} · ${p.failureReason ?? PAYOUT_NOTE[p.status] ?? ""}`}</Muted>
            </View>
          ))
        )}
      </Card>

      <Card title="Recent activity">
        {e.recent.length === 0 ? (
          <Muted>Nothing yet. Trips you complete show up here.</Muted>
        ) : (
          e.recent.map((l, i) => (
            <View key={`${l.at}-${i}`} style={{ flexDirection: "row", justifyContent: "space-between", alignItems: "center", gap: 12 }}>
              <View style={{ flex: 1 }}>
                <Text numberOfLines={1} style={{ color: t.text }}>{l.description}</Text>
                <Muted>{dateTime(l.at)}</Muted>
              </View>
              <Badge tone={l.direction === "CREDIT" ? "ok" : "neutral"}>{`${l.direction === "CREDIT" ? "+" : "−"}${money(l.amount, e.currency)}`}</Badge>
            </View>
          ))
        )}
      </Card>
    </>
  );
}
