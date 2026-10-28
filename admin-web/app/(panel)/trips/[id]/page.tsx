"use client";

import Link from "next/link";
import { use, useState } from "react";
import { ArrowLeft } from "lucide-react";
import { Badge, Button, Card, ConfirmDialog, DataTable, ErrorBox, IdLink, Loading, PageHeader, StatusBadge, Td } from "@/components/ui";
import { dateTime, deadline, money, titleCase } from "@/lib/format";
import { useCancelTrip, useTrip } from "@/lib/queries";

function Row({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="flex justify-between gap-4 py-1.5 text-sm">
      <dt className="text-muted">{label}</dt>
      <dd className="text-right">{children}</dd>
    </div>
  );
}

export default function TripDetailPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  const detail = useTrip(id);
  const cancel = useCancelTrip();
  const [confirm, setConfirm] = useState(false);

  if (detail.isPending) return <Loading />;
  if (detail.isError) return <ErrorBox error={detail.error} onRetry={() => detail.refetch()} />;
  const { trip, payment, offers, disputes } = detail.data;
  const cancellable = trip.status === "REQUESTED" || trip.status === "ACCEPTED" || trip.status === "ARRIVED";
  const timeline: [string, string | null][] = [
    ["Requested", trip.requestedAt], ["Accepted", trip.acceptedAt], ["Started", trip.startedAt], ["Completed", trip.completedAt],
  ];

  return (
    <>
      <Link href="/trips" className="mb-3 inline-flex items-center gap-1 text-sm text-muted hover:text-ink"><ArrowLeft className="size-4" aria-hidden /> Trips</Link>
      <PageHeader
        title={`Trip ${trip.id.slice(0, 8)}`}
        subtitle={<StatusBadge status={trip.status} />}
        actions={cancellable ? <Button variant="danger" onClick={() => setConfirm(true)}>Cancel trip</Button> : undefined}
      />

      <div className="grid gap-4 lg:grid-cols-2">
        <Card title="Trip">
          <dl className="divide-y divide-line">
            <Row label="Customer">{trip.customerName ?? "—"}</Row>
            <Row label="Rider">{trip.riderName ?? "Unassigned"}</Row>
            <Row label="Vehicle">{titleCase(trip.vehicleType)}</Row>
            <Row label="Fare">{money(trip.fare, trip.currency)}{trip.surgeMultiplier > 1 ? ` (${trip.surgeMultiplier}× surge)` : ""}</Row>
            <Row label="Pickup"><span className="font-mono text-xs">{trip.pickupLat.toFixed(4)}, {trip.pickupLon.toFixed(4)}</span></Row>
            <Row label="Drop-off"><span className="font-mono text-xs">{trip.dropLat.toFixed(4)}, {trip.dropLon.toFixed(4)}</span></Row>
          </dl>
        </Card>

        <Card title="Timeline">
          <ol className="space-y-3 text-sm">
            {timeline.map(([label, at]) => (
              <li key={label} className="flex items-center justify-between">
                <span className={at ? "" : "text-muted"}>{label}</span>
                <span className="tabular-nums text-muted">{dateTime(at)}</span>
              </li>
            ))}
          </ol>
        </Card>

        <Card title="Payment">
          {payment ? (
            <dl className="divide-y divide-line">
              <Row label="Status"><StatusBadge status={payment.status} /></Row>
              <Row label="Charged">{money(payment.amount, payment.currency)}</Row>
              <Row label="Commission">{money(payment.commission, payment.currency)}</Row>
              <Row label="Rider earns">{money(payment.riderAmount, payment.currency)}</Row>
              <Row label="Attempts">{payment.attempts}</Row>
              {payment.failureReason ? <Row label="Problem"><span className="text-[var(--bad-fg)]">{payment.failureReason}</span></Row> : null}
            </dl>
          ) : <p className="text-sm text-muted">No payment yet. It is created when the trip completes.</p>}
        </Card>

        <Card title="Disputes">
          {disputes.length === 0 ? <p className="text-sm text-muted">No disputes on this trip.</p> : (
            <ul className="space-y-2 text-sm">
              {disputes.map((d) => (
                <li key={d.id} className="flex items-center justify-between">
                  <span><StatusBadge status={d.status} /> <span className="ml-2">{money(d.amount, d.currency)}</span> <span className="text-muted">{d.reason ? titleCase(d.reason) : ""}</span></span>
                  <span className="text-muted">{deadline(d.evidenceDueBy).label}</span>
                </li>
              ))}
              <li><Link href="/disputes" className="text-accent hover:underline">Open disputes</Link></li>
            </ul>
          )}
        </Card>
      </div>

      <h2 className="mb-2 mt-6 text-sm font-medium text-muted">Dispatch: offers sent to riders ({trip.offersSent})</h2>
      {offers.length === 0 ? <p className="text-sm text-muted">No offers were sent.</p> : (
        <DataTable caption="Offers" head={["Rider", "Distance", "Outcome", "Sent", "Answered"]}>
          {offers.map((o) => (
            <tr key={o.id}>
              <Td>{o.riderName ?? <IdLink href="/riders" id={o.riderId} />}</Td>
              <Td className="tabular-nums">{o.riderDistanceKm.toFixed(1)} km</Td>
              <Td><StatusBadge status={o.status} /></Td>
              <Td>{dateTime(o.createdAt)}</Td>
              <Td>{o.respondedAt ? dateTime(o.respondedAt) : <Badge>No answer</Badge>}</Td>
            </tr>
          ))}
        </DataTable>
      )}

      <ConfirmDialog
        open={confirm} title="Cancel this trip?" confirmLabel="Cancel trip" danger busy={cancel.isPending} error={cancel.error}
        onCancel={() => { setConfirm(false); cancel.reset(); }}
        onConfirm={() => cancel.mutate(id, { onSuccess: () => setConfirm(false) })}
      >
        The customer and the rider are told the trip is cancelled. This cannot be undone.
      </ConfirmDialog>
    </>
  );
}
