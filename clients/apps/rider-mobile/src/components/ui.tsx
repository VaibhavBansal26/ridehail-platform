import type { ReactNode } from "react";
import { ActivityIndicator, Pressable, StyleSheet, Text, TextInput, type TextInputProps, useColorScheme, View } from "react-native";

const LIGHT = { bg: "#f6f7f9", surface: "#ffffff", line: "#e1e4ea", text: "#14171f", muted: "#5d6677", accent: "#2557d6", accentInk: "#ffffff", okBg: "#e3f5ea", okFg: "#14683a", warnBg: "#fdf0d5", warnFg: "#8a5a00", badBg: "#fde6e4", badFg: "#a3231a", infoBg: "#e4ecfd", infoFg: "#1f4bb5" };
const DARK = { bg: "#0e1116", surface: "#161a22", line: "#2a303c", text: "#e8ebf1", muted: "#96a0b2", accent: "#6d95ff", accentInk: "#0b1020", okBg: "#123524", okFg: "#6fd49a", warnBg: "#3a2c0c", warnFg: "#f1c566", badBg: "#411a17", badFg: "#ff9a92", infoBg: "#14264f", infoFg: "#9db9ff" };
export type Theme = typeof LIGHT;
export const useTheme = (): Theme => (useColorScheme() === "dark" ? DARK : LIGHT);

export type Tone = "ok" | "warn" | "bad" | "info" | "neutral";

export function Card({ title, children }: { title?: string; children: ReactNode }) {
  const t = useTheme();
  return (
    <View style={[s.card, { backgroundColor: t.surface, borderColor: t.line }]}>
      {title ? <Text style={[s.cardTitle, { color: t.text, borderColor: t.line }]}>{title}</Text> : null}
      <View style={s.cardBody}>{children}</View>
    </View>
  );
}

export function Button({ label, onPress, variant = "primary", busy = false, disabled = false }: { label: string; onPress: () => void; variant?: "primary" | "secondary"; busy?: boolean; disabled?: boolean }) {
  const t = useTheme();
  const primary = variant === "primary";
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={label}
      accessibilityState={{ disabled: disabled || busy, busy }}
      disabled={disabled || busy}
      onPress={onPress}
      style={({ pressed }) => [s.button, primary ? { backgroundColor: t.accent } : { borderColor: t.line, borderWidth: 1, backgroundColor: t.surface }, (disabled || busy) && { opacity: 0.5 }, pressed && { opacity: 0.8 }]}
    >
      {busy ? <ActivityIndicator color={primary ? t.accentInk : t.text} /> : null}
      <Text style={[s.buttonText, { color: primary ? t.accentInk : t.text }]}>{label}</Text>
    </Pressable>
  );
}

export function ErrorNote({ children }: { children: ReactNode }) {
  const t = useTheme();
  return (
    <View accessibilityRole="alert" style={[s.note, { backgroundColor: t.badBg }]}>
      <Text style={{ color: t.badFg, fontSize: 14 }}>{children}</Text>
    </View>
  );
}

export function Badge({ tone = "neutral", children }: { tone?: Tone; children: ReactNode }) {
  const t = useTheme();
  const colors: Record<Tone, [string, string]> = { ok: [t.okBg, t.okFg], warn: [t.warnBg, t.warnFg], bad: [t.badBg, t.badFg], info: [t.infoBg, t.infoFg], neutral: [t.line, t.muted] };
  const [bg, fg] = colors[tone];
  return (
    <View style={[s.badge, { backgroundColor: bg }]}>
      <Text style={{ color: fg, fontSize: 12, fontWeight: "600" }}>{children}</Text>
    </View>
  );
}

const STATUS_TONE: Record<string, Tone> = { OFFLINE: "neutral", AVAILABLE: "ok", ON_TRIP: "info", PENDING: "warn", AWAITING_FUNDS: "warn", PAID: "ok", FAILED: "bad", REQUIRES_ATTENTION: "bad" };
export function StatusBadge({ status }: { status: string }) {
  return <Badge tone={STATUS_TONE[status] ?? "neutral"}>{status.toLowerCase().replace(/_/g, " ").replace(/^\w/, (c) => c.toUpperCase())}</Badge>;
}

export function Muted({ children, style }: { children: ReactNode; style?: object }) {
  const t = useTheme();
  return <Text style={[{ color: t.muted, fontSize: 14 }, style]}>{children}</Text>;
}

export function Field({ label, ...input }: { label: string } & TextInputProps) {
  const t = useTheme();
  return (
    <View style={{ gap: 4 }}>
      <Text style={{ color: t.text, fontWeight: "600", fontSize: 14 }}>{label}</Text>
      <TextInput accessibilityLabel={label} placeholderTextColor={t.muted} style={[s.input, { color: t.text, borderColor: t.line, backgroundColor: t.surface }]} {...input} />
    </View>
  );
}

export function Stat({ label, value, big = false, warn = false }: { label: string; value: string; big?: boolean; warn?: boolean }) {
  const t = useTheme();
  return (
    <View>
      <Text style={{ color: t.muted, fontSize: 12 }}>{label}</Text>
      <Text style={{ color: warn ? t.warnFg : t.text, fontSize: big ? 32 : 18, fontWeight: "600" }}>{value}</Text>
    </View>
  );
}

const s = StyleSheet.create({
  card: { borderWidth: 1, borderRadius: 14, overflow: "hidden" },
  cardTitle: { fontSize: 14, fontWeight: "600", paddingHorizontal: 16, paddingVertical: 10, borderBottomWidth: 1 },
  cardBody: { padding: 16, gap: 12 },
  button: { minHeight: 48, borderRadius: 10, paddingHorizontal: 16, flexDirection: "row", alignItems: "center", justifyContent: "center", gap: 8 },
  buttonText: { fontSize: 16, fontWeight: "600" },
  note: { borderRadius: 10, paddingHorizontal: 12, paddingVertical: 8 },
  badge: { alignSelf: "flex-start", borderRadius: 999, paddingHorizontal: 8, paddingVertical: 2 },
  input: { minHeight: 48, borderWidth: 1, borderRadius: 10, paddingHorizontal: 12, fontSize: 16 },
});
