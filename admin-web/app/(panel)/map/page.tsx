import type { Metadata } from "next";
import { MapView } from "@/components/map-view";
import { env } from "@/lib/env";

export const metadata: Metadata = { title: "Live map" };

export default function MapPage() {
  // map settings come from the server's environment, so one build serves every deployment
  return <MapView tileUrl={env.tileUrl} attribution={env.tileAttribution} view={env.defaultView} />;
}
