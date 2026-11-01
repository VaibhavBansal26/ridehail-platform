import { type VehicleType, vehicleSvg } from "@ridehail/api";

// the pictures themselves (SVG strings, drawn from presentation attributes only) are in @ridehail/api, shared with the phone apps
export { pinSvg, vehicleSvg } from "@ridehail/api";

/** The picture of a vehicle for buttons and lists. */
export function VehicleIcon({ type, size = 32 }: { type: VehicleType; size?: number }) {
  return <span aria-hidden className="inline-flex" dangerouslySetInnerHTML={{ __html: vehicleSvg(type, size) }} />;
}
