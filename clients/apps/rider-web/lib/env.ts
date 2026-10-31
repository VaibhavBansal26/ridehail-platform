import "server-only";

/** Server-side configuration, read at request time and validated so a bad deployment fails loudly at the first request. */
function httpOrigin(raw: string, name: string): string {
  const url = new URL(raw);
  if (url.protocol !== "http:" && url.protocol !== "https:") throw new Error(`${name} must be http(s): ${raw}`);
  return url.origin;
}

function backendUrl(): string {
  return httpOrigin(process.env.BACKEND_URL ?? "http://localhost:8080", "BACKEND_URL");
}

/** ws(s)://host/ws that the browser opens for pushes. Defaults to the API's own host. */
export function realtimeUrl(): string {
  const raw = process.env.REALTIME_URL;
  if (raw) {
    const url = new URL(raw);
    if (url.protocol !== "ws:" && url.protocol !== "wss:") throw new Error(`REALTIME_URL must be ws(s): ${raw}`);
    return url.href;
  }
  return backendUrl().replace(/^http/, "ws") + "/ws";
}

export const env = {
  get backendUrl() {
    return backendUrl();
  },
  get realtimeUrl() {
    return realtimeUrl();
  },
  /** Cookies are Secure unless this is explicitly turned off. */
  get secureCookies() {
    return process.env.ALLOW_INSECURE_COOKIES !== "true";
  },
};
