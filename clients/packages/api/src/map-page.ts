import { type LatLon, pinSvg, vehicleSvg } from "./map";
import type { VehicleType } from "./schemas";

/*
 * The map the phone apps show: a small web page (Leaflet and OpenStreetMap-style tiles) that the app puts in a WebView and drives with
 * mapStateScript(). A WebView needs no native map library and no Google or Apple map key, and it draws exactly what the web apps draw.
 * Leaflet itself is loaded from a CDN with a subresource-integrity hash, so a tampered file is refused: the maps need the network anyway,
 * for their tiles.
 */
export const LEAFLET_VERSION = "1.9.4";
export const LEAFLET_JS = { url: `https://unpkg.com/leaflet@${LEAFLET_VERSION}/dist/leaflet.js`, integrity: "sha256-20nQCchB9co0qIjJZRGuk2/Z9VM+kNiyxNV1lvTlZBo=" };
export const LEAFLET_CSS = { url: `https://unpkg.com/leaflet@${LEAFLET_VERSION}/dist/leaflet.css`, integrity: "sha256-p4NxAoJBhIIN+hmNHrzRCf9tD/miZyoHS5obTRR9BMY=" };

/** What the map shows. `route` is the road as [lat, lon] pairs; without it a straight dashed line joins the pickup and drop-off. */
export type MapState = {
  pickup?: LatLon | null;
  drop?: LatLon | null;
  car?: (LatLon & { heading?: number; vehicle: VehicleType }) | null;
  route?: [number, number][] | null;
};

export interface MapPageOptions {
  tileUrl: string;
  attribution: string;
}

/** JSON that is safe to place inside a <script> element: no "</script>" or HTML comment can end it early. */
function inlineJson(value: unknown): string {
  return JSON.stringify(value).replace(/</g, "\\u003c").replace(/>/g, "\\u003e").replace(/&/g, "\\u0026").replace(/\u2028/g, "\\u2028").replace(/\u2029/g, "\\u2029");
}

/** The page. It tells the app "ready" (a postMessage) once it can take its first state. */
export function mapPageHtml({ tileUrl, attribution }: MapPageOptions): string {
  const art = { pickup: pinSvg("pickup"), drop: pinSvg("drop"), BIKE: vehicleSvg("BIKE"), AUTO: vehicleSvg("AUTO"), SEDAN: vehicleSvg("SEDAN"), SUV: vehicleSvg("SUV") };
  return `<!doctype html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no">
<link rel="stylesheet" href="${LEAFLET_CSS.url}" integrity="${LEAFLET_CSS.integrity}" crossorigin="">
<style>
html, body, #map { height: 100%; margin: 0; background: #f0f2f5; }
.ride-pin, .ride-car { background: none; border: 0; }
.ride-car { transition: transform 4s linear; }
.ride-car-art { width: 40px; height: 40px; transform-origin: 50% 50%; transition: transform 0.6s ease; filter: drop-shadow(0 2px 3px rgba(0, 0, 0, 0.35)); }
</style></head>
<body><div id="map"></div>
<script src="${LEAFLET_JS.url}" integrity="${LEAFLET_JS.integrity}" crossorigin=""></script>
<script>
(function () {
  var TILE = ${inlineJson(tileUrl)}, ATTR = ${inlineJson(attribution)}, ART = ${inlineJson(art)};
  var map = L.map('map', { zoomControl: false, scrollWheelZoom: false }).setView([12.9716, 77.5946], 13);
  L.tileLayer(TILE, { attribution: ATTR, maxZoom: 19 }).addTo(map);
  var pins = { pickup: null, drop: null }, line = null, car = null, carVehicle = null, fitted = '';
  function ll(p) { return [p.lat, p.lon]; }
  function pinIcon(kind) { return L.divIcon({ className: 'ride-pin', html: ART[kind], iconSize: [28, 36], iconAnchor: [14, 34] }); }
  function carIcon(v) { return L.divIcon({ className: 'ride-car', html: '<div class="ride-car-art">' + ART[v] + '</div>', iconSize: [40, 40], iconAnchor: [20, 20] }); }
  function at(p) { return p ? p.lat.toFixed(5) + ',' + p.lon.toFixed(5) : '-'; }

  window.setState = function (s) {
    s = s || {};
    ['pickup', 'drop'].forEach(function (k) {
      if (s[k]) { if (pins[k]) pins[k].setLatLng(ll(s[k])); else pins[k] = L.marker(ll(s[k]), { icon: pinIcon(k), keyboard: false }).addTo(map); }
      else if (pins[k]) { map.removeLayer(pins[k]); pins[k] = null; }
    });
    if (line) { map.removeLayer(line); line = null; }
    var road = !!(s.route && s.route.length >= 2);
    if (road) line = L.polyline(s.route, { color: '#2557d6', weight: 5, opacity: 0.85, lineCap: 'round', lineJoin: 'round' }).addTo(map);
    else if (s.pickup && s.drop) line = L.polyline([ll(s.pickup), ll(s.drop)], { color: '#2557d6', weight: 3, dashArray: '6 8', opacity: 0.7 }).addTo(map);

    if (s.car) {
      // the marker element is kept across position updates so the CSS transition glides it; only a different vehicle makes a new one
      if (!car || carVehicle !== s.car.vehicle) {
        if (car) map.removeLayer(car);
        car = L.marker(ll(s.car), { icon: carIcon(s.car.vehicle), zIndexOffset: 1000, keyboard: false }).addTo(map);
        carVehicle = s.car.vehicle;
      } else car.setLatLng(ll(s.car));
      var art = car.getElement() && car.getElement().querySelector('.ride-car-art');
      if (art) art.style.transform = 'rotate(' + (s.car.heading || 0) + 'deg)';
    } else if (car) { map.removeLayer(car); car = null; carVehicle = null; }

    var key = at(s.pickup) + '|' + at(s.drop) + (s.car ? '|car' : '') + (road ? '|road' : '');
    var pts = [];
    [s.pickup, s.drop, s.car].forEach(function (p) { if (p) pts.push(ll(p)); });
    if (road) s.route.forEach(function (p) { pts.push(p); });
    if (pts.length && key !== fitted) {
      fitted = key;
      if (pts.length === 1) map.setView(pts[0], 15); else map.fitBounds(L.latLngBounds(pts), { padding: [36, 36], maxZoom: 16 });
    } else if (s.car && !map.getBounds().pad(-0.1).contains(ll(s.car))) map.panTo(ll(s.car));
  };

  window.__ridehailMapReady = true;
  if (window.ReactNativeWebView) window.ReactNativeWebView.postMessage('ready');
})();
</script></body></html>`;
}

/** The line of JavaScript that gives the page a new state. Ends in `true;` as React Native's injectJavaScript requires. */
export function mapStateScript(state: MapState): string {
  return `window.setState(${inlineJson(state)});true;`;
}
