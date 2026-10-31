"use client";

import Link from "next/link";
import { use, useState } from "react";
import { Badge, Button, Card, ErrorBox, Loading, PageHeader } from "@/components/ui";
import { dateTime, titleCase } from "@/lib/format";
import { useSupportReply, useSupportResolve, useSupportThread } from "@/lib/queries";
import { SUPPORT_LIMIT } from "@/lib/schemas";

export default function SupportThreadPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  const q = useSupportThread(id);
  const reply = useSupportReply();
  const resolve = useSupportResolve();
  const [text, setText] = useState("");

  if (q.isPending) return <Loading />;
  if (q.isError) return <ErrorBox error={q.error} onRetry={() => q.refetch()} />;
  const { conversation: c, messages } = q.data;
  const send = () => reply.mutate({ id, body: text.trim() }, { onSuccess: () => setText("") });

  return (
    <>
      <PageHeader
        title={c.name ?? "Unknown"}
        subtitle={<><Link href="/support" className="underline">Support</Link> · {titleCase(c.role)} · {c.phone}</>}
        actions={<Badge tone={c.awaitingReply ? "warn" : c.status === "RESOLVED" ? "neutral" : "ok"}>{c.awaitingReply ? "Needs reply" : titleCase(c.status)}</Badge>}
      />

      <Card title="Conversation" className="max-w-3xl">
        <ol className="space-y-3" aria-label="Messages">
          {messages.map((m) => (
            <li key={m.id} className={`flex ${m.from === "STAFF" ? "justify-end" : ""}`}>
              <div className={`max-w-[85%] rounded-lg px-3 py-2 text-sm ${m.from === "STAFF" ? "bg-accent text-white" : "bg-surface-2"}`}>
                {/* plain text on purpose: what people type is never interpreted as markup */}
                <p className="whitespace-pre-wrap break-words">{m.body}</p>
                <p className={`mt-1 text-xs ${m.from === "STAFF" ? "text-white/80" : "text-muted"}`}>
                  {m.from === "STAFF" ? "Support" : c.name} · {dateTime(m.createdAt)}
                  {m.tripId ? <> · <Link href={`/trips/${m.tripId}`} className="underline">about trip {m.tripId.slice(0, 8)}</Link></> : null}
                </p>
              </div>
            </li>
          ))}
        </ol>
      </Card>

      <Card title="Reply" className="mt-4 max-w-3xl">
        <label className="block text-sm font-medium">
          Your reply
          <textarea
            value={text} rows={4} onChange={(e) => setText(e.target.value.slice(0, SUPPORT_LIMIT))}
            className="mt-1 block w-full rounded-md border border-line bg-surface px-3 py-2 text-sm"
          />
        </label>
        <div className="mt-1 text-xs text-muted">{text.length}/{SUPPORT_LIMIT} · they get a notification on their phone</div>
        {reply.error ? <ErrorBox error={reply.error} /> : null}
        {resolve.error ? <ErrorBox error={resolve.error} /> : null}
        <div className="mt-3 flex flex-wrap gap-2">
          <Button variant="primary" disabled={text.trim().length === 0 || reply.isPending} onClick={send}>{reply.isPending ? "Sending…" : "Send reply"}</Button>
          {c.status === "OPEN" ? <Button disabled={resolve.isPending} onClick={() => resolve.mutate(id)}>Mark resolved</Button> : null}
        </div>
      </Card>
    </>
  );
}
