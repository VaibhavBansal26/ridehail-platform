"use client";

import { useState } from "react";
import { DataTable, Empty, ErrorBox, Loading, PageHeader, Pager, Select, StatusBadge, Td, useNow } from "@/components/ui";
import { relative, titleCase } from "@/lib/format";
import { PAGE_SIZE, useRiders } from "@/lib/queries";
import { RiderStatus } from "@/lib/schemas";

/** A position older than this on an online rider means their app has stopped reporting. */
const STALE_MS = 2 * 60_000;

export default function RidersPage() {
  const [status, setStatus] = useState("");
  const [page, setPage] = useState(0);
  const riders = useRiders(status, page);
  const now = useNow();

  return (
    <>
      <PageHeader
        title="Riders" subtitle="Sorted by name."
        actions={<Select label="Status" value={status} onChange={(v) => { setStatus(v); setPage(0); }} options={[{ value: "", label: "All" }, ...RiderStatus.options.map((s) => ({ value: s, label: titleCase(s) }))]} />}
      />
      {riders.isPending ? <Loading /> : riders.isError ? <ErrorBox error={riders.error} onRetry={() => riders.refetch()} /> : riders.data.items.length === 0 ? <Empty>No riders{status ? ` who are ${titleCase(status)}` : ""}.</Empty> : (
        <>
          <DataTable caption="Riders" head={["Name", "Status", "Vehicle", "Rating", "Last position", "Location"]}>
            {riders.data.items.map((r) => {
              const seen = r.locationUpdatedAt ? new Date(r.locationUpdatedAt).getTime() : null;
              const stale = r.status !== "OFFLINE" && (seen === null || now - seen > STALE_MS);
              return (
                <tr key={r.id} className="hover:bg-surface-2">
                  <Td className="font-medium">{r.name}</Td>
                  <Td><StatusBadge status={r.status} /></Td>
                  <Td>{titleCase(r.vehicleType)}</Td>
                  <Td className="tabular-nums">{r.rating.toFixed(1)}</Td>
                  <Td>
                    {relative(r.locationUpdatedAt, now)}
                    {stale ? <span className="ml-2 text-xs text-[var(--warn-fg)]">not reporting</span> : null}
                  </Td>
                  <Td className="font-mono text-xs">{r.lat !== null && r.lon !== null ? `${r.lat.toFixed(4)}, ${r.lon.toFixed(4)}` : "—"}</Td>
                </tr>
              );
            })}
          </DataTable>
          <Pager page={page} size={PAGE_SIZE} total={riders.data.total} onPage={setPage} />
        </>
      )}
    </>
  );
}
