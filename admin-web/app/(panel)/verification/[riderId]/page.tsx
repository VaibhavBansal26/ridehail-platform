"use client";

import Link from "next/link";
import { use, useState } from "react";
import { Badge, Button, Card, ConfirmDialog, ErrorBox, Loading, PageHeader } from "@/components/ui";
import { dateTime, titleCase } from "@/lib/format";
import { useApproveRider, useReinstateRider, useRejectRider, useSuspendRider, useVerification } from "@/lib/queries";
import type { VerificationDetail } from "@/lib/schemas";

const REASON_LIMIT = 500;
type Doc = VerificationDetail["verification"]["documents"][number];
type Action = "approve" | "reject" | "suspend" | "reinstate";

/** Days until `date` (YYYY-MM-DD), negative once it has passed. */
function daysLeft(date: string | null | undefined, now = Date.now()): number | null {
  if (!date) return null;
  const t = Date.parse(date + "T00:00:00Z");
  return Number.isNaN(t) ? null : Math.floor((t - now) / 86_400_000);
}

function Expiry({ date }: { date: string | null | undefined }) {
  const d = daysLeft(date);
  if (d === null) return <span className="text-muted">no expiry</span>;
  const tone = d < 0 ? "bad" : d <= 30 ? "warn" : "ok";
  return <Badge tone={tone}>{d < 0 ? `expired ${date}` : `expires ${date}${d <= 30 ? ` (${d} days)` : ""}`}</Badge>;
}

/** A document is hidden until someone asks to see it, so every look is deliberate (and recorded, server side). */
function DocumentPanel({ riderId, doc }: { riderId: string; doc: Doc }) {
  const [shown, setShown] = useState(false);
  const src = `/api/admin/verifications/${riderId}/documents/${doc.type}`;
  const isPdf = doc.contentType === "application/pdf";
  return (
    <div className="rounded-md border border-line p-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div>
          <div className="text-sm font-medium">{doc.label}</div>
          <div className="mt-1 flex items-center gap-2 text-xs text-muted">
            <Expiry date={doc.expiresOn} /> <span>sent {dateTime(doc.uploadedAt)}</span>
          </div>
        </div>
        {shown ? <Button onClick={() => setShown(false)}>Hide</Button> : <Button onClick={() => setShown(true)}>Show document</Button>}
      </div>
      {shown ? (
        <div className="mt-3">
          {isPdf ? (
            <a href={src} target="_blank" rel="noreferrer" className="text-sm text-accent underline">Open the PDF in a new tab</a>
          ) : (
            // eslint-disable-next-line @next/next/no-img-element
            <img src={src} alt={doc.label} className="max-h-96 w-auto rounded border border-line" />
          )}
        </div>
      ) : null}
    </div>
  );
}

export default function VerificationReviewPage({ params }: { params: Promise<{ riderId: string }> }) {
  const { riderId } = use(params);
  const q = useVerification(riderId);
  const approve = useApproveRider();
  const reject = useRejectRider();
  const suspend = useSuspendRider();
  const reinstate = useReinstateRider();
  const [action, setAction] = useState<Action | null>(null);
  const [reason, setReason] = useState("");

  if (q.isPending) return <Loading />;
  if (q.isError) return <ErrorBox error={q.error} onRetry={() => q.refetch()} />;
  const { name, phone, vehicleType, verification: v } = q.data;

  const close = () => { setAction(null); setReason(""); approve.reset(); reject.reset(); suspend.reset(); reinstate.reset(); };
  const busy = approve.isPending || reject.isPending || suspend.isPending || reinstate.isPending;
  const error = approve.error ?? reject.error ?? suspend.error ?? reinstate.error;
  const needsReason = action === "reject" || action === "suspend";
  const run = () => {
    const opts = { onSuccess: close };
    if (action === "approve") approve.mutate(riderId, opts);
    else if (action === "reject") reject.mutate({ id: riderId, reason: reason.trim() }, opts);
    else if (action === "suspend") suspend.mutate({ id: riderId, reason: reason.trim() }, opts);
    else if (action === "reinstate") reinstate.mutate(riderId, opts);
  };

  const titles: Record<Action, string> = { approve: "Approve this rider?", reject: "Send this back to the rider?", suspend: "Suspend this rider?", reinstate: "Lift the suspension?" };
  const bodies: Record<Action, string> = {
    approve: `${name} will be able to go online and take trips straight away.`,
    reject: "The rider is told to open Verification and sees your reason. They can fix it and send it again.",
    suspend: `${name} is taken offline now (a trip in progress is finished first) and cannot go online until you lift this. They see your reason.`,
    reinstate: `${name} can go online again, as long as their documents are still valid.`,
  };

  return (
    <>
      <PageHeader
        title={name}
        subtitle={<><Link href="/verification" className="underline">Verification</Link> · {phone} · {titleCase(vehicleType)}</>}
        actions={<Badge tone={v.status === "APPROVED" ? "ok" : v.status === "PENDING_REVIEW" ? "warn" : "bad"}>{titleCase(v.status)}</Badge>}
      />

      {v.note ? <p className="mb-4 rounded-md bg-surface-2 px-3 py-2 text-sm"><span className="font-medium">Note:</span> {v.note}</p> : null}

      <Card title="Documents" className="max-w-3xl">
        <p className="mb-3 text-xs text-muted">Compare the selfie with the photo on the licence. Opening a document is recorded in the audit log with your name.</p>
        <div className="space-y-3">
          {v.documents.map((d) => <DocumentPanel key={d.type} riderId={riderId} doc={d} />)}
          {v.missing.length > 0 ? <p className="text-sm text-muted">Not sent yet: {v.missing.map((m) => titleCase(m).toLowerCase()).join(", ")}.</p> : null}
        </div>
      </Card>

      <div className="mt-4 flex flex-wrap gap-2">
        {v.status === "PENDING_REVIEW" ? (
          <>
            <Button variant="primary" onClick={() => setAction("approve")}>Approve</Button>
            <Button onClick={() => setAction("reject")}>Send back…</Button>
          </>
        ) : null}
        {v.status === "APPROVED" ? <Button variant="danger" onClick={() => setAction("suspend")}>Suspend…</Button> : null}
        {v.status === "SUSPENDED" ? <Button onClick={() => setAction("reinstate")}>Lift suspension</Button> : null}
      </div>

      <ConfirmDialog
        open={action !== null} title={action ? titles[action] : ""} confirmLabel={action === "approve" ? "Approve" : action === "reject" ? "Send back" : action === "suspend" ? "Suspend" : "Lift"}
        danger={action === "suspend"} busy={busy} error={error} onCancel={close} onConfirm={run}
        confirmDisabled={needsReason && reason.trim().length === 0}
      >
        {action ? bodies[action] : null}
        {needsReason ? (
          <div className="mt-3">
            <label className="block text-sm font-medium text-ink">Reason (the rider sees this)
              <textarea
                value={reason} onChange={(e) => setReason(e.target.value.slice(0, REASON_LIMIT))} rows={3}
                className="mt-1 block w-full rounded-md border border-line bg-surface px-3 py-2 text-sm"
              />
            </label>
            <span className="text-xs text-muted">{reason.length}/{REASON_LIMIT}</span>
          </div>
        ) : null}
      </ConfirmDialog>
    </>
  );
}
