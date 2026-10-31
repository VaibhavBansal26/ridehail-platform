"use client";

import { MAX_DOCUMENT_BYTES, type Verification } from "@ridehail/api";
import { Check } from "lucide-react";
import { type FormEvent, useState } from "react";
import { Badge, Button, Card, ErrorNote, Field, inputClass } from "@/components/ui";
import { dateTime, titleCase } from "@/lib/format";
import { useSubmitVerification, useUploadDocument, useVerification } from "@/lib/queries";

type Requirement = Verification["requirements"][number];
const ACCEPT_ANY = "image/jpeg,image/png,application/pdf";
const ACCEPT_IMAGE = "image/jpeg,image/png";

/** What the screen can say before the server is asked: a file that is obviously wrong is refused on the device. */
export function checkFile(file: File, requirement: Requirement): string | null {
  if (file.size === 0) return "That file is empty.";
  if (file.size > MAX_DOCUMENT_BYTES) return `That file is larger than ${MAX_DOCUMENT_BYTES / (1024 * 1024)} MB. Take a smaller photo.`;
  const ok = requirement.imageOnly ? ["image/jpeg", "image/png"] : ["image/jpeg", "image/png", "application/pdf"];
  if (file.type && !ok.includes(file.type)) return requirement.imageOnly ? "Send a JPEG or PNG picture." : "Send a JPEG or PNG picture, or a PDF.";
  return null;
}

function DocumentRow({ requirement, sent, editable }: { requirement: Requirement; sent: Verification["documents"][number] | undefined; editable: boolean }) {
  const upload = useUploadDocument();
  const [error, setError] = useState<string | null>(null);

  function onSubmit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const form = new FormData(e.currentTarget);
    const expiresOn = String(form.get("expiresOn") ?? "");
    // read from the input itself: it is what the person picked, whatever else is in the form
    const input = e.currentTarget.elements.namedItem("file") as HTMLInputElement | null;
    const file = input?.files?.[0];
    if (!file || file.size === 0) return setError("Choose a file first.");
    const problem = checkFile(file, requirement);
    if (problem) return setError(problem);
    if (requirement.needsExpiry && !expiresOn) return setError(`Enter the date this ${requirement.label.toLowerCase()} expires.`);
    setError(null);
    upload.mutate({ type: requirement.type, file, expiresOn: requirement.needsExpiry ? expiresOn : undefined }, { onSuccess: () => e.currentTarget?.reset?.() });
  }

  return (
    <li className="py-4">
      <div className="flex items-center justify-between gap-2">
        <span className="font-medium">{requirement.label}</span>
        {sent ? <Badge tone="ok"><Check className="size-3" aria-hidden /> Sent</Badge> : <Badge tone="warn">Needed</Badge>}
      </div>
      {sent ? (
        <p className="mt-1 text-xs text-muted">
          {dateTime(sent.uploadedAt)}
          {sent.expiresOn ? ` · expires ${sent.expiresOn}` : ""}
        </p>
      ) : null}
      {editable ? (
        <form onSubmit={onSubmit} className="mt-3 space-y-3">
          <Field label={sent ? "Send a different one" : "Choose a file"}>
            <input name="file" type="file" accept={requirement.imageOnly ? ACCEPT_IMAGE : ACCEPT_ANY} capture={requirement.type === "SELFIE" ? "user" : undefined} className={inputClass} />
          </Field>
          {requirement.needsExpiry ? (
            <Field label="Expiry date">
              <input name="expiresOn" type="date" defaultValue={sent?.expiresOn ?? ""} className={inputClass} />
            </Field>
          ) : null}
          {error ? <ErrorNote>{error}</ErrorNote> : null}
          {upload.error ? <ErrorNote>{upload.error.message}</ErrorNote> : null}
          <Button type="submit" variant="secondary" busy={upload.isPending}>{sent ? "Replace" : "Upload"}</Button>
        </form>
      ) : null}
    </li>
  );
}

const STATUS_TEXT: Record<string, { title: string; body: string; tone: "ok" | "warn" | "bad" | "info" | "neutral" }> = {
  NOT_SUBMITTED: { title: "Send your documents", body: "We check four things before you can drive: your licence, your vehicle's registration, your insurance and a selfie.", tone: "info" },
  PENDING_REVIEW: { title: "We're reviewing your documents", body: "This usually takes a day or two. We'll notify you as soon as there's a decision. You can't change anything until then.", tone: "warn" },
  APPROVED: { title: "You're approved", body: "You can go online and take trips.", tone: "ok" },
  REJECTED: { title: "Your documents need attention", body: "Fix what's listed below and send them again.", tone: "bad" },
  SUSPENDED: { title: "Your account is suspended", body: "You can't take trips right now. Contact support if you think this is a mistake.", tone: "bad" },
  EXPIRED: { title: "A document has expired", body: "Upload current ones and send them again to start driving.", tone: "bad" },
};

export function VerificationView() {
  const verification = useVerification();
  const submit = useSubmitVerification();

  if (verification.isPending) return <p className="py-10 text-center text-sm text-muted">Loading…</p>;
  if (verification.isError) return <ErrorNote>{verification.error.message}</ErrorNote>;
  const v = verification.data;
  if (!v.required) return <Card><p className="text-sm">Verification isn&apos;t required yet. You can drive without it.</p></Card>;

  const text = STATUS_TEXT[v.status] ?? { title: titleCase(v.status), body: "", tone: "neutral" as const };
  const sent = new Map(v.documents.map((d) => [d.type, d]));

  return (
    <>
      <Card>
        <div className="flex items-start justify-between gap-2">
          <h2 className="text-xl font-semibold">{text.title}</h2>
          <Badge tone={text.tone}>{titleCase(v.status)}</Badge>
        </div>
        <p className="mt-1 text-sm text-muted">{text.body}</p>
        {v.note ? <p role="note" className="mt-3 rounded-lg bg-surface-2 px-3 py-2 text-sm"><span className="font-medium">From the reviewer:</span> {v.note}</p> : null}
      </Card>

      <Card title="Your documents">
        <ul className="divide-y divide-line">
          {v.requirements.map((r) => <DocumentRow key={r.type} requirement={r} sent={sent.get(r.type)} editable={v.canUpload} />)}
        </ul>
        {submit.error ? <div className="mt-3"><ErrorNote>{submit.error.message}</ErrorNote></div> : null}
        {v.canUpload ? (
          <Button className="mt-4 w-full" busy={submit.isPending} disabled={!v.canSubmit} onClick={() => submit.mutate()}>
            Send for review
          </Button>
        ) : null}
        {v.canUpload && !v.canSubmit && v.missing.length > 0 ? (
          <p className="mt-2 text-xs text-muted">Still needed: {v.missing.map((m) => titleCase(m).toLowerCase()).join(", ")}.</p>
        ) : null}
      </Card>
    </>
  );
}
