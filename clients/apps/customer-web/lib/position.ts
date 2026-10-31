export type Fix = { lat: number; lon: number };

/** The browser's current position, once. (The customer's position is only needed to say where to be picked up.) */
export function currentPosition(): Promise<Fix> {
  return new Promise((resolve, reject) => {
    if (typeof navigator === "undefined" || !navigator.geolocation) return reject(new Error("This browser cannot share a position."));
    navigator.geolocation.getCurrentPosition(
      (p) => resolve({ lat: p.coords.latitude, lon: p.coords.longitude }),
      (e) =>
        reject(
          new Error(
            e.code === e.PERMISSION_DENIED
              ? "Location access is blocked. Allow it in your browser's site settings, or enter your pickup point below."
              : "Your position is not available right now. Enter your pickup point below.",
          ),
        ),
      { enableHighAccuracy: true, timeout: 10_000 },
    );
  });
}

export function parseCoordinates(lat: string, lon: string): Fix | null {
  const la = Number(lat), lo = Number(lon);
  return lat.trim() !== "" && lon.trim() !== "" && Number.isFinite(la) && Number.isFinite(lo) && Math.abs(la) <= 90 && Math.abs(lo) <= 180 ? { lat: la, lon: lo } : null;
}
