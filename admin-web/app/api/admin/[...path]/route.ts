import { NextRequest } from "next/server";
import { backendPath, forward, json, rejectCrossSite } from "@/lib/bff";

/** The browser's only way to reach the admin API: same origin, cookie in, bearer token out. */
async function handle(req: NextRequest, ctx: { params: Promise<{ path: string[] }> }) {
  const refused = rejectCrossSite(req);
  if (refused) return refused;
  const path = backendPath((await ctx.params).path);
  if (!path) return json({ detail: "Not found" }, 404);
  return forward(req, path);
}

export { handle as GET, handle as POST };
