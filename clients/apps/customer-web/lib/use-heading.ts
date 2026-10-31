import { useState } from "react";
import { bearing, type LatLon, metres } from "@/lib/geo";

/** Which way a moving vehicle is pointing, worked out from its last two distinct positions (under 4 m of movement is GPS noise). */
export function useHeading(position: LatLon | null | undefined): number | undefined {
  const [previous, setPrevious] = useState<LatLon | null>(position ?? null);
  const [heading, setHeading] = useState<number | undefined>(undefined);
  // derived from the previous render's position, so it is adjusted while rendering rather than in an effect
  if (position && (!previous || previous.lat !== position.lat || previous.lon !== position.lon)) {
    if (previous && metres(previous, position) >= 4) setHeading(bearing(previous, position));
    if (!previous || metres(previous, position) >= 4) setPrevious(position);
  }
  return heading;
}
