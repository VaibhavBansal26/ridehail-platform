"use client";

import "leaflet/dist/leaflet.css";
import L from "leaflet";
import { Fragment, useEffect, useRef } from "react";
import { CircleMarker, MapContainer, Polyline, TileLayer, Tooltip, useMap } from "react-leaflet";
import type { LiveSnapshot } from "@/lib/schemas";
import { titleCase } from "@/lib/format";
import { RIDER_COLOR, STALE_MS, TRIP_COLOR, type Layers, type Selection } from "@/lib/map-constants";

function FitOnce({ points }: { points: [number, number][] }) {
  const map = useMap();
  const done = useRef(false);
  useEffect(() => {
    if (done.current || points.length === 0) return;
    done.current = true;
    map.fitBounds(L.latLngBounds(points), { padding: [40, 40], maxZoom: 15 });
  }, [map, points]);
  return null;
}

/** Lets the side panel ask the map to move: bumping `focus.n` re-centres on the point. */
function FlyTo({ focus }: { focus: { lat: number; lon: number; n: number } | null }) {
  const map = useMap();
  useEffect(() => {
    if (focus) map.flyTo([focus.lat, focus.lon], Math.max(map.getZoom(), 14), { duration: 0.6 });
  }, [map, focus]);
  return null;
}

export default function LiveMap({
  data, tileUrl, attribution, view, layers, selection, onSelect, focus, now,
}: {
  data: LiveSnapshot | undefined; tileUrl: string; attribution: string; view: [number, number, number];
  layers: Layers; selection: Selection; onSelect: (s: Selection) => void;
  focus: { lat: number; lon: number; n: number } | null; now: number;
}) {
  const riders = data?.riders ?? [];
  const trips = data?.trips ?? [];
  const riderById = new Map(riders.map((r) => [r.id, r]));
  const fitPoints: [number, number][] = [...riders.map((r) => [r.lat, r.lon] as [number, number]), ...trips.map((t) => [t.pickupLat, t.pickupLon] as [number, number])];

  return (
    <MapContainer center={[view[0], view[1]]} zoom={view[2]} className="h-full w-full" scrollWheelZoom>
      <TileLayer url={tileUrl} attribution={attribution} maxZoom={19} />
      <FitOnce points={fitPoints} />
      <FlyTo focus={focus} />

      {layers.trips && trips.map((t) => {
        const color = TRIP_COLOR[t.status] ?? "#888";
        const selected = selection?.kind === "trip" && selection.id === t.id;
        const rider = t.riderId ? riderById.get(t.riderId) : undefined;
        const headingToPickup = (t.status === "ACCEPTED" || t.status === "ARRIVED") && rider;
        return (
          <Fragment key={t.id}>
            <Polyline positions={[[t.pickupLat, t.pickupLon], [t.dropLat, t.dropLon]]} pathOptions={{ color, weight: selected ? 4 : 2, dashArray: "6 6", opacity: selected ? 1 : 0.6 }} />
            {headingToPickup ? <Polyline positions={[[rider.lat, rider.lon], [t.pickupLat, t.pickupLon]]} pathOptions={{ color, weight: 2, opacity: 0.9 }} /> : null}
            <CircleMarker center={[t.dropLat, t.dropLon]} radius={5} pathOptions={{ color: "#fff", weight: 2, fillColor: color, fillOpacity: 1 }} />
            <CircleMarker
              center={[t.pickupLat, t.pickupLon]} radius={selected ? 10 : 8}
              pathOptions={{ color: "#fff", weight: 2, fillColor: color, fillOpacity: 1 }}
              eventHandlers={{ click: () => onSelect({ kind: "trip", id: t.id }) }}
            >
              <Tooltip>{`${titleCase(t.status)} · ${t.customerName ?? "Customer"}${t.riderName ? " → " + t.riderName : ""}`}</Tooltip>
            </CircleMarker>
          </Fragment>
        );
      })}

      {layers.riders && riders.map((r) => {
        const seen = r.locationUpdatedAt ? new Date(r.locationUpdatedAt).getTime() : null;
        const stale = seen === null || now - seen > STALE_MS;
        const selected = selection?.kind === "rider" && selection.id === r.id;
        return (
          <CircleMarker
            key={r.id} center={[r.lat, r.lon]} radius={selected ? 10 : 7}
            pathOptions={{ color: stale ? "#d98200" : "#fff", weight: stale ? 3 : 2, fillColor: RIDER_COLOR[r.status] ?? "#888", fillOpacity: stale ? 0.55 : 1 }}
            eventHandlers={{ click: () => onSelect({ kind: "rider", id: r.id }) }}
          >
            <Tooltip>{`${r.name} · ${titleCase(r.status)} · ${titleCase(r.vehicleType)}${stale ? " · not reporting" : ""}`}</Tooltip>
          </CircleMarker>
        );
      })}
    </MapContainer>
  );
}
