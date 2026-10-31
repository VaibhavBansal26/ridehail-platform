import { NextRequest } from "next/server";
import { clearSession, json, rejectCrossSite } from "@/lib/bff";

export async function POST(req: NextRequest) {
  const refused = rejectCrossSite(req);
  if (refused) return refused;
  const res = json({ ok: true });
  clearSession(res);
  return res;
}
