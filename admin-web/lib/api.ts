import { z } from "zod";
import { CSRF_HEADER, CSRF_VALUE } from "@/lib/session";

/** Browser-side client for the same-origin BFF. Every response is validated against its schema. */

export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
  ) {
    super(message);
    this.name = "ApiError";
  }
}

async function errorMessage(res: Response): Promise<string> {
  try {
    const body = (await res.json()) as { detail?: unknown; message?: unknown; title?: unknown };
    for (const v of [body.detail, body.message, body.title]) if (typeof v === "string" && v) return v;
  } catch {
    /* not JSON */
  }
  return res.status >= 500 ? "The server had a problem. Try again." : `Request failed (${res.status})`;
}

function toLogin() {
  if (typeof window !== "undefined" && window.location.pathname !== "/login") {
    const here = window.location.pathname + window.location.search;
    // full page load on purpose: it drops every cached query along with the dead session
    // eslint-disable-next-line @next/next/no-location-assign-relative-destination
    window.location.assign("/login?next=" + encodeURIComponent(here));
  }
}

async function request(path: string, method: "GET" | "POST", body?: unknown): Promise<Response> {
  let res: Response;
  try {
    res = await fetch("/api/admin/" + path, {
      method,
      credentials: "same-origin",
      headers: {
        Accept: "application/json",
        ...(method === "POST" ? { [CSRF_HEADER]: CSRF_VALUE } : {}),
        ...(body !== undefined ? { "Content-Type": "application/json" } : {}),
      },
      body: body !== undefined ? JSON.stringify(body) : undefined,
    });
  } catch {
    throw new ApiError("Cannot reach the admin server. Check your connection.", 0);
  }
  if (res.status === 401) {
    toLogin();
    throw new ApiError("Your session has ended. Sign in again.", 401);
  }
  if (!res.ok) throw new ApiError(await errorMessage(res), res.status);
  return res;
}

export async function get<T>(path: string, schema: z.ZodType<T>): Promise<T> {
  const res = await request(path, "GET");
  const parsed = schema.safeParse(await res.json());
  if (!parsed.success) {
    const where = parsed.error.issues[0]?.path.join(".") || "response";
    throw new ApiError(`The API answered in a shape this panel does not understand (${where}). Is the backend up to date?`, 502);
  }
  return parsed.data;
}

export async function post(path: string, body?: unknown): Promise<void> {
  await request(path, "POST", body);
}

export function query(params: Record<string, string | number | undefined | null>): string {
  const q = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) if (v !== undefined && v !== null && v !== "") q.set(k, String(v));
  const s = q.toString();
  return s ? "?" + s : "";
}
