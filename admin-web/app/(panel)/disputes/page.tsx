"use client";

import { useState } from "react";
import { ExternalLink } from "lucide-react";
import { Badge, DataTable, Empty, ErrorBox, IdLink, Loading, PageHeader, Pager, Select, StatusBadge, Td, useNow } from "@/components/ui";
import { dateTime, deadline, money, safeProviderUrl, titleCase } from "@/lib/format";
import { PAGE_SIZE, useDisputes } from "@/lib/queries";
import { DisputeStatus } from "@/lib/schemas";

export default function DisputesPage() {
  const [status, setStatus] = useState("");
  const [page, setPage] = useState(0);
  const disputes = useDisputes(status, page);
  const now = useNow();

  return (
    <>
      <PageHeader
        title="Disputes" subtitle="Chargebacks and inquiries. Evidence is submitted in the payment provider's dashboard; this page tells you which ones are urgent."
        actions={<Select label="Status" value={status} onChange={(v) => { setStatus(v); setPage(0); }} options={[{ value: "", label: "All" }, ...DisputeStatus.options.map((s) => ({ value: s, label: titleCase(s) }))]} />}
      />
      {disputes.isPending ? <Loading /> : disputes.isError ? <ErrorBox error={disputes.error} onRetry={() => disputes.refetch()} /> : disputes.data.items.length === 0 ? <Empty>No disputes{status ? ` that are ${titleCase(status)}` : ""}.</Empty> : (
        <>
          <DataTable caption="Disputes" head={["Dispute", "Status", "Amount", "Reason", "Evidence due", "Ledger", "Trip"]}>
            {disputes.data.items.map((d) => {
              const due = deadline(d.evidenceDueBy, now);
              const unanswered = d.status === "OPEN" || d.status === "INQUIRY";
              const url = safeProviderUrl(d.providerUrl);
              return (
                <tr key={d.id} className="align-top hover:bg-surface-2">
                  <Td>
                    <div className="font-mono text-xs">{d.providerId}</div>
                    <div className="text-xs text-muted">{dateTime(d.createdAt)}</div>
                    {url && unanswered ? <a href={url} target="_blank" rel="noopener noreferrer" className="mt-1 inline-flex items-center gap-1 text-sm text-accent hover:underline">Respond <ExternalLink className="size-3.5" aria-hidden /><span className="sr-only">(opens the payment dashboard)</span></a> : null}
                  </Td>
                  <Td><StatusBadge status={d.status} /></Td>
                  <Td className="tabular-nums">{money(d.amount, d.currency)}</Td>
                  <Td>{d.reason ? titleCase(d.reason) : "—"}</Td>
                  <Td>
                    {unanswered ? <Badge tone={due.overdue ? "bad" : due.soon ? "warn" : "neutral"}>{due.label}</Badge> : <span className="text-muted">{d.resolvedAt ? `Closed ${dateTime(d.resolvedAt)}` : "—"}</span>}
                  </Td>
                  <Td className="max-w-64">
                    {!d.matched ? <Badge tone="warn">Not matched to a payment</Badge> : d.frozen ? (unanswered ? <Badge tone="info">Earnings frozen {money(d.riderHeldAmount, d.currency)}</Badge> : <span className="text-muted">Held {money(d.riderHeldAmount, d.currency)} while open</span>) : <span className="text-muted">Nothing frozen</span>}
                    {d.feeBooked > 0 ? <div className="mt-1 text-xs text-muted">Fee booked {money(d.feeBooked, d.currency)}</div> : null}
                    {d.ledgerNote ? <div className="mt-1 text-xs text-muted">{d.ledgerNote}</div> : null}
                  </Td>
                  <Td>{d.tripId ? <IdLink href={`/trips/${d.tripId}`} id={d.tripId} /> : "—"}</Td>
                </tr>
              );
            })}
          </DataTable>
          <Pager page={page} size={PAGE_SIZE} total={disputes.data.total} onPage={setPage} />
        </>
      )}
    </>
  );
}
