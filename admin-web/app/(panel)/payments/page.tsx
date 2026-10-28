"use client";

import { useState } from "react";
import { Badge, Button, ConfirmDialog, DataTable, Empty, ErrorBox, IdLink, Loading, PageHeader, Pager, Select, StatusBadge, Td, useNow } from "@/components/ui";
import { dateTime, money, relative, titleCase } from "@/lib/format";
import { PAGE_SIZE, useFunding, usePayments, usePayouts, useRefundPayment, useRetryPayment } from "@/lib/queries";
import { PaymentStatus, PayoutStatus, type Payment } from "@/lib/schemas";

type Tab = "payments" | "payouts";

export default function PaymentsPage() {
  const [tab, setTab] = useState<Tab>("payments");
  return (
    <>
      <PageHeader title="Payments" subtitle="What customers were charged and what riders were paid." />
      <div role="tablist" aria-label="Payments or payouts" className="mb-4 flex gap-1 border-b border-line">
        {(["payments", "payouts"] as const).map((t) => (
          <button
            key={t} role="tab" aria-selected={tab === t} onClick={() => setTab(t)}
            className={`-mb-px border-b-2 px-4 py-2 text-sm ${tab === t ? "border-accent font-medium" : "border-transparent text-muted hover:text-ink"}`}
          >
            {titleCase(t)}
          </button>
        ))}
      </div>
      {tab === "payments" ? <PaymentsTab /> : <PayoutsTab />}
    </>
  );
}

function PaymentsTab() {
  const [status, setStatus] = useState("");
  const [page, setPage] = useState(0);
  const payments = usePayments(status, page);
  const retry = useRetryPayment();
  const refund = useRefundPayment();
  const [action, setAction] = useState<{ kind: "retry" | "refund"; payment: Payment } | null>(null);
  const busy = retry.isPending || refund.isPending;
  const error = retry.error ?? refund.error;

  function close() {
    setAction(null);
    retry.reset();
    refund.reset();
  }

  return (
    <>
      <div className="mb-3">
        <Select label="Status" value={status} onChange={(v) => { setStatus(v); setPage(0); }} options={[{ value: "", label: "All" }, ...PaymentStatus.options.map((s) => ({ value: s, label: titleCase(s) }))]} />
      </div>
      {payments.isPending ? <Loading /> : payments.isError ? <ErrorBox error={payments.error} onRetry={() => payments.refetch()} /> : payments.data.items.length === 0 ? <Empty>No payments{status ? ` that are ${titleCase(status)}` : ""}.</Empty> : (
        <>
          <DataTable caption="Payments" head={["Trip", "Status", "Charged", "Commission", "Rider earns", "Attempts", "Problem", ""]}>
            {payments.data.items.map((p) => (
              <tr key={p.id} className="hover:bg-surface-2">
                <Td><IdLink href={`/trips/${p.tripId}`} id={p.tripId} /></Td>
                <Td><StatusBadge status={p.status} /></Td>
                <Td className="tabular-nums">{money(p.amount, p.currency)}</Td>
                <Td className="tabular-nums">{money(p.commission, p.currency)}</Td>
                <Td className="tabular-nums">{money(p.riderAmount, p.currency)}</Td>
                <Td className="tabular-nums">{p.attempts}</Td>
                <Td className="max-w-56 truncate text-[var(--bad-fg)]" >{p.failureReason ?? ""}</Td>
                <Td className="text-right whitespace-nowrap">
                  {p.status === "REQUIRES_ATTENTION" || p.status === "PENDING" ? <Button onClick={() => setAction({ kind: "retry", payment: p })}>Retry</Button> : null}{" "}
                  {p.status === "CAPTURED" ? <Button onClick={() => setAction({ kind: "refund", payment: p })}>Refund</Button> : null}
                </Td>
              </tr>
            ))}
          </DataTable>
          <Pager page={page} size={PAGE_SIZE} total={payments.data.total} onPage={setPage} />
        </>
      )}

      <ConfirmDialog
        open={action !== null} title={action?.kind === "refund" ? "Refund this payment?" : "Retry this charge?"}
        confirmLabel={action?.kind === "refund" ? "Refund" : "Retry charge"} danger={action?.kind === "refund"} busy={busy} error={error}
        onCancel={close}
        onConfirm={() => action && (action.kind === "refund" ? refund : retry).mutate(action.payment.id, { onSuccess: close })}
      >
        {action?.kind === "refund"
          ? `The customer gets ${money(action.payment.amount, action.payment.currency)} back and the rider's earnings for this trip are reversed.`
          : `The customer's card is charged ${action ? money(action.payment.amount, action.payment.currency) : ""} again. It is safe to repeat: the provider ignores duplicates.`}
      </ConfirmDialog>
    </>
  );
}

function PayoutsTab() {
  const [status, setStatus] = useState("");
  const [page, setPage] = useState(0);
  const payouts = usePayouts(status, page);
  const funding = useFunding();
  const now = useNow();

  return (
    <>
      {funding.data ? (
        <div className="mb-4 flex flex-wrap gap-x-8 gap-y-1 rounded-lg border border-line bg-surface p-3 text-sm">
          <span><span className="text-muted">Available at provider </span>{funding.data.providerAvailable === null ? "not reported" : money(funding.data.providerAvailable, funding.data.currency)}</span>
          <span><span className="text-muted">Waiting for funds </span>{money(funding.data.awaitingTotal, funding.data.currency)} ({funding.data.awaitingCount})</span>
        </div>
      ) : null}
      <div className="mb-3">
        <Select label="Status" value={status} onChange={(v) => { setStatus(v); setPage(0); }} options={[{ value: "", label: "All" }, ...PayoutStatus.options.map((s) => ({ value: s, label: titleCase(s) }))]} />
      </div>
      {payouts.isPending ? <Loading /> : payouts.isError ? <ErrorBox error={payouts.error} onRetry={() => payouts.refetch()} /> : payouts.data.items.length === 0 ? <Empty>No payouts{status ? ` that are ${titleCase(status)}` : ""}.</Empty> : (
        <>
          <DataTable caption="Payouts" head={["Payout", "Status", "Amount", "Attempts", "Waiting since", "Problem", "Created"]}>
            {payouts.data.items.map((p) => (
              <tr key={p.id} className="hover:bg-surface-2">
                <Td className="font-mono text-xs">{p.id.slice(0, 8)}</Td>
                <Td><StatusBadge status={p.status} /></Td>
                <Td className="tabular-nums">{money(p.amount)}</Td>
                <Td className="tabular-nums">{p.attempts}</Td>
                <Td>{p.waitingSince ? <Badge tone="warn">{relative(p.waitingSince, now)}</Badge> : "—"}</Td>
                <Td className="max-w-56 truncate text-[var(--bad-fg)]">{p.failureReason ?? ""}</Td>
                <Td title={dateTime(p.createdAt)}>{relative(p.createdAt, now)}</Td>
              </tr>
            ))}
          </DataTable>
          <Pager page={page} size={PAGE_SIZE} total={payouts.data.total} onPage={setPage} />
        </>
      )}
    </>
  );
}
