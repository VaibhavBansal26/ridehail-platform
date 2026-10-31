"use client";

import { MAX_SUPPORT_MESSAGE } from "@ridehail/api";
import { type FormEvent, useEffect, useState } from "react";
import { Badge, Button, Card, ErrorNote, Field, inputClass } from "@/components/ui";
import { dateTime } from "@/lib/format";
import { useMarkSupportRead, useSendSupport, useSupport } from "@/lib/queries";

export function SupportView() {
  const q = useSupport();
  const send = useSendSupport();
  const markRead = useMarkSupportRead();
  const [text, setText] = useState("");
  const unread = q.data?.unread ?? 0;

  // looking at the conversation is what reading it means
  useEffect(() => {
    if (unread > 0) markRead.mutate();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [unread]);

  function onSubmit(e: FormEvent) {
    e.preventDefault();
    const body = text.trim();
    if (!body) return;
    send.mutate(body, { onSuccess: () => setText("") });
  }

  return (
    <div className="space-y-4">
      <Card title="Support">
        <p className="text-sm text-muted">Tell us what went wrong. Someone will answer here, and you will get a notification.</p>
        {q.isPending ? <p className="mt-3 text-sm text-muted">Loading…</p> : q.isError ? <ErrorNote>We could not load your messages. {q.error.message}</ErrorNote> : (
          <ol aria-label="Messages" className="mt-3 space-y-2">
            {q.data.messages.length === 0 ? <li className="text-sm text-muted">No messages yet.</li> : null}
            {q.data.messages.map((m) => (
              <li key={m.id} className={`flex ${m.from === "USER" ? "justify-end" : ""}`}>
                <div className={`max-w-[85%] rounded-lg px-3 py-2 text-sm ${m.from === "USER" ? "bg-accent text-white" : "bg-surface-2"}`}>
                  {/* plain text on purpose: nothing typed by anyone is interpreted as markup */}
                  <p className="whitespace-pre-wrap break-words">{m.body}</p>
                  <p className={`mt-1 text-xs ${m.from === "USER" ? "text-white/80" : "text-muted"}`}>{m.from === "USER" ? "You" : "Support"} · {dateTime(m.createdAt)}</p>
                </div>
              </li>
            ))}
          </ol>
        )}
        {q.data?.status === "RESOLVED" ? <div className="mt-3"><Badge tone="neutral">Marked resolved. Write again if you still need help.</Badge></div> : null}
      </Card>

      <form onSubmit={onSubmit} className="space-y-3">
        <Field label="Your message" hint={`${text.length}/${MAX_SUPPORT_MESSAGE}`}>
          <textarea
            value={text} rows={4} onChange={(e) => setText(e.target.value.slice(0, MAX_SUPPORT_MESSAGE))}
            className={inputClass}
          />
        </Field>
        {send.error ? <ErrorNote>{send.error.message}</ErrorNote> : null}
        <Button type="submit" busy={send.isPending} disabled={text.trim().length === 0}>Send</Button>
      </form>
    </div>
  );
}
