"use client";

import { useState } from "react";
import { DataTable, Empty, ErrorBox, IdLink, Loading, PageHeader, Pager, Select, StatusBadge, Td, useNow } from "@/components/ui";
import { dateTime, money, relative, titleCase } from "@/lib/format";
import { PAGE_SIZE, useTrips } from "@/lib/queries";
import { TripStatus } from "@/lib/schemas";

export default function TripsPage() {
  const [status, setStatus] = useState("");
  const [page, setPage] = useState(0);
  const trips = useTrips(status, page);
  const now = useNow();

  return (
    <>
      <PageHeader
        title="Trips" subtitle="Newest first."
        actions={<Select label="Status" value={status} onChange={(v) => { setStatus(v); setPage(0); }} options={[{ value: "", label: "All" }, ...TripStatus.options.map((s) => ({ value: s, label: titleCase(s) }))]} />}
      />
      {trips.isPending ? <Loading /> : trips.isError ? <ErrorBox error={trips.error} onRetry={() => trips.refetch()} /> : trips.data.items.length === 0 ? <Empty>No trips{status ? ` in ${titleCase(status)}` : ""} yet.</Empty> : (
        <>
          <DataTable caption="Trips" head={["Trip", "Status", "Customer", "Rider", "Vehicle", "Fare", "Requested"]}>
            {trips.data.items.map((t) => (
              <tr key={t.id} className="hover:bg-surface-2">
                <Td><IdLink href={`/trips/${t.id}`} id={t.id} /></Td>
                <Td><StatusBadge status={t.status} /></Td>
                <Td>{t.customerName ?? "—"}</Td>
                <Td>{t.riderName ?? <span className="text-muted">Unassigned</span>}</Td>
                <Td>{titleCase(t.vehicleType)}{t.surgeMultiplier > 1 ? <span className="ml-1 text-xs text-[var(--warn-fg)]">{t.surgeMultiplier}×</span> : null}</Td>
                <Td className="tabular-nums">{money(t.fare, t.currency)}</Td>
                <Td><span title={dateTime(t.requestedAt)}>{relative(t.requestedAt, now)}</span></Td>
              </tr>
            ))}
          </DataTable>
          <Pager page={page} size={PAGE_SIZE} total={trips.data.total} onPage={setPage} />
        </>
      )}
    </>
  );
}
