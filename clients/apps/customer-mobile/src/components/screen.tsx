import type { ReactNode } from "react";
import { RefreshControl, ScrollView } from "react-native";
import { useSafeAreaInsets } from "react-native-safe-area-context";
import { useQueryClient } from "@tanstack/react-query";
import { useTheme } from "@/components/ui";

/** A scrolling page with the app's background; pull down to refetch everything. */
export function Screen({ children }: { children: ReactNode }) {
  const t = useTheme();
  const insets = useSafeAreaInsets();
  const qc = useQueryClient();
  return (
    <ScrollView
      style={{ flex: 1, backgroundColor: t.bg }}
      contentContainerStyle={{ padding: 16, paddingTop: 16 + insets.top, gap: 16 }}
      refreshControl={<RefreshControl refreshing={false} onRefresh={() => void qc.invalidateQueries()} />}
      keyboardShouldPersistTaps="handled"
    >
      {children}
    </ScrollView>
  );
}
