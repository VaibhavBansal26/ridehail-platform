import { NextRequest } from "next/server";
import { backendRegister, json, rejectCrossSite, setSession } from "@/lib/bff";

const VEHICLES = ["BIKE", "AUTO", "SEDAN", "SUV"];

export async function POST(req: NextRequest) {
  const refused = rejectCrossSite(req);
  if (refused) return refused;

  let name = "", phone = "", password = "", vehicleType = "";
  try {
    const body = (await req.json()) as Record<string, unknown>;
    name = typeof body.name === "string" ? body.name.trim() : "";
    phone = typeof body.phone === "string" ? body.phone.trim() : "";
    password = typeof body.password === "string" ? body.password : "";
    vehicleType = typeof body.vehicleType === "string" ? body.vehicleType : "";
  } catch {
    return json({ detail: "Invalid request" }, 400);
  }
  if (!name || !phone) return json({ detail: "Enter your name and phone number" }, 400);
  if (password.length < 8 || password.length > 72) return json({ detail: "Choose a password of 8 to 72 characters" }, 400);
  if (!VEHICLES.includes(vehicleType)) return json({ detail: "Choose your vehicle type" }, 400);

  let result;
  try {
    result = await backendRegister(name, phone, password, vehicleType);
  } catch {
    return json({ detail: "The API is not reachable" }, 502);
  }
  if (result.status === 409) return json({ detail: "That phone number is already registered. Sign in instead." }, 409);
  if (result.status === 429) return json({ detail: "Too many attempts. Try again shortly." }, 429);
  if (!result.tokens) return json({ detail: "Could not create the account. Check your details." }, 400);

  const res = json({ ok: true }, 201);
  setSession(res, result.tokens);
  return res;
}
