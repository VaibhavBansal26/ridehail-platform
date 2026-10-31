"use client";

import Link from "next/link";
import type { ReactNode } from "react";
import { CheckCircle2, ShieldAlert } from "lucide-react";
import { Badge, Card, ErrorBox, Loading, PageHeader, type Tone, useNow } from "@/components/ui";
import { deadline, money, titleCase } from "@/lib/format";
import { useAttention, useFunding, useLive, useTrialBalance } from "@/lib/queries";

function Stat({ label, value, tone = "neutral", href, hint }: { label: string; value: ReactNode; tone?: Tone; href?: string; hint?: string }) {
  const body = (
    <div className="rounded-lg border border-line bg-surface p-4 transition hover:bg-surface-2">
      <div className="text-xs uppercase tracking-wide text-muted">{label}</div>
      <div className="mt-1 flex items-center gap-2 text-2xl font-semibold tabular-nums">
        {value}
        {tone !== "neutral" ? <Badge tone={tone}>{tone === "ok" ? "OK" : "Action"}</Badge> : null}
      </div>
      {hint ? <div className="mt-1 text-xs text-muted">{hint}</div> : null}
    </div>
  );
  return href ? <Link href={href} className="block">{body}</Link> : body;
}

export default function OverviewPage() {
  const attention = useAttention();
  const funding = useFunding();
  const ledger = useTrialBalance();
  const live = useLive();
  const now = useNow();

  if (attention.isPending) return <Loading />;
  if (attention.isError) return <ErrorBox error={attention.error} onRetry={() => attention.refetch()} />;
  const a = attention.data;
  const nextDeadline = deadline(a.disputes.earliestEvidenceDueBy, now);
  const short = funding.data && funding.data.providerAvailable !== null && funding.data.providerAvailable < funding.data.awaitingTotal;

  return (
    <>
      <PageHeader title="Overview" subtitle="What needs a person right now, and how the platform is doing." />

      <div
        role="status"
        className={`mb-6 flex items-center gap-3 rounded-lg border border-line p-4 text-sm ${a.needsAction ? "bg-[var(--bad-bg)] text-[var(--bad-fg)]" : "bg-[var(--ok-bg)] text-[var(--ok-fg)]"}`}
      >
        {a.needsAction ? <ShieldAlert className="size-5" aria-hidden /> : <CheckCircle2 className="size-5" aria-hidden />}
        <span className="font-medium">{a.needsAction ? "Needs attention" : "All clear. Nothing is waiting for a person."}</span>
      </div>

      <h2 className="mb-2 text-sm font-medium text-muted">Needs attention</h2>
      <div className="mb-6 grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
        <Stat
          label="Open disputes" value={a.disputes.open} href="/disputes" tone={a.disputes.open > 0 ? "bad" : "ok"}
          hint={a.disputes.open > 0 ? `${money(a.disputes.openAmount)} in dispute · ${nextDeadline.label}` : `${a.disputes.inquiries} inquiries`}
        />
        <Stat
          label="Past evidence deadline" value={a.disputes.overdue} href="/disputes" tone={a.disputes.overdue > 0 ? "bad" : "ok"}
          hint={a.disputes.overdue > 0 ? "Answer these in Disputes now" : "No missed deadlines"}
        />
        <Stat
          label="Open incidents" value={a.incidents.open} href="/incidents" tone={a.incidents.open > 0 ? "bad" : "ok"}
          hint={Object.entries(a.incidents.byType).map(([t, n]) => `${n} ${titleCase(t)}`).join(" · ") || "None"}
        />
        <Stat
          label="Payments & payouts stuck" value={a.paymentsRequiringAttention + a.payoutsRequiringAttention} href="/payments"
          tone={a.paymentsRequiringAttention + a.payoutsRequiringAttention > 0 ? "bad" : "ok"}
          hint={`${a.paymentsRequiringAttention} payments · ${a.payoutsRequiringAttention} payouts`}
        />
      </div>

      <div className="grid gap-4 lg:grid-cols-3">
        <Card title="Money" className="lg:col-span-1">
          <dl className="space-y-3 text-sm">
            <div className="flex justify-between"><dt className="text-muted">Rider funds on hold (disputes)</dt><dd className="tabular-nums">{money(a.riderFundsOnHold)}</dd></div>
            <div className="flex justify-between"><dt className="text-muted">Payouts waiting for funds</dt><dd className="tabular-nums">{a.payoutsAwaitingFunds}</dd></div>
            {funding.data ? (
              <>
                <div className="flex justify-between">
                  <dt className="text-muted">Available at provider</dt>
                  <dd className="tabular-nums">{funding.data.providerAvailable === null ? "Not reported" : money(funding.data.providerAvailable, funding.data.currency)}</dd>
                </div>
                <div className="flex justify-between"><dt className="text-muted">Owed to riders, waiting</dt><dd className="tabular-nums">{money(funding.data.awaitingTotal, funding.data.currency)} ({funding.data.awaitingCount})</dd></div>
                {short ? <p className="rounded-md bg-[var(--warn-bg)] p-2 text-[var(--warn-fg)]">Available funds are lower than what riders are waiting for. Payouts will wait until card funds clear.</p> : null}
              </>
            ) : funding.isError ? <ErrorBox error={funding.error} /> : null}
            <div className="flex items-center justify-between border-t border-line pt-3">
              <dt className="text-muted">Ledger</dt>
              <dd>
                {ledger.data ? (
                  <Link href="/ledger"><Badge tone={ledger.data.balanced ? "ok" : "bad"}>{ledger.data.balanced ? "Balanced" : "OUT OF BALANCE"}</Badge></Link>
                ) : "—"}
              </dd>
            </div>
          </dl>
        </Card>

        <Card title="On the road now" className="lg:col-span-2" action={<Link href="/map" className="text-sm text-accent hover:underline">Open live map</Link>}>
          {live.isPending ? <Loading /> : live.isError ? <ErrorBox error={live.error} /> : (
            <div className="grid gap-6 sm:grid-cols-2">
              <div>
                <div className="mb-2 text-xs uppercase tracking-wide text-muted">Riders</div>
                <ul className="space-y-1.5 text-sm">
                  {Object.entries(live.data.riderCounts).map(([k, n]) => (
                    <li key={k} className="flex justify-between"><span>{titleCase(k)}</span><span className="tabular-nums">{n}</span></li>
                  ))}
                </ul>
              </div>
              <div>
                <div className="mb-2 text-xs uppercase tracking-wide text-muted">Active trips</div>
                <ul className="space-y-1.5 text-sm">
                  {Object.entries(live.data.tripCounts).map(([k, n]) => (
                    <li key={k} className="flex justify-between"><span>{titleCase(k)}</span><span className="tabular-nums">{n}</span></li>
                  ))}
                </ul>
              </div>
            </div>
          )}
        </Card>
      </div>
    </>
  );
}
