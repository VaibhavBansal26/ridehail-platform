"use client";

import "leaflet/dist/leaflet.css";
import type { VehicleType } from "@ridehail/api";
import L from "leaflet";
import { useEffect, useMemo, useRef } from "react";
import { MapContainer, Marker, Polyline, TileLayer, useMap } from "react-leaflet";
import { pinSvg, vehicleSvg } from "@/components/vehicle-art";
import type { LatLon } from "@/lib/geo";

export type Car = LatLon & { heading?: number; vehicle: VehicleType };
export type RideMapProps = { pickup?: LatLon | null; drop?: LatLon | null; car?: Car | null; tileUrl: string; attribution: string };

const FALLBACK_CENTRE: [number, number] = [12.9716, 77.5946];
const pinIcon = (kind: "pickup" | "drop") => L.divIcon({ className: "ride-pin", html: pinSvg(kind), iconSize: [28, 36], iconAnchor: [14, 34] });
// the vehicle's picture is drawn once pointing north and turned by a style set on the element, so that the marker element survives
// every position update and its CSS transition (globals.css) glides it from one fix to the next
const carIcon = (vehicle: VehicleType) => L.divIcon({ className: "ride-car", html: `<div class="ride-car-art">${vehicleSvg(vehicle)}</div>`, iconSize: [40, 40], iconAnchor: [20, 20] });

/** Keeps the pickup, drop-off and car in view: refit when the trip's ends change, and only pan when the car leaves the screen. */
function Framing({ pickup, drop, car }: { pickup?: LatLon | null; drop?: LatLon | null; car?: LatLon | null }) {
  const map = useMap();
  const fitted = useRef("");
  const key = [pickup, drop].map((p) => (p ? `${p.lat.toFixed(5)},${p.lon.toFixed(5)}` : "-")).join("|") + (car ? "|car" : "");
  useEffect(() => {
    const pts = [pickup, drop, car].filter((p): p is LatLon => !!p).map((p) => [p.lat, p.lon] as [number, number]);
    if (pts.length === 0 || fitted.current === key) return;
    fitted.current = key;
    if (pts.length === 1) map.setView(pts[0]!, 15);
    else map.fitBounds(L.latLngBounds(pts), { padding: [36, 36], maxZoom: 16 });
  }, [map, key, pickup, drop, car]);
  useEffect(() => {
    if (car && !map.getBounds().pad(-0.1).contains([car.lat, car.lon])) map.panTo([car.lat, car.lon], { animate: true });
  }, [map, car?.lat, car?.lon]); // eslint-disable-line react-hooks/exhaustive-deps
  return null;
}

function CarMarker({ car }: { car: Car }) {
  const ref = useRef<L.Marker>(null);
  const icon = useMemo(() => carIcon(car.vehicle), [car.vehicle]);
  useEffect(() => {
    const art = ref.current?.getElement()?.querySelector<HTMLElement>(".ride-car-art");
    if (art) art.style.transform = `rotate(${car.heading ?? 0}deg)`;
  }, [car.heading, icon]);
  return <Marker ref={ref} position={[car.lat, car.lon]} icon={icon} zIndexOffset={1000} keyboard={false} />;
}

export default function RideMapInner({ pickup, drop, car, tileUrl, attribution }: RideMapProps) {
  const pins = useMemo(() => ({ pickup: pinIcon("pickup"), drop: pinIcon("drop") }), []);
  const start = pickup ?? drop ?? car ?? { lat: FALLBACK_CENTRE[0], lon: FALLBACK_CENTRE[1] };
  return (
    <MapContainer center={[start.lat, start.lon]} zoom={14} className="h-full w-full" scrollWheelZoom={false} attributionControl>
      <TileLayer url={tileUrl} attribution={attribution} maxZoom={19} />
      <Framing pickup={pickup} drop={drop} car={car} />
      {pickup && drop ? <Polyline positions={[[pickup.lat, pickup.lon], [drop.lat, drop.lon]]} pathOptions={{ color: "#2557d6", weight: 3, dashArray: "6 8", opacity: 0.7 }} /> : null}
      {drop ? <Marker position={[drop.lat, drop.lon]} icon={pins.drop} keyboard={false} /> : null}
      {pickup ? <Marker position={[pickup.lat, pickup.lon]} icon={pins.pickup} keyboard={false} /> : null}
      {car ? <CarMarker car={car} /> : null}
    </MapContainer>
  );
}
