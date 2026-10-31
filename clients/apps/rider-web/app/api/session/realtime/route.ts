import { NextRequest } from "next/server";
import { json, realtimeToken, rejectCrossSite } from "@/lib/bff";

/** A short-lived bearer token and the WebSocket address, for the browser's realtime connection. */
export async function GET(req: NextRequest) {
  const refused = rejectCrossSite(req);
  if (refused) return refused;
  try {
    return await realtimeToken(req);
  } catch {
    return json({ detail: "The API is not reachable" }, 502);
  }
}
