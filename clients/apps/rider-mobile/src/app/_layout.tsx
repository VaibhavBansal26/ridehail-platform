import { QueryClient, QueryClientProvider, focusManager } from "@tanstack/react-query";
import { Stack } from "expo-router";
import { useEffect, useState } from "react";
import { AppState, Platform } from "react-native";
// imported for its side effect: the background location task must be defined when the app starts, even headless
import "@/lib/background";
import { NoticeProvider } from "@/lib/notices";
import { SessionProvider } from "@/lib/session";

export default function RootLayout() {
  const [client] = useState(
    () =>
      new QueryClient({
        defaultOptions: {
          queries: {
            staleTime: 2_000,
            // a 401 means the session ended; do not hammer the API while the app returns to sign-in
            retry: (count, error) => count < 2 && !(error as { status?: number }).status,
          },
        },
      }),
  );

  // refetch when the app comes back to the front (the phone's version of "window focus")
  useEffect(() => {
    if (Platform.OS === "web") return;
    const sub = AppState.addEventListener("change", (s) => focusManager.setFocused(s === "active"));
    return () => sub.remove();
  }, []);

  return (
    <QueryClientProvider client={client}>
      <NoticeProvider>
        <SessionProvider>
          <Stack screenOptions={{ headerShown: false }} />
        </SessionProvider>
      </NoticeProvider>
    </QueryClientProvider>
  );
}
