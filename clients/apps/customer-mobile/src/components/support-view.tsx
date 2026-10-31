import { dateTime, MAX_SUPPORT_MESSAGE } from "@ridehail/api";
import { useEffect, useState } from "react";
import { Text, View } from "react-native";
import { Badge, Button, Card, ErrorNote, Field, Muted, useTheme } from "@/components/ui";
import { useMarkSupportRead, useSendSupport, useSupport } from "@/lib/queries";

export function SupportView() {
  const t = useTheme();
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

  const body = text.trim();
  return (
    <View style={{ gap: 12 }}>
      <Card title="Support">
        <Muted>Tell us what went wrong. Someone will answer here, and you will get a notification.</Muted>
        {q.isPending ? <Muted style={{ marginTop: 12 }}>Loading…</Muted> : q.isError ? <ErrorNote>We could not load your messages. {q.error.message}</ErrorNote> : (
          <View accessibilityLabel="Messages" style={{ marginTop: 12, gap: 8 }}>
            {q.data.messages.length === 0 ? <Muted>No messages yet.</Muted> : null}
            {q.data.messages.map((m) => {
              const mine = m.from === "USER";
              return (
                <View key={m.id} style={{ alignSelf: mine ? "flex-end" : "flex-start", maxWidth: "85%", borderRadius: 12, padding: 10, backgroundColor: mine ? t.accent : t.bg, borderWidth: mine ? 0 : 1, borderColor: t.line }}>
                  {/* Text renders what was typed as text: nothing here is interpreted */}
                  <Text style={{ color: mine ? t.accentInk : t.text, fontSize: 15 }}>{m.body}</Text>
                  <Text style={{ color: mine ? t.accentInk : t.muted, fontSize: 12, marginTop: 4 }}>{mine ? "You" : "Support"} · {dateTime(m.createdAt)}</Text>
                </View>
              );
            })}
          </View>
        )}
        {q.data?.status === "RESOLVED" ? <View style={{ marginTop: 12 }}><Badge>Marked resolved. Write again if you still need help.</Badge></View> : null}
      </Card>

      <Field label="Your message" value={text} onChangeText={(v) => setText(v.slice(0, MAX_SUPPORT_MESSAGE))} multiline numberOfLines={4} style={{ minHeight: 96, textAlignVertical: "top" }} />
      <Muted>{text.length}/{MAX_SUPPORT_MESSAGE}</Muted>
      {send.error ? <ErrorNote>{send.error.message}</ErrorNote> : null}
      <Button label="Send" busy={send.isPending} disabled={body.length === 0} onPress={() => send.mutate(body, { onSuccess: () => setText("") })} />
    </View>
  );
}
