import { Pressable, Text, View } from "react-native";
import { Badge, useTheme } from "@/components/ui";
import { useNotices } from "@/lib/notices";
import { useCustomer } from "@/lib/queries";
import { useConnection } from "@/lib/realtime";
import { useSession } from "@/lib/session";

/** Name, connection, sign-out, and the one short notice at a time. */
export function Header() {
  const t = useTheme();
  const customer = useCustomer();
  const connection = useConnection();
  const { signOut } = useSession();
  const { notice, clear } = useNotices();
  const tone = notice ? { info: [t.infoBg, t.infoFg], ok: [t.okBg, t.okFg], bad: [t.badBg, t.badFg] }[notice.tone] : null;

  return (
    <View style={{ gap: 12 }}>
      <View style={{ flexDirection: "row", justifyContent: "space-between", alignItems: "center" }}>
        <View style={{ gap: 4 }}>
          <Text style={{ color: t.text, fontSize: 17, fontWeight: "700" }}>{customer.data?.name ?? "Ridehail"}</Text>
          <View style={{ flexDirection: "row", gap: 6 }}>
            {connection === "reconnecting" ? <Badge tone="warn">Reconnecting…</Badge> : null}
          </View>
        </View>
        <Pressable accessibilityRole="button" accessibilityLabel="Sign out" onPress={() => void signOut()} style={{ minHeight: 44, justifyContent: "center", paddingHorizontal: 8 }}>
          <Text style={{ color: t.muted }}>Sign out</Text>
        </Pressable>
      </View>
      {notice && tone ? (
        <Pressable accessibilityRole="alert" onPress={clear} style={{ backgroundColor: tone[0], borderRadius: 10, padding: 12 }}>
          <Text style={{ color: tone[1] }}>{notice.text}</Text>
        </Pressable>
      ) : null}
    </View>
  );
}
