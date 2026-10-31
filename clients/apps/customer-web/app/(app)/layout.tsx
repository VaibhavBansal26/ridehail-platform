import type { ReactNode } from "react";
import { RealtimeProvider } from "@/components/realtime";
import { Shell } from "@/components/shell";

export default function AppLayout({ children }: { children: ReactNode }) {
  return (
    <RealtimeProvider>
      <Shell>{children}</Shell>
    </RealtimeProvider>
  );
}
