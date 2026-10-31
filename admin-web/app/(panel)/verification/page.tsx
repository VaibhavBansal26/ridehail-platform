"use client";

import Link from "next/link";
import { useState } from "react";
import { Badge, DataTable, Empty, ErrorBox, Loading, PageHeader, Pager, Select, Td, useNow } from "@/components/ui";
import { relative, titleCase } from "@/lib/format";
import { PAGE_SIZE, useVerificationQueue } from "@/lib/queries";

const STATUSES = ["PENDING_REVIEW", "APPROVED", "REJECTED", "SUSPENDED", "EXPIRED", "NOT_SUBMITTED"];
const TONE: Record<string, "ok" | "warn" | "bad" | "neutral" | "info"> = {
  PENDING_REVIEW: "warn", APPROVED: "ok", REJECTED: "bad", SUSPENDED: "bad", EXPIRED: "bad", NOT_SUBMITTED: "neutral",
};

export default function VerificationPage() {
  const [status, setStatus] = useState("PENDING_REVIEW");
  const [page, setPage] = useState(0);
  const q = useVerificationQueue(status, page);
  const now = useNow();

  return (
    <>
      <PageHeader
        title="Rider verification"
        subtitle="Riders cannot go online until their documents are approved. Oldest waiting first. Opening a document is recorded against your name."
        actions={<Select label="Status" value={status} onChange={(v) => { setStatus(v); setPage(0); }} options={STATUSES.map((s) => ({ value: s, label: titleCase(s) }))} />}
      />
      {q.isPending ? <Loading /> : q.isError ? <ErrorBox error={q.error} onRetry={() => q.refetch()} /> : q.data.items.length === 0 ? (
        <Empty>{status === "PENDING_REVIEW" ? "Nobody is waiting for a review." : "No riders in this state."}</Empty>
      ) : (
        <>
          <DataTable caption="Riders by verification status" head={["Rider", "Phone", "Vehicle", "Status", "Waiting", "Documents", ""]}>
            {q.data.items.map((r) => (
              <tr key={r.riderId}>
                <Td className="font-medium">{r.name}</Td>
                <Td>{r.phone}</Td>
                <Td>{titleCase(r.vehicleType)}</Td>
                <Td><Badge tone={TONE[r.status] ?? "neutral"}>{titleCase(r.status)}</Badge></Td>
                <Td>{r.status === "PENDING_REVIEW" ? relative(r.submittedAt, now) : "—"}</Td>
                <Td>{r.documents} of 4</Td>
                <Td className="text-right"><Link href={`/verification/${r.riderId}`} className="text-accent underline">Review</Link></Td>
              </tr>
            ))}
          </DataTable>
          <Pager page={page} size={PAGE_SIZE} total={q.data.total} onPage={setPage} />
        </>
      )}
    </>
  );
}
