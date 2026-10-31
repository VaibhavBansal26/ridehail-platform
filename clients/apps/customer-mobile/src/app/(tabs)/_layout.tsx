import { Redirect, Tabs } from "expo-router";
import { ActivityIndicator, View } from "react-native";
import { RealtimeProvider } from "@/lib/realtime";
import { useSession } from "@/lib/session";

export default function TabsLayout() {
  const { state } = useSession();
  if (state === "loading") return <View style={{ flex: 1, justifyContent: "center" }}><ActivityIndicator /></View>;
  if (state === "signedOut") return <Redirect href="/login" />;
  return (
    <RealtimeProvider>
      <Tabs screenOptions={{ headerShown: false }}>
        <Tabs.Screen name="index" options={{ title: "Ride" }} />
        <Tabs.Screen name="account" options={{ title: "Account" }} />
      </Tabs>
    </RealtimeProvider>
  );
}
