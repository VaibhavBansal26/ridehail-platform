"use client";

import { createContext, type ReactNode, useContext } from "react";
import { DEFAULT_ATTRIBUTION, DEFAULT_TILE_URL, type MapSettings } from "@/lib/map-settings";

const Context = createContext<MapSettings>({ tileUrl: DEFAULT_TILE_URL, attribution: DEFAULT_ATTRIBUTION });

/** Where the map tiles come from, handed down from the server's environment (see app/(app)/layout.tsx). */
export function MapSettingsProvider({ value, children }: { value: MapSettings; children: ReactNode }) {
  return <Context.Provider value={value}>{children}</Context.Provider>;
}

export const useMapSettings = () => useContext(Context);
