import { dateTime, titleCase, type Verification } from "@ridehail/api";
import { useState } from "react";
import { Text, View } from "react-native";
import { Badge, Button, Card, ErrorNote, Field, Muted, useTheme } from "@/components/ui";
import { pickDocument } from "@/lib/documents";
import { useSubmitVerification, useUploadDocument, useVerification } from "@/lib/queries";

type Requirement = Verification["requirements"][number];

/** YYYY-MM-DD that is a real date (not 2030-02-31). */
export function isValidDate(s: string): boolean {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(s)) return false;
  const d = new Date(s + "T00:00:00Z");
  return !Number.isNaN(d.getTime()) && d.toISOString().slice(0, 10) === s;
}

function DocumentRow({ requirement, sent, editable }: { requirement: Requirement; sent: Verification["documents"][number] | undefined; editable: boolean }) {
  const t = useTheme();
  const upload = useUploadDocument();
  const [expiry, setExpiry] = useState(sent?.expiresOn ?? "");
  const [problem, setProblem] = useState<string | null>(null);

  async function choose(source: "camera" | "library") {
    setProblem(null);
    if (requirement.needsExpiry && !isValidDate(expiry)) return setProblem(`Enter the date this ${requirement.label.toLowerCase()} expires, as YYYY-MM-DD (for example 2030-05-31).`);
    const result = await pickDocument(source, { selfie: requirement.type === "SELFIE" });
    if (!result.ok) {
      if (result.reason !== "cancelled") setProblem(result.message ?? "Could not use that picture.");
      return;
    }
    upload.mutate({ type: requirement.type, picked: result.picked, expiresOn: requirement.needsExpiry ? expiry : undefined });
  }

  return (
    <View style={{ gap: 8, paddingVertical: 12, borderTopWidth: 1, borderColor: t.line }}>
      <View style={{ flexDirection: "row", justifyContent: "space-between", alignItems: "center" }}>
        <Text style={{ color: t.text, fontWeight: "600" }}>{requirement.label}</Text>
        {sent ? <Badge tone="ok">Sent</Badge> : <Badge tone="warn">Needed</Badge>}
      </View>
      {sent ? <Muted>{`${dateTime(sent.uploadedAt)}${sent.expiresOn ? ` · expires ${sent.expiresOn}` : ""}`}</Muted> : null}
      {editable ? (
        <>
          {requirement.needsExpiry ? (
            <Field label="Expiry date (YYYY-MM-DD)" value={expiry} onChangeText={setExpiry} placeholder="2030-05-31" keyboardType="numbers-and-punctuation" autoCapitalize="none" />
          ) : null}
          {problem ? <ErrorNote>{problem}</ErrorNote> : null}
          {upload.error ? <ErrorNote>{upload.error.message}</ErrorNote> : null}
          <View style={{ flexDirection: "row", gap: 8 }}>
            <View style={{ flex: 1 }}>
              <Button label={sent ? "Retake photo" : "Take photo"} busy={upload.isPending} onPress={() => void choose("camera")} />
            </View>
            {requirement.type !== "SELFIE" ? (
              <View style={{ flex: 1 }}>
                <Button label="Choose photo" variant="secondary" busy={upload.isPending} onPress={() => void choose("library")} />
              </View>
            ) : null}
          </View>
        </>
      ) : null}
    </View>
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
  const t = useTheme();
  const verification = useVerification();
  const submit = useSubmitVerification();

  if (verification.isPending) return <Muted style={{ textAlign: "center", paddingVertical: 40 }}>Loading…</Muted>;
  if (verification.isError) return <ErrorNote>{verification.error.message}</ErrorNote>;
  const v = verification.data;
  if (!v.required) return <Card><Muted>Verification isn&apos;t required yet. You can drive without it.</Muted></Card>;

  const text = STATUS_TEXT[v.status] ?? { title: titleCase(v.status), body: "", tone: "neutral" as const };
  const sent = new Map(v.documents.map((d) => [d.type, d]));

  return (
    <>
      <Card>
        <View style={{ flexDirection: "row", justifyContent: "space-between", alignItems: "flex-start", gap: 8 }}>
          <Text accessibilityRole="header" style={{ color: t.text, fontSize: 22, fontWeight: "700", flex: 1 }}>{text.title}</Text>
          <Badge tone={text.tone}>{titleCase(v.status)}</Badge>
        </View>
        <Muted>{text.body}</Muted>
        {v.note ? <Text accessibilityRole="summary" style={{ color: t.text }}>{`From the reviewer: ${v.note}`}</Text> : null}
      </Card>

      <Card title="Your documents">
        {v.requirements.map((r) => <DocumentRow key={r.type} requirement={r} sent={sent.get(r.type)} editable={v.canUpload} />)}
        {submit.error ? <ErrorNote>{submit.error.message}</ErrorNote> : null}
        {v.canUpload ? <Button label="Send for review" busy={submit.isPending} disabled={!v.canSubmit} onPress={() => submit.mutate()} /> : null}
        {v.canUpload && !v.canSubmit && v.missing.length > 0 ? <Muted>{`Still needed: ${v.missing.map((m) => titleCase(m).toLowerCase()).join(", ")}.`}</Muted> : null}
      </Card>
    </>
  );
}
