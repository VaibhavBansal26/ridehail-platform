import { ApiError } from "@ridehail/api";
import { Redirect } from "expo-router";
import { useState } from "react";
import { KeyboardAvoidingView, Platform, Pressable, ScrollView, Text, View } from "react-native";
import { useSafeAreaInsets } from "react-native-safe-area-context";
import { Button, Card, ErrorNote, Field, Muted, useTheme } from "@/components/ui";
import { useSession, WrongAccountError } from "@/lib/session";

export default function Login() {
  const t = useTheme();
  const insets = useSafeAreaInsets();
  const { state, signIn, register } = useSession();
  const [mode, setMode] = useState<"signin" | "register">("signin");
  const [name, setName] = useState("");
  const [phone, setPhone] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  if (state === "signedIn") return <Redirect href="/" />;

  async function submit() {
    setError(null);
    if (!phone.trim() || !password) return setError("Enter your phone number and password.");
    if (mode === "register" && (!name.trim() || password.length < 8)) return setError("Enter your name and a password of at least 8 characters.");
    setBusy(true);
    try {
      if (mode === "signin") await signIn(phone, password);
      else await register({ name, phone, password });
    } catch (e) {
      // a customer's or admin's account is refused like a wrong password: no hint about which accounts exist
      setError(e instanceof WrongAccountError ? e.message : e instanceof ApiError && e.status === 409 ? "That phone number is already registered. Sign in instead." : e instanceof ApiError ? e.message : "Something went wrong.");
      setBusy(false);
    }
  }

  return (
    <KeyboardAvoidingView behavior={Platform.OS === "ios" ? "padding" : undefined} style={{ flex: 1, backgroundColor: t.bg }}>
      <ScrollView contentContainerStyle={{ flexGrow: 1, justifyContent: "center", padding: 16, paddingTop: 16 + insets.top, gap: 16 }} keyboardShouldPersistTaps="handled">
        <View style={{ gap: 4 }}>
          <Text style={{ color: t.text, fontSize: 28, fontWeight: "700" }}>Ridehail</Text>
          <Muted>Get a ride in minutes.</Muted>
        </View>
        <Card>
          <View accessibilityRole="tablist" style={{ flexDirection: "row", backgroundColor: t.line, borderRadius: 10, padding: 4, gap: 4 }}>
            {(["signin", "register"] as const).map((m) => (
              <Pressable key={m} accessibilityRole="tab" accessibilityState={{ selected: mode === m }} onPress={() => { setMode(m); setError(null); }} style={{ flex: 1, minHeight: 40, borderRadius: 8, alignItems: "center", justifyContent: "center", backgroundColor: mode === m ? t.surface : "transparent" }}>
                <Text style={{ color: mode === m ? t.text : t.muted, fontWeight: "600" }}>{m === "signin" ? "Sign in" : "Create account"}</Text>
              </Pressable>
            ))}
          </View>
          {mode === "register" ? <Field label="Your name" value={name} onChangeText={setName} autoComplete="name" /> : null}
          <Field label="Phone number" value={phone} onChangeText={setPhone} keyboardType="phone-pad" autoComplete="tel" autoCapitalize="none" />
          <Field label="Password" value={password} onChangeText={setPassword} secureTextEntry autoComplete={mode === "signin" ? "current-password" : "new-password"} autoCapitalize="none" />
          {error ? <ErrorNote>{error}</ErrorNote> : null}
          <Button label={mode === "signin" ? "Sign in" : "Create account"} busy={busy} onPress={() => void submit()} />
        </Card>
      </ScrollView>
    </KeyboardAvoidingView>
  );
}
