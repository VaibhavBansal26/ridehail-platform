"use client";

import "leaflet/dist/leaflet.css";
import L from "leaflet";
import { Fragment, useCallback, useEffect, useRef } from "react";
import { CircleMarker, MapContainer, Marker, Polyline, TileLayer, Tooltip, useMap } from "react-leaflet";
import type { LiveSnapshot } from "@/lib/schemas";
import { titleCase } from "@/lib/format";
import { RIDER_COLOR, STALE_MS, TRIP_COLOR, type Layers, type Selection } from "@/lib/map-constants";
import { type VehicleType, vehicleSvg } from "@/lib/vehicle-art";

/**
 * A rider on the map: the picture of their vehicle on a ring in their status colour (green available, blue on a trip, grey offline), faded
 * when they have stopped reporting. The position has no heading here, so every vehicle points north.
 */
function riderIcon(type: VehicleType, color: string, stale: boolean, selected: boolean): L.DivIcon {
  const size = selected ? 52 : 44;
  const html =
    `<svg xmlns="http://www.w3.org/2000/svg" width="${size}" height="${size}" viewBox="0 0 44 44" aria-hidden="true" opacity="${stale ? 0.55 : 1}">` +
    `<circle cx="22" cy="22" r="20" fill="${color}" fill-opacity="0.22" stroke="${stale ? "#d98200" : color}" stroke-width="${selected ? 3.5 : 2.5}"${stale ? ' stroke-dasharray="4 3"' : ""}/>` +
    `<g transform="translate(5 5)">${vehicleSvg(type, 34)}</g></svg>`;
  return L.divIcon({ className: "rider-pin", html, iconSize: [size, size], iconAnchor: [size / 2, size / 2] });
}

/**
 * Frames everything once, when there is something to frame and the map has a real size. A map fitted while its container is still being laid
 * out would stay zoomed out, so the fit waits for a real size and is redone on every resize until the person moves the map themselves.
 */
/** The points within about a city's reach of the middle one, so a single stray position does not zoom the map out to a country. */
export function nearTheMiddle(points: [number, number][]): [number, number][] {
  if (points.length < 3) return points;
  const median = (v: number[]) => [...v].sort((a, b) => a - b)[Math.floor(v.length / 2)];
  const lat = median(points.map((p) => p[0]));
  const lon = median(points.map((p) => p[1]));
  const near = points.filter((p) => Math.abs(p[0] - lat) <= 1 && Math.abs(p[1] - lon) <= 1);
  return near.length > 0 ? near : points;
}

function FitOnce({ points }: { points: [number, number][] }) {
  const map = useMap();
  const touched = useRef(false);
  const fitted = useRef(false);
  const latest = useRef(points);
  const fit = useCallback(() => {
    const size = map.getSize();
    if (touched.current || latest.current.length === 0 || size.x < 200 || size.y < 120) return;
    fitted.current = true;
    map.fitBounds(L.latLngBounds(latest.current), { padding: [40, 40], maxZoom: 15, animate: false });
  }, [map]);
  useEffect(() => {
    latest.current = points;
    if (!fitted.current) fit();
  }, [points, fit]);
  useEffect(() => {
    const container = map.getContainer();
    const stop = () => { touched.current = true; };
    const watcher = new ResizeObserver(() => {
      map.invalidateSize();
      if (!fitted.current || !touched.current) fit();
    });
    watcher.observe(container);
    ["mousedown", "wheel", "touchstart"].forEach((e) => container.addEventListener(e, stop, { passive: true }));
    return () => {
      watcher.disconnect();
      ["mousedown", "wheel", "touchstart"].forEach((e) => container.removeEventListener(e, stop));
    };
  }, [map, fit]);
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
  const fitPoints = nearTheMiddle([...riders.map((r) => [r.lat, r.lon] as [number, number]), ...trips.map((t) => [t.pickupLat, t.pickupLon] as [number, number])]);

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
          <Marker
            key={r.id} position={[r.lat, r.lon]} icon={riderIcon(r.vehicleType, RIDER_COLOR[r.status] ?? "#888", stale, selected)}
            zIndexOffset={selected ? 1000 : 0} keyboard={false}
            eventHandlers={{ click: () => onSelect({ kind: "rider", id: r.id }) }}
          >
            <Tooltip>{`${r.name} · ${titleCase(r.status)} · ${titleCase(r.vehicleType)}${stale ? " · not reporting" : ""}`}</Tooltip>
          </Marker>
        );
      })}
    </MapContainer>
  );
}
