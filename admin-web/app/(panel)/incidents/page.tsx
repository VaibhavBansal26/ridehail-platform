"use client";

import { useState } from "react";
import { Button, ConfirmDialog, DataTable, Empty, ErrorBox, Loading, PageHeader, Pager, Select, StatusBadge, Td, useNow } from "@/components/ui";
import { dateTime, money, relative, titleCase } from "@/lib/format";
import { PAGE_SIZE, useIncidents, useResolveIncident } from "@/lib/queries";
import { IncidentType, type Incident } from "@/lib/schemas";

const NOTE_LIMIT = 500;

export default function IncidentsPage() {
  const [status, setStatus] = useState("OPEN");
  const [type, setType] = useState("");
  const [page, setPage] = useState(0);
  const incidents = useIncidents(status, type, page);
  const resolve = useResolveIncident();
  const [target, setTarget] = useState<Incident | null>(null);
  const [note, setNote] = useState("");
  const now = useNow();

  function close() {
    setTarget(null);
    setNote("");
    resolve.reset();
  }

  return (
    <>
      <PageHeader
        title="Incidents" subtitle="Money problems the payment provider reported that a person has to look at: failed bank payouts, reversed transfers, failed refunds."
        actions={
          <>
            <Select label="Status" value={status} onChange={(v) => { setStatus(v); setPage(0); }} options={[{ value: "OPEN", label: "Open" }, { value: "RESOLVED", label: "Resolved" }, { value: "", label: "All" }]} />
            <Select label="Type" value={type} onChange={(v) => { setType(v); setPage(0); }} options={[{ value: "", label: "All" }, ...IncidentType.options.map((s) => ({ value: s, label: titleCase(s) }))]} />
          </>
        }
      />
      {incidents.isPending ? <Loading /> : incidents.isError ? <ErrorBox error={incidents.error} onRetry={() => incidents.refetch()} /> : incidents.data.items.length === 0 ? <Empty>{status === "OPEN" ? "No open incidents. Nothing is waiting for a person." : "No incidents match."}</Empty> : (
        <>
          <DataTable caption="Incidents" head={["Type", "Status", "Amount", "What happened", "Raised", ""]}>
            {incidents.data.items.map((i) => (
              <tr key={i.id} className="align-top hover:bg-surface-2">
                <Td><div className="font-medium">{titleCase(i.type)}</div><div className="font-mono text-xs text-muted">{i.providerRef}</div></Td>
                <Td><StatusBadge status={i.status} /></Td>
                <Td className="tabular-nums">{i.amount !== null ? money(i.amount, i.currency ?? "USD") : "—"}</Td>
                <Td className="max-w-80">
                  {i.detail}
                  {i.resolutionNote ? <div className="mt-1 text-xs text-muted">Resolved: {i.resolutionNote}</div> : null}
                </Td>
                <Td title={dateTime(i.createdAt)}>{relative(i.createdAt, now)}</Td>
                <Td className="text-right">{i.status === "OPEN" ? <Button onClick={() => setTarget(i)}>Resolve</Button> : null}</Td>
              </tr>
            ))}
          </DataTable>
          <Pager page={page} size={PAGE_SIZE} total={incidents.data.total} onPage={setPage} />
        </>
      )}

      <ConfirmDialog
        open={target !== null} title="Mark as resolved" confirmLabel="Mark resolved" busy={resolve.isPending} error={resolve.error} onCancel={close}
        onConfirm={() => target && resolve.mutate({ id: target.id, note: note.trim() }, { onSuccess: close })}
      >
        <p>This only records that someone dealt with it. It does not move any money. Say what was done so the next person knows.</p>
        <label className="mt-3 block text-ink">
          <span className="text-sm font-medium">What was done</span>
          <textarea
            value={note} onChange={(e) => setNote(e.target.value.slice(0, NOTE_LIMIT))} rows={3}
            className="mt-1 block w-full rounded-md border border-line bg-surface px-3 py-2 text-sm"
          />
          <span className="text-xs text-muted">{note.length}/{NOTE_LIMIT}</span>
        </label>
      </ConfirmDialog>
    </>
  );
}
