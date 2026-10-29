import { NextRequest } from "next/server";
import { backendLogin, json, rejectCrossSite, setSession } from "@/lib/bff";

export async function POST(req: NextRequest) {
  const refused = rejectCrossSite(req);
  if (refused) return refused;

  let phone = "", password = "";
  try {
    const body = (await req.json()) as { phone?: unknown; password?: unknown };
    phone = typeof body.phone === "string" ? body.phone.trim() : "";
    password = typeof body.password === "string" ? body.password : "";
  } catch {
    return json({ detail: "Invalid request" }, 400);
  }
  if (!phone || !password) return json({ detail: "Enter your phone and password" }, 400);

  let result;
  try {
    result = await backendLogin(phone, password);
  } catch {
    return json({ detail: "The API is not reachable" }, 502);
  }
  if (result.status === 429) {
    const wait = result.retryAfter ?? 60;
    const res = json({ detail: `Too many sign-in attempts. Try again in ${wait} second${wait === 1 ? "" : "s"}.`, retryAfter: wait }, 429);
    res.headers.set("Retry-After", String(wait));
    return res;
  }
  // wrong password and "not an admin" look the same from outside: no hint about which accounts exist
  if (!result.tokens || result.role !== "ADMIN") return json({ detail: "Wrong phone or password" }, 401);

  const res = json({ ok: true });
  setSession(res, result.tokens);
  return res;
}
