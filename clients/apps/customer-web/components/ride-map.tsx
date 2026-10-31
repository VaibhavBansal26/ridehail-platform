"use client";

import dynamic from "next/dynamic";
import { useMapSettings } from "@/components/map-settings";
import type { RideMapProps } from "@/components/ride-map-inner";

// Leaflet needs a real browser (window), so it is never rendered on the server
const Inner = dynamic(() => import("@/components/ride-map-inner"), {
  ssr: false,
  loading: () => <div aria-hidden className="h-full w-full animate-pulse bg-surface-2" />,
});

/** The map card: where the ride starts and ends, and the vehicle itself once it is moving. `height` is a Tailwind class. */
export function RideMap({ height = "h-60", label = "Map of your ride", ...props }: Omit<RideMapProps, "tileUrl" | "attribution"> & { height?: string; label?: string }) {
  const settings = useMapSettings();
  return (
    <section aria-label={label} className={`relative isolate overflow-hidden rounded-xl border border-line ${height}`}>
      <Inner {...props} tileUrl={settings.tileUrl} attribution={settings.attribution} />
    </section>
  );
}
