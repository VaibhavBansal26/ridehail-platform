#!/usr/bin/env node
// A load test that plays the real thing: riders go online and report their position, customers request rides, riders accept and
// drive them to completion. At the end it checks the books still balance. No dependencies (Node 22+).
//
//   node scripts/loadtest.mjs --base http://localhost:8080 --riders 40 --customers 20 --seconds 60 --admin-password dev-admin-password
//
// It needs a server where a client may save a test card (the simulated payment gateway: `docker compose up`, not the prod profile),
// because a real provider refuses that shortcut. Against a prod-profile server it still measures sign-up, position updates and
// quotes, and says that booking was skipped.
//
// What the numbers mean: they are for the machine and network you run it on. A laptop running the whole stack in Docker says
// little about production capacity; run it against a staging stack sized like production, from a separate machine.
import { randomUUID } from "node:crypto";

const arg = (name, fallback) => {
  const i = process.argv.indexOf(`--${name}`);
  return i > 0 ? process.argv[i + 1] : fallback;
};
const BASE = arg("base", "http://localhost:8080").replace(/\/$/, "");
const RIDERS = Number(arg("riders", 40));
const CUSTOMERS = Number(arg("customers", 20));
const SECONDS = Number(arg("seconds", 60));
const ADMIN_PASSWORD = arg("admin-password", "dev-admin-password");
const LAT = 12.9716, LON = 77.5946;

const stats = new Map(); // endpoint -> { ms: number[], codes: Map }
const events = { tripsRequested: 0, tripsCompleted: 0, acceptMs: [], noRiders: 0, bookingSkipped: false };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const jitter = (n) => (Math.random() - 0.5) * n;

async function call(name, method, path, token, body) {
  const t0 = performance.now();
  let code = 0, json;
  try {
    const res = await fetch(BASE + path, {
      method,
      headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}), ...(body ? { "Content-Type": "application/json" } : {}) },
      body: body ? JSON.stringify(body) : undefined,
    });
    code = res.status;
    const text = await res.text();
    try { json = text ? JSON.parse(text) : undefined; } catch { json = undefined; }
  } catch { code = 0; }
  const s = stats.get(name) ?? { ms: [], codes: new Map() };
  s.ms.push(performance.now() - t0);
  s.codes.set(code, (s.codes.get(code) ?? 0) + 1);
  stats.set(name, s);
  return { code, json };
}

async function register(kind, i) {
  const phone = `+1${Date.now() % 1e7}${i}${Math.floor(Math.random() * 1e4)}`;
  const body = { name: `Load ${kind} ${i}`, phone, password: "password123", ...(kind === "rider" ? { vehicleType: "SEDAN" } : {}) };
  const r = await call(`register ${kind}`, "POST", `/api/auth/register/${kind}`, null, body);
  if (r.code !== 201) throw new Error(`could not register a ${kind}: HTTP ${r.code}`);
  return r.json.accessToken;
}

let stop = false;

async function riderLoop(i) {
  const token = await register("rider", i);
  let lat = LAT + jitter(0.02), lon = LON + jitter(0.02);
  await call("rider online", "POST", "/api/riders/me/online", token, { lat, lon });
  let lastLocation = 0;
  while (!stop) {
    if (Date.now() - lastLocation > 4000) {
      lat += jitter(0.0004); lon += jitter(0.0004);
      await call("rider location", "PUT", "/api/riders/me/location", token, { lat, lon });
      lastLocation = Date.now();
    }
    const offers = await call("rider offers", "GET", "/api/riders/me/offers", token);
    const offer = offers.json?.[0];
    if (offer) {
      const accepted = await call("offer accept", "POST", `/api/riders/me/offers/${offer.offerId}/accept`, token);
      if (accepted.code === 204) {
        const trip = offer.tripId;
        await sleep(300);
        await call("trip arrive", "POST", `/api/trips/${trip}/arrive`, token);
        await sleep(300);
        await call("trip start", "POST", `/api/trips/${trip}/start`, token);
        await sleep(500);
        const done = await call("trip complete", "POST", `/api/trips/${trip}/complete`, token);
        if (done.code === 200) events.tripsCompleted++;
      }
    }
    await sleep(1000 + Math.random() * 500);
  }
}

async function customerLoop(i) {
  const token = await register("customer", i);
  const card = await call("save test card", "PUT", "/api/customers/me/payment-method", token, { token: "tok_ok" });
  const canBook = card.code === 204;
  if (!canBook) events.bookingSkipped = true;
  while (!stop) {
    const q = await call("fare quote", "POST", "/api/fares/quote", token, {
      vehicleType: "SEDAN", pickupLat: LAT + jitter(0.02), pickupLon: LON + jitter(0.02), dropLat: LAT + 0.03, dropLon: LON + 0.03,
    });
    if (canBook && q.code >= 200 && q.code < 300) {
      const t0 = performance.now();
      const trip = await call("book trip", "POST", "/api/trips", token, { quoteId: q.json.quoteId });
      if (trip.code === 201) {
        events.tripsRequested++;
        // wait for a rider to take it (or for the request to fail)
        for (let waited = 0; waited < 20_000 && !stop; waited += 1000) {
          await sleep(1000);
          const t = await call("trip poll", "GET", `/api/trips/${trip.json.id}`, token);
          if (t.json?.status === "NO_RIDERS_AVAILABLE") { events.noRiders++; break; }
          if (t.json && t.json.status !== "REQUESTED") { events.acceptMs.push(performance.now() - t0); break; }
        }
        // let it finish, then the customer is free to ride again
        for (let w = 0; w < 30_000 && !stop; w += 1000) {
          const a = await call("active trip", "GET", "/api/trips/active", token);
          if (a.code === 204) break;
          await sleep(1000);
        }
      }
    }
    await sleep(2000 + Math.random() * 2000);
  }
}

const pct = (arr, p) => (arr.length ? [...arr].sort((a, b) => a - b)[Math.min(arr.length - 1, Math.floor((p / 100) * arr.length))] : 0);
const fmt = (n) => n.toFixed(0).padStart(6);

async function main() {
  console.log(`load test: ${RIDERS} riders, ${CUSTOMERS} customers, ${SECONDS}s against ${BASE}`);
  const loops = [];
  const guard = (p) => p.catch((e) => { console.error("worker failed:", e.message); });
  for (let i = 0; i < RIDERS; i++) { loops.push(guard(riderLoop(i))); await sleep(40); }
  for (let i = 0; i < CUSTOMERS; i++) { loops.push(guard(customerLoop(i))); await sleep(40); }
  const started = Date.now();
  await sleep(SECONDS * 1000);
  stop = true;
  await Promise.race([Promise.all(loops), sleep(45_000)]);
  const elapsed = (Date.now() - started) / 1000;

  console.log(`\n${"endpoint".padEnd(18)} ${"count".padStart(6)} ${"p50".padStart(6)} ${"p95".padStart(6)} ${"p99".padStart(6)} ${"max".padStart(6)}  status codes`);
  let total = 0, bad = 0;
  for (const [name, s] of [...stats].sort()) {
    total += s.ms.length;
    const codes = [...s.codes].map(([c, n]) => `${c}×${n}`).join(" ");
    for (const [c, n] of s.codes) if (c === 0 || c >= 500) bad += n;
    console.log(`${name.padEnd(18)} ${String(s.ms.length).padStart(6)} ${fmt(pct(s.ms, 50))} ${fmt(pct(s.ms, 95))} ${fmt(pct(s.ms, 99))} ${fmt(Math.max(...s.ms))}  ${codes}`);
  }
  console.log(`\n${total} requests in ${elapsed.toFixed(0)}s (${(total / elapsed).toFixed(1)}/s); ${bad} failed (network errors or 5xx)`);
  if (events.bookingSkipped) console.log("booking skipped: this server refuses client-supplied test cards (a real payment provider)");
  else {
    console.log(`trips: ${events.tripsRequested} requested, ${events.tripsCompleted} completed, ${events.noRiders} found no rider`);
    console.log(`time from request to a rider accepting: p50 ${fmt(pct(events.acceptMs, 50))} ms, p95 ${fmt(pct(events.acceptMs, 95))} ms (${events.acceptMs.length} trips)`);
  }

  // the books must still balance after all that
  const admin = await call("admin login", "POST", "/api/auth/login", null, { phone: "admin", password: ADMIN_PASSWORD });
  let ok = bad === 0;
  if (admin.code === 200) {
    await sleep(3000); // let the last payments settle
    const tb = await call("trial balance", "GET", "/api/admin/ledger/trial-balance", admin.json.accessToken);
    console.log(`ledger balanced: ${tb.json?.balanced}`);
    ok = ok && tb.json?.balanced === true;
  } else console.log("could not check the ledger (admin login failed): pass --admin-password");
  console.log(ok ? "\nPASS" : "\nFAIL");
  process.exit(ok ? 0 : 1);
}

main().catch((e) => { console.error(e); process.exit(2); });
