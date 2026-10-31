"use client";

import Link from "next/link";
import { useState } from "react";
import { Badge, DataTable, Empty, ErrorBox, Loading, PageHeader, Pager, Select, Td, useNow } from "@/components/ui";
import { relative, titleCase } from "@/lib/format";
import { PAGE_SIZE, useSupportInbox } from "@/lib/queries";

const FILTERS = [
  { value: "awaiting", label: "Waiting for a reply" },
  { value: "open", label: "Open" },
  { value: "resolved", label: "Resolved" },
  { value: "all", label: "All" },
];

export default function SupportInboxPage() {
  const [filter, setFilter] = useState("awaiting");
  const [page, setPage] = useState(0);
  const q = useSupportInbox(filter, page);
  const now = useNow();

  return (
    <>
      <PageHeader
        title="Support"
        subtitle="Customers and riders write from their apps. Whoever has waited longest is first."
        actions={<Select label="Show" value={filter} onChange={(v) => { setFilter(v); setPage(0); }} options={FILTERS} />}
      />
      {q.isPending ? <Loading /> : q.isError ? <ErrorBox error={q.error} onRetry={() => q.refetch()} /> : q.data.items.length === 0 ? (
        <Empty>{filter === "awaiting" ? "Nobody is waiting for a reply." : "No conversations here."}</Empty>
      ) : (
        <>
          <DataTable caption="Support conversations" head={["Person", "Role", "Last message", "Status", "Waiting", ""]}>
            {q.data.items.map((c) => (
              <tr key={c.id} className="align-top">
                <Td className="font-medium">{c.name ?? "Unknown"}<div className="text-xs font-normal text-muted">{c.phone}</div></Td>
                <Td>{titleCase(c.role)}</Td>
                <Td className="max-w-80 truncate">{c.lastPreview}<div className="text-xs text-muted">{relative(c.lastMessageAt, now)}</div></Td>
                <Td><Badge tone={c.awaitingReply ? "warn" : c.status === "RESOLVED" ? "neutral" : "ok"}>{c.awaitingReply ? "Needs reply" : titleCase(c.status)}</Badge></Td>
                <Td>{c.awaitingReply ? relative(c.awaitingSince, now) : "—"}</Td>
                <Td className="text-right"><Link href={`/support/${c.id}`} className="text-accent underline">Open</Link></Td>
              </tr>
            ))}
          </DataTable>
          <Pager page={page} size={PAGE_SIZE} total={q.data.total} onPage={setPage} />
        </>
      )}
    </>
  );
}
