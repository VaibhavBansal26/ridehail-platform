"use client";

import dynamic from "next/dynamic";
import { useState } from "react";
import { Crosshair } from "lucide-react";
import { Badge, ErrorBox, Loading, StatusBadge, useNow } from "@/components/ui";
import { RIDER_COLOR, STALE_MS, TRIP_COLOR, type Layers, type Selection } from "@/lib/map-constants";
import { relative, titleCase } from "@/lib/format";
import { useLive } from "@/lib/queries";

// Leaflet needs a real browser (window), so it is never rendered on the server
const LiveMap = dynamic(() => import("@/components/live-map"), { ssr: false, loading: () => <Loading label="Loading map" /> });

export function MapView({ tileUrl, attribution, view }: { tileUrl: string; attribution: string; view: [number, number, number] }) {
  const live = useLive();
  const now = useNow(5_000);
  const [layers, setLayers] = useState<Layers>({ riders: true, trips: true });
  const [selection, setSelection] = useState<Selection>(null);
  const [focus, setFocus] = useState<{ lat: number; lon: number; n: number } | null>(null);
  const data = live.data;

  const select = (s: Selection, lat?: number, lon?: number) => {
    setSelection(s);
    if (lat !== undefined && lon !== undefined) setFocus({ lat, lon, n: (focus?.n ?? 0) + 1 });
  };

  const staleRiders = data ? data.riders.filter((r) => !r.locationUpdatedAt || now - new Date(r.locationUpdatedAt).getTime() > STALE_MS).length : 0;

  return (
    <div className="flex h-[calc(100dvh-8rem)] min-h-[28rem] flex-col gap-3 md:h-[calc(100dvh-5rem)] lg:flex-row">
      <div className="relative min-h-72 flex-1 overflow-hidden rounded-lg border border-line">
        <LiveMap data={data} tileUrl={tileUrl} attribution={attribution} view={view} layers={layers} selection={selection} onSelect={(s) => setSelection(s)} focus={focus} now={now} />
      </div>

      <aside className="flex max-h-72 w-full flex-col gap-3 overflow-y-auto rounded-lg border border-line bg-surface p-4 lg:max-h-none lg:w-80" aria-label="Map details">
        <div className="flex items-center justify-between">
          <h1 className="text-base font-semibold">Live map</h1>
          {live.isError ? <Badge tone="bad">Offline</Badge> : data ? <span className="text-xs text-muted">Updated {relative(data.generatedAt, now)}</span> : null}
        </div>
        {live.isError ? <ErrorBox error={live.error} onRetry={() => live.refetch()} /> : null}
        {data?.truncated ? <p className="rounded-md bg-[var(--warn-bg)] p-2 text-xs text-[var(--warn-fg)]">Showing the first 5,000 riders and trips only.</p> : null}

        <fieldset className="space-y-1.5 text-sm">
          <legend className="sr-only">Layers</legend>
          <label className="flex items-center gap-2"><input type="checkbox" checked={layers.riders} onChange={(e) => setLayers({ ...layers, riders: e.target.checked })} /> Riders ({data?.riders.length ?? 0}){staleRiders > 0 ? <span className="text-xs text-[var(--warn-fg)]">{staleRiders} not reporting</span> : null}</label>
          <label className="flex items-center gap-2"><input type="checkbox" checked={layers.trips} onChange={(e) => setLayers({ ...layers, trips: e.target.checked })} /> Active trips ({data?.trips.length ?? 0})</label>
        </fieldset>

        <ul className="flex flex-wrap gap-x-3 gap-y-1 text-xs text-muted" aria-label="Legend">
          {Object.entries({ ...RIDER_COLOR, ...TRIP_COLOR }).filter(([k]) => k !== "OFFLINE").map(([k, c]) => (
            <li key={k} className="flex items-center gap-1"><span className="inline-block size-2.5 rounded-full" style={{ background: c }} />{titleCase(k)}</li>
          ))}
        </ul>

        <div className="min-h-0 flex-1 space-y-4 overflow-y-auto">
          <section>
            <h2 className="mb-1.5 text-xs font-medium uppercase tracking-wide text-muted">Active trips</h2>
            {!data ? null : data.trips.length === 0 ? <p className="text-sm text-muted">Nothing on the road.</p> : (
              <ul className="space-y-1">
                {data.trips.map((t) => (
                  <li key={t.id}>
                    <button
                      onClick={() => select({ kind: "trip", id: t.id }, t.pickupLat, t.pickupLon)} aria-pressed={selection?.kind === "trip" && selection.id === t.id}
                      className={`flex w-full items-center justify-between gap-2 rounded-md px-2 py-1.5 text-left text-sm hover:bg-surface-2 ${selection?.kind === "trip" && selection.id === t.id ? "bg-surface-2" : ""}`}
                    >
                      <span className="min-w-0 truncate">{t.customerName ?? "Customer"}{t.riderName ? <span className="text-muted"> → {t.riderName}</span> : null}</span>
                      <StatusBadge status={t.status} />
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </section>
          <section>
            <h2 className="mb-1.5 text-xs font-medium uppercase tracking-wide text-muted">Riders online</h2>
            {!data ? null : data.riders.length === 0 ? <p className="text-sm text-muted">No riders online.</p> : (
              <ul className="space-y-1">
                {data.riders.map((r) => (
                  <li key={r.id}>
                    <button
                      onClick={() => select({ kind: "rider", id: r.id }, r.lat, r.lon)} aria-pressed={selection?.kind === "rider" && selection.id === r.id}
                      className={`flex w-full items-center justify-between gap-2 rounded-md px-2 py-1.5 text-left text-sm hover:bg-surface-2 ${selection?.kind === "rider" && selection.id === r.id ? "bg-surface-2" : ""}`}
                    >
                      <span className="flex min-w-0 items-center gap-2"><Crosshair className="size-3.5 shrink-0 text-muted" aria-hidden /><span className="truncate">{r.name}</span></span>
                      <span className="shrink-0 text-xs text-muted">{r.locationUpdatedAt ? relative(r.locationUpdatedAt, now) : "no fix"}</span>
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </section>
        </div>
      </aside>
    </div>
  );
}
