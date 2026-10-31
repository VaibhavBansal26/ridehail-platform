import { Redirect, Tabs } from "expo-router";
import { ActivityIndicator, View } from "react-native";
import { PushBridge } from "@/components/push-bridge";
import { useVerification } from "@/lib/queries";
import { RealtimeProvider } from "@/lib/realtime";
import { useSession } from "@/lib/session";

export default function TabsLayout() {
  const { state } = useSession();
  // the Verify tab exists only where verification is required (href: null hides a tab but keeps the screen reachable by link)
  const verification = useVerification();
  const needsVerification = verification.data?.required === true;
  if (state === "loading") return <View style={{ flex: 1, justifyContent: "center" }}><ActivityIndicator /></View>;
  if (state === "signedOut") return <Redirect href="/login" />;
  return (
    <RealtimeProvider>
      <PushBridge />
      <Tabs screenOptions={{ headerShown: false }}>
        <Tabs.Screen name="index" options={{ title: "Drive" }} />
        <Tabs.Screen name="earnings" options={{ title: "Earnings" }} />
        <Tabs.Screen name="verification" options={{ title: "Verify", href: needsVerification ? undefined : null, tabBarBadge: needsVerification && verification.data?.status !== "APPROVED" ? "!" : undefined }} />
      </Tabs>
    </RealtimeProvider>
  );
}
