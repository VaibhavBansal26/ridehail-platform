import type { ReactNode } from "react";
import { MapSettingsProvider } from "@/components/map-settings";
import { RealtimeProvider } from "@/components/realtime";
import { Shell } from "@/components/shell";
import { mapSettingsFromEnv } from "@/lib/map-settings";

export default function AppLayout({ children }: { children: ReactNode }) {
  return (
    <MapSettingsProvider value={mapSettingsFromEnv()}>
      <RealtimeProvider>
        <Shell>{children}</Shell>
      </RealtimeProvider>
    </MapSettingsProvider>
  );
}
