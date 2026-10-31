"use client";

import { useState } from "react";
import { Badge, Button, Card, ErrorNote, Stat, StatusBadge } from "@/components/ui";
import { dateTime, money, titleCase } from "@/lib/format";
import { useEarnings, usePayouts, useRequestPayout, useStartOnboarding } from "@/lib/queries";

const PAYOUT_NOTE: Record<string, string> = {
  AWAITING_FUNDS: "Waiting for card payments to settle with our payment provider, which takes a few days. It goes out by itself.",
  PENDING: "On its way to your account.",
  PAID: "Sent to your payout account.",
  FAILED: "This one did not go through. The money is back in your balance.",
  REQUIRES_ATTENTION: "We are checking this one. Your money is safe and reserved.",
};

export function EarningsView() {
  const earnings = useEarnings();
  const payouts = usePayouts();
  const withdraw = useRequestPayout();
  const onboarding = useStartOnboarding();
  const [sent, setSent] = useState<string | null>(null);

  if (earnings.isPending) return <p className="py-10 text-center text-sm text-muted">Loading…</p>;
  if (earnings.isError) return <ErrorNote>{earnings.error.message}</ErrorNote>;
  const e = earnings.data;

  function startOnboarding() {
    onboarding.mutate(undefined, {
      // our provider hosts the identity and bank forms; we never see them
      onSuccess: (r) => window.location.assign(r.url),
    });
  }

  return (
    <>
      {e.payoutBlockedReason ? <ErrorNote>{e.payoutBlockedReason}</ErrorNote> : null}

      <Card>
        <Stat label="Available to withdraw" value={<span className="text-3xl">{money(e.available, e.currency)}</span>} />
        <div className="mt-4 grid grid-cols-2 gap-4">
          <Stat label="On its way to you" value={money(e.payoutsInProgress, e.currency)} />
          <Stat label="On hold" value={money(e.onHold, e.currency)} tone={e.onHold > 0 ? "warn" : undefined} />
        </div>
        {e.onHold > 0 ? <p className="mt-3 text-xs text-muted">Money on hold is waiting for a payment dispute to be decided. It is released or adjusted when it is.</p> : null}

        {withdraw.error ? <div className="mt-4"><ErrorNote>{withdraw.error.message}</ErrorNote></div> : null}
        {onboarding.error ? <div className="mt-4"><ErrorNote>{onboarding.error.message}</ErrorNote></div> : null}
        {sent ? <p role="status" className="mt-4 text-sm">{sent}</p> : null}

        {e.hasPayoutAccount ? (
          <Button
            className="mt-4 w-full"
            busy={withdraw.isPending}
            disabled={e.available <= 0 || !!e.payoutBlockedReason}
            onClick={() =>
              withdraw.mutate(undefined, {
                onSuccess: (p) => setSent(`${money(p.amount, p.currency)}: ${PAYOUT_NOTE[p.status] ?? titleCase(p.status)}`),
              })
            }
          >
            Withdraw {money(e.available, e.currency)}
          </Button>
        ) : (
          <div className="mt-4 space-y-2">
            <p className="text-sm text-muted">To withdraw, set up where we send your money. You&apos;ll give your details to our payment provider, not to us.</p>
            <Button className="w-full" busy={onboarding.isPending} onClick={startOnboarding}>
              Set up payouts
            </Button>
          </div>
        )}
      </Card>

      <Card title="Withdrawals">
        {payouts.isPending ? (
          <p className="text-sm text-muted">Loading…</p>
        ) : (payouts.data ?? []).length === 0 ? (
          <p className="text-sm text-muted">No withdrawals yet.</p>
        ) : (
          <ul className="divide-y divide-line">
            {payouts.data!.map((p) => (
              <li key={p.id} className="py-2.5">
                <div className="flex items-center justify-between gap-2">
                  <span className="font-medium">{money(p.amount, p.currency)}</span>
                  <StatusBadge status={p.status} />
                </div>
                <div className="mt-0.5 text-xs text-muted">
                  {dateTime(p.createdAt)} · {p.failureReason ?? PAYOUT_NOTE[p.status] ?? ""}
                </div>
              </li>
            ))}
          </ul>
        )}
      </Card>

      <Card title="Recent activity">
        {e.recent.length === 0 ? (
          <p className="text-sm text-muted">Nothing yet. Trips you complete show up here.</p>
        ) : (
          <ul className="divide-y divide-line">
            {e.recent.map((l, i) => (
              <li key={`${l.at}-${i}`} className="flex items-center justify-between gap-3 py-2.5">
                <div className="min-w-0">
                  <div className="truncate text-sm">{l.description}</div>
                  <div className="text-xs text-muted">{dateTime(l.at)}</div>
                </div>
                <Badge tone={l.direction === "CREDIT" ? "ok" : "neutral"}>
                  {l.direction === "CREDIT" ? "+" : "−"}
                  {money(l.amount, e.currency)}
                </Badge>
              </li>
            ))}
          </ul>
        )}
      </Card>
    </>
  );
}
