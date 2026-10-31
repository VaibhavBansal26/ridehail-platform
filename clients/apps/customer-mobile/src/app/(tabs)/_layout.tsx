import { Redirect, Tabs } from "expo-router";
import { ActivityIndicator, View } from "react-native";
import { PushBridge } from "@/components/push-bridge";
import { useSupport } from "@/lib/queries";
import { RealtimeProvider } from "@/lib/realtime";
import { useSession } from "@/lib/session";

export default function TabsLayout() {
  const { state } = useSession();
  const supportUnread = (useSupport().data?.unread ?? 0) > 0;
  if (state === "loading") return <View style={{ flex: 1, justifyContent: "center" }}><ActivityIndicator /></View>;
  if (state === "signedOut") return <Redirect href="/login" />;
  return (
    <RealtimeProvider>
      <PushBridge />
      <Tabs screenOptions={{ headerShown: false }}>
        <Tabs.Screen name="index" options={{ title: "Ride" }} />
        <Tabs.Screen name="account" options={{ title: "Account" }} />
        <Tabs.Screen name="support" options={{ title: "Support", tabBarBadge: supportUnread ? "•" : undefined }} />
      </Tabs>
    </RealtimeProvider>
  );
}
