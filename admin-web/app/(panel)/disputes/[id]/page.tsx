"use client";

import Link from "next/link";
import { use, useState } from "react";
import { Badge, Button, Card, ConfirmDialog, ErrorBox, Loading, PageHeader, useNow } from "@/components/ui";
import { dateTime, deadline } from "@/lib/format";
import { useDisputeEvidence, useSaveEvidence, useSubmitEvidence } from "@/lib/queries";
import { EVIDENCE_LIMIT } from "@/lib/schemas";

function Fact({ label, value }: { label: string; value: string | null | undefined }) {
  return (
    <div>
      <dt className="text-xs font-medium text-muted">{label}</dt>
      <dd className="mt-0.5 whitespace-pre-line text-sm">{value || <span className="text-muted">Not available</span>}</dd>
    </div>
  );
}

export default function DisputeEvidencePage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  const q = useDisputeEvidence(id);
  const save = useSaveEvidence();
  const submit = useSubmitEvidence();
  const now = useNow();
  const [text, setText] = useState<string | null>(null);
  const [confirming, setConfirming] = useState(false);

  // until the person types, show what is saved; after that their text wins over refetches
  const saved = q.data?.explanation ?? "";

  if (q.isPending) return <Loading />;
  if (q.isError) return <ErrorBox error={q.error} onRetry={() => q.refetch()} />;
  const v = q.data;
  const draft = text ?? saved;
  const dirty = draft !== saved;
  const due = deadline(v.evidenceDueBy, now);
  const send = () => submit.mutate(id, { onSuccess: () => setConfirming(false) });

  return (
    <>
      <PageHeader
        title="Answer this dispute"
        subtitle={<><Link href="/disputes" className="underline">Disputes</Link> · Evidence goes to the payment provider and can be sent once.</>}
        actions={v.submitted ? <Badge tone="ok">Submitted {v.submittedAt ? dateTime(v.submittedAt) : ""}</Badge> : <Badge tone={due.overdue ? "bad" : due.soon ? "warn" : "neutral"}>{due.label}</Badge>}
      />

      <Card title="What we will tell them" className="max-w-3xl">
        <p className="mb-3 text-xs text-muted">Compiled from our trip and payment records. {v.submitted ? "This is what was sent." : "It is frozen at the moment you submit."}</p>
        <dl className="space-y-3">
          <Fact label="Customer" value={v.facts.customerName} />
          <Fact label="Service" value={v.facts.productDescription} />
          <Fact label="Service date" value={v.facts.serviceDate} />
          <Fact label="Activity log" value={v.facts.accessActivityLog} />
        </dl>
        {!v.facts.accessActivityLog ? <p className="mt-3 text-sm text-muted">This dispute is not tied to a trip, so there are no records to attach. Your explanation has to carry the case.</p> : null}
      </Card>

      <Card title="Your explanation" className="mt-4 max-w-3xl">
        <label className="block text-sm font-medium">
          Why this charge is valid
          <textarea
            value={draft} readOnly={v.submitted} rows={8}
            onChange={(e) => setText(e.target.value.slice(0, EVIDENCE_LIMIT))}
            className="mt-1 block w-full rounded-md border border-line bg-surface px-3 py-2 text-sm"
          />
        </label>
        <div className="mt-1 text-xs text-muted">{draft.length}/{EVIDENCE_LIMIT}</div>

        {v.lastError ? <p role="alert" className="mt-3 rounded-md bg-surface-2 px-3 py-2 text-sm">The payment provider refused the last attempt: {v.lastError}</p> : null}
        {save.error ? <ErrorBox error={save.error} /> : null}

        {!v.submitted ? (
          <div className="mt-4 flex flex-wrap items-center gap-2">
            <Button disabled={!dirty || save.isPending} onClick={() => save.mutate({ id, explanation: draft })}>{save.isPending ? "Saving…" : "Save draft"}</Button>
            <Button variant="primary" disabled={dirty || !v.canSubmit} onClick={() => setConfirming(true)}>Submit to the provider…</Button>
            <span className="text-xs text-muted">
              {dirty ? "Save your changes before submitting." : !v.canSubmit ? v.cannotSubmitBecause : save.isSuccess ? "Draft saved." : null}
            </span>
          </div>
        ) : null}
      </Card>

      <ConfirmDialog
        open={confirming} title="Submit this evidence?" confirmLabel="Submit" busy={submit.isPending} error={submit.error}
        onCancel={() => { setConfirming(false); submit.reset(); }} onConfirm={send}
      >
        The provider accepts one response per dispute. After this you cannot add to it or change it.
      </ConfirmDialog>
    </>
  );
}
