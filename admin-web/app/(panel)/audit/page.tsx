"use client";

import { useState } from "react";
import { DataTable, Empty, ErrorBox, Loading, PageHeader, Pager, Select, Td, useNow } from "@/components/ui";
import { dateTime, relative, titleCase } from "@/lib/format";
import { PAGE_SIZE, useAudit } from "@/lib/queries";

const ACTIONS = ["TRIP_CANCELLED", "PAYMENT_RETRIED", "PAYMENT_REFUNDED", "INCIDENT_RESOLVED", "ADMIN_CREATED", "ADMIN_DISABLED", "ADMIN_ENABLED",
  "VERIFICATION_APPROVED", "VERIFICATION_REJECTED", "VERIFICATION_SUSPENDED", "VERIFICATION_REINSTATED", "VERIFICATION_DOCUMENT_VIEWED",
  "DISPUTE_EVIDENCE_SAVED", "DISPUTE_EVIDENCE_SUBMITTED", "SUPPORT_REPLIED", "SUPPORT_RESOLVED"];

export default function AuditPage() {
  const [action, setAction] = useState("");
  const [page, setPage] = useState(0);
  const audit = useAudit(action, page);
  const now = useNow();

  return (
    <>
      <PageHeader
        title="Audit log" subtitle="Everything an administrator changed, and who did it. Entries cannot be edited or deleted."
        actions={<Select label="Action" value={action} onChange={(v) => { setAction(v); setPage(0); }} options={[{ value: "", label: "All" }, ...ACTIONS.map((a) => ({ value: a, label: titleCase(a) }))]} />}
      />
      {audit.isPending ? <Loading /> : audit.isError ? <ErrorBox error={audit.error} onRetry={() => audit.refetch()} /> : audit.data.items.length === 0 ? <Empty>Nothing has been recorded yet.</Empty> : (
        <>
          <DataTable caption="Admin actions" head={["When", "Who", "Action", "What"]}>
            {audit.data.items.map((a) => (
              <tr key={a.id} className="align-top hover:bg-surface-2">
                <Td title={dateTime(a.createdAt)}>{relative(a.createdAt, now)}</Td>
                <Td className="font-medium">{a.actorPhone}</Td>
                <Td>{titleCase(a.action)}</Td>
                <Td className="max-w-96">
                  {a.detail}
                  {a.targetId ? <div className="font-mono text-xs text-muted">{a.targetType} {a.targetId.slice(0, 8)}</div> : null}
                </Td>
              </tr>
            ))}
          </DataTable>
          <Pager page={page} size={PAGE_SIZE} total={audit.data.total} onPage={setPage} />
        </>
      )}
    </>
  );
}
