// Records the demo video's clips: a customer and a driver take a ride (side by side, with maps and the car moving live), three
// requests arrive at once, then the admin panel is toured. Each scene waits for its narration line, so picture and voice stay together.
//
// Needs the local stack running with demo data and simulated payments (see scripts/demo/README.md), Playwright with Chromium, and
// ffmpeg. Everything shown is the real UI talking to the real API; nothing is mocked.
//
//   NARRATION_TIMING=<folder from narrate.cjs>/timing.json NODE_PATH=$HOME/node_modules node scripts/demo/record-demo.cjs <output-folder>
//
// It writes clips and a timeline per scene (when each narration line starts) to <output-folder>; assemble.cjs joins them.
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');

const OUT = path.resolve(process.argv[2] || 'demo-out');
const CUSTOMER = process.env.CUSTOMER_URL || 'http://localhost:3002';
const RIDER = process.env.RIDER_URL || 'http://localhost:3001';
const ADMIN = process.env.ADMIN_URL || 'http://localhost:3000';
const API = process.env.API_URL || 'http://localhost:8080';
const ADMIN_PASSWORD = process.env.ADMIN_PASSWORD || 'dev-admin-password';
const PASSWORD = 'demo-password-1';
const DESTINATION = process.env.DESTINATION || 'Lalbagh'; // searched for by name in the customer app

const { lines } = require('./narration.json');
const LINE = Object.fromEntries(lines.map((l) => [l.id, l]));
const TIMING = process.env.NARRATION_TIMING ? JSON.parse(fs.readFileSync(process.env.NARRATION_TIMING, 'utf8')) : {};
/** How long a narration line takes to say, plus a breath. Measured when there is audio, estimated from the word count when there is not. */
const slotMs = (id) => Math.round(((TIMING[id] ?? LINE[id].text.split(/\s+/).length / 2.4) + 0.7) * 1000);

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const suffix = Number(String(Date.now()).slice(-8));

// ---- Bengaluru, around MG Road
const PICKUP = { lat: 12.9716, lon: 77.5946 };
const START = { lat: 12.969, lon: 77.5918 };           // where the driver waits: about 400 m from the pickup
const MG_ROAD = { lat: 12.9756, lon: 77.6068 };        // the nearest drop-off in the list
const lerp = (a, b, t) => ({ lat: a.lat + (b.lat - a.lat) * t, lon: a.lon + (b.lon - a.lon) * t });
const geo = (p) => ({ latitude: p.lat, longitude: p.lon });
const leg = (a, b, steps) => Array.from({ length: steps }, (_, i) => lerp(a, b, (i + 1) / steps));
/** `n` points spaced evenly along a road (a list of {lat, lon}), the last being its end: where a car would be at each step. */
function along(road, n) {
  const d = (p, q) => Math.hypot(p.lat - q.lat, (p.lon - q.lon) * Math.cos((p.lat * Math.PI) / 180));
  const cumulative = [0];
  for (let i = 1; i < road.length; i++) cumulative.push(cumulative[i - 1] + d(road[i - 1], road[i]));
  const total = cumulative[cumulative.length - 1];
  return Array.from({ length: n }, (_, k) => {
    const target = (total * (k + 1)) / n;
    let j = 1;
    while (j < cumulative.length - 1 && cumulative[j] < target) j++;
    const span = cumulative[j] - cumulative[j - 1] || 1;
    return lerp(road[j - 1], road[j], Math.min(1, Math.max(0, (target - cumulative[j - 1]) / span)));
  });
}

// ---------------------------------------------------------------------------------------------------------------- overlays
// Drawn inside the page so they are part of the recording (contexts use bypassCSP: the apps' policy forbids inline styles).
const overlay = () => {
  if (window.__demo) return;
  window.__demo = true;
  // an init script runs before the page has a root element, so nothing is added to the DOM until it is first needed
  const ensureStyle = () => {
    if (document.getElementById('demo-style') || !document.documentElement) return;
    const css = document.createElement('style');
    css.id = 'demo-style';
    css.textContent = `
      #demo-caption{position:fixed;left:50%;transform:translateX(-50%);bottom:76px;max-width:90%;z-index:2147483647;
        background:rgba(15,23,42,.94);color:#fff;font:600 16px/1.35 system-ui,sans-serif;padding:10px 16px;border-radius:12px;
        text-align:center;box-shadow:0 6px 24px rgba(0,0,0,.28);transition:opacity .25s;pointer-events:none}
      #demo-tag{position:fixed;top:10px;right:10px;z-index:2147483647;background:#2563eb;color:#fff;font:700 13px system-ui,sans-serif;
        padding:5px 11px;border-radius:999px;letter-spacing:.04em;pointer-events:none}
      .demo-ripple{position:fixed;width:34px;height:34px;margin:-17px 0 0 -17px;border-radius:50%;background:rgba(37,99,235,.35);
        border:2px solid #2563eb;z-index:2147483647;pointer-events:none;animation:demo-r .7s ease-out forwards}
      @keyframes demo-r{from{transform:scale(.4);opacity:1}to{transform:scale(1.6);opacity:0}}`;
    document.documentElement.appendChild(css);
  };
  const el = (id) => {
    ensureStyle();
    let e = document.getElementById(id);
    if (!e && document.documentElement) { e = document.createElement('div'); e.id = id; document.documentElement.appendChild(e); }
    return e;
  };
  document.addEventListener('mousedown', (e) => {
    ensureStyle();
    const d = document.createElement('div');
    d.className = 'demo-ripple'; d.style.left = e.clientX + 'px'; d.style.top = e.clientY + 'px';
    document.documentElement.appendChild(d); setTimeout(() => d.remove(), 800);
  }, true);
  window.__say = (text) => { const e = el('demo-caption'); if (!e) return; e.style.opacity = text ? '1' : '0'; if (text) e.textContent = text; };
  window.__tag = (text) => { const e = el('demo-tag'); if (e) e.textContent = text; };
};

async function prepare(page, tag) {
  await page.addInitScript(overlay);
  await page.addInitScript((t) => { window.addEventListener('DOMContentLoaded', () => { if (window.__tag && t) window.__tag(t); }); }, tag);
}
const say = (page, text) => page.evaluate((t) => { window.__say && window.__say(t); }, text).catch(() => {});
async function typeInto(locator, text) { await locator.click(); await locator.pressSequentially(text, { delay: 35 }); }

// ---------------------------------------------------------------------------------------------------------------- the API
// Used for the people who are not on screen (the other customers and drivers in the busy scene) and for setup. Same API the apps use.
async function call(method, route, token, body) {
  const res = await fetch(API + route, { method, headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: 'Bearer ' + token } : {}) }, body: body ? JSON.stringify(body) : undefined });
  const text = await res.text();
  if (!res.ok) throw new Error(`${method} ${route} -> ${res.status} ${text.slice(0, 160)}`);
  return text ? JSON.parse(text) : null;
}
const person = (kind, name, phone, extra = {}) => call('POST', `/api/auth/register/${kind}`, null, { name, phone, password: PASSWORD, ...extra });

/** Signs in off camera and returns the browser's session, so a scene can begin already signed in. */
async function signedIn(browser, base, userLabel, user, password) {
  const ctx = await browser.newContext({ viewport: { width: 640, height: 720 }, bypassCSP: true });
  const p = await ctx.newPage();
  await p.goto(base + '/login');
  await p.getByRole('textbox', { name: userLabel }).fill(user);
  await p.getByRole('textbox', { name: 'Password' }).fill(password);
  await p.getByRole('button', { name: 'Sign in' }).click();
  await p.waitForURL((u) => !u.pathname.startsWith('/login'), { timeout: 40000 });
  const state = await ctx.storageState();
  await ctx.close();
  return state;
}

// ---------------------------------------------------------------------------------------------------------------- timelines
class Scene {
  constructor(name) { this.name = name; this.t0 = Date.now(); this.lines = []; }
  /** Starts a narration line now and returns once it has been said (and `work`, if any, has finished). */
  async beat(id, work) {
    this.lines.push({ id, t: Number(((Date.now() - this.t0) / 1000).toFixed(2)) });
    await Promise.all([sleep(slotMs(id)), work ? work() : null]);
  }
  save() { fs.writeFileSync(path.join(OUT, this.name + '.timeline.json'), JSON.stringify(this.lines, null, 2)); }
}

// ---------------------------------------------------------------------------------------------------------------- scenes
async function card(browser, name, id, title, sub) {
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 720 }, bypassCSP: true, recordVideo: { dir: OUT, size: { width: 1280, height: 720 } } });
  const page = await ctx.newPage();
  const scene = new Scene(name);
  await page.setContent(`<body style="margin:0;height:100vh;display:grid;place-items:center;background:linear-gradient(135deg,#0f172a,#1e3a8a);
    color:#fff;font-family:system-ui,sans-serif;text-align:center"><div><div style="font-size:64px;font-weight:800;letter-spacing:-.02em">${title}</div>
    <div style="margin-top:18px;font-size:26px;opacity:.85;max-width:900px">${sub}</div></div></body>`);
  await sleep(400);
  await scene.beat(id);
  await ctx.close();
  fs.renameSync(await page.video().path(), path.join(OUT, name + '.webm'));
  scene.save();
}

/** A customer and a driver, side by side: sign-up, request, the car approaching and driving on a live map, payment, earnings. */
async function rideScene(browser, people) {
  const paneOptions = (geolocation) => ({ viewport: { width: 640, height: 720 }, bypassCSP: true, geolocation: geo(geolocation), permissions: ['geolocation'], recordVideo: { dir: OUT, size: { width: 640, height: 720 } } });
  const cctx = await browser.newContext(paneOptions(PICKUP)), rctx = await browser.newContext(paneOptions(START));
  const scene = new Scene('ride');
  const c = await cctx.newPage(), r = await rctx.newPage();
  await Promise.all([prepare(c, 'CUSTOMER'), prepare(r, 'DRIVER')]);

  await scene.beat('signup', () => Promise.all([
    (async () => {
      await c.goto(CUSTOMER + '/login');
      await say(c, 'A customer creates an account');
      await c.getByRole('tab', { name: 'Create account' }).click();
      await typeInto(c.getByRole('textbox', { name: 'Your name' }), people.customer.name);
      await typeInto(c.getByRole('textbox', { name: 'Phone number' }), people.customer.phone);
      await typeInto(c.getByRole('textbox', { name: /Password/ }), PASSWORD);
      await c.getByRole('button', { name: 'Create account' }).click();
    })(),
    (async () => {
      await r.goto(RIDER + '/login');
      await say(r, 'A driver signs up in the rider app');
      await r.getByRole('tab', { name: 'Create account' }).click();
      await typeInto(r.getByRole('textbox', { name: 'Your name' }), people.rider.name);
      await typeInto(r.getByRole('textbox', { name: 'Phone number' }), people.rider.phone);
      await typeInto(r.getByRole('textbox', { name: /Password/ }), PASSWORD);
      await r.getByRole('button', { name: 'Create account' }).click();
    })(),
  ]));

  await scene.beat('online', () => Promise.all([
    (async () => {
      await r.getByRole('button', { name: 'Go online' }).click({ timeout: 30000 });
      await say(r, 'The driver goes online and shares their position');
    })(),
    (async () => {
      await c.getByRole('link', { name: 'Account' }).click({ timeout: 30000 });
      await say(c, 'A card is added (a development test card)');
      await c.getByRole('button', { name: /test card/ }).click();
      await sleep(1500);
      await say(c, 'Card on file: charged only when the trip ends');
    })(),
  ]));

  await c.getByRole('link', { name: 'Ride' }).click();
  await c.getByRole('button', { name: 'Use my location' }).click({ timeout: 20000 });
  await scene.beat('fare', async () => {
    await say(c, 'Search for a place: the map shows the road to it');
    await typeInto(c.getByRole('searchbox'), DESTINATION);
    await c.getByRole('button', { name: 'Search' }).click();
    await c.getByRole('list', { name: 'Search results' }).getByRole('button').first().click({ timeout: 30000 });
    await sleep(2500); // the road arrives from the routing service
    await say(c, 'Fare quoted upfront, locked when you book');
    await c.getByRole('button', { name: 'See fare' }).click();
    await c.getByRole('button', { name: 'Request ride' }).waitFor({ timeout: 30000 });
  });
  // the road the car will drive: the same one the map draws (the first search result is the destination the customer chose)
  const customerToken = (await call('POST', '/api/auth/login', null, { phone: people.customer.phone, password: PASSWORD })).accessToken;
  const destination = (await call('GET', '/api/places/search?q=' + encodeURIComponent(DESTINATION), customerToken))[0];
  const roadBetween = async (from, to, steps) => {
    try {
      const r = await call('GET', `/api/routes?fromLat=${from.lat}&fromLon=${from.lon}&toLat=${to.lat}&toLon=${to.lon}`, customerToken);
      return along(r.points.map(([lat, lon]) => ({ lat, lon })), steps);
    } catch {
      return leg(from, to, steps); // no routing service: a straight line
    }
  };
  // the demo city has trips of its own: remember this fare so the driver takes THIS customer's trip
  const fare = ((await c.locator('main').innerText()).split('Your fare')[1] || '').match(/\$\d+\.\d{2}/)[0];

  await scene.beat('offer', async () => {
    await c.getByRole('button', { name: 'Request ride' }).click();
    await say(c, 'Finding the nearest driver…');
    for (const deadline = Date.now() + 120000; ; await sleep(400)) {
      if (Date.now() > deadline) throw new Error('no offer for ' + fare);
      if (!(await r.getByRole('button', { name: /Accept/ }).count())) continue;
      if (!(await r.locator('main').innerText()).includes(fare)) {
        await r.getByRole('button', { name: 'Decline' }).click().catch(() => {}); // someone else's trip
        continue;
      }
      await say(r, 'The offer arrives in real time, with the route');
      await sleep(2500);
      await r.getByRole('button', { name: /Accept/ }).first().click().catch(() => {});
      // the offer can run out while it is being looked at: only go on once the trip really is the driver's, otherwise look again
      if (await r.getByRole('heading', { name: 'Head to the pickup' }).waitFor({ timeout: 8000 }).then(() => true, () => false)) break;
    }
  });

  // the driver's phone moves: the app reports the position every few seconds and the customer's car glides along
  const drive = async (steps) => { for (const p of steps) { await rctx.setGeolocation({ latitude: p.lat, longitude: p.lon }); await sleep(4000); } };
  await scene.beat('approach', async () => {
    await say(c, 'Live: the car is on its way to you');
    await say(r, 'Head to the pickup');
    await drive(await roadBetween(START, PICKUP, 5));
  });

  await scene.beat('drive', async () => {
    await r.getByRole('button', { name: "I've arrived" }).click();
    await say(c, 'Your driver has arrived');
    await sleep(1800);
    await r.getByRole('button', { name: 'Start trip' }).click();
    await say(c, 'On your way');
    await say(r, 'Drive to the drop-off');
    await drive(await roadBetween(PICKUP, destination, 8));
    await r.getByRole('button', { name: 'Complete trip' }).click();
  });

  await scene.beat('paid', async () => {
    await say(c, 'Trip complete: the card is charged automatically');
    await sleep(2200);
    await r.getByRole('link', { name: 'Earnings' }).click();
    await say(r, 'The driver’s share is credited; the platform takes its commission');
  });

  const [cv, rv] = [c.video(), r.video()];
  await Promise.all([cctx.close(), rctx.close()]);
  fs.renameSync(await cv.path(), path.join(OUT, 'ride-customer.webm'));
  fs.renameSync(await rv.path(), path.join(OUT, 'ride-rider.webm'));
  scene.save();
}

/** Three customers ask for a ride at the same instant: the nearest driver sees all three at once, the others go to drivers nearby. */
async function rushScene(browser, people) {
  // the people who are not on screen, set up before the cameras roll
  const near = (dLat, dLon) => ({ lat: MG_ROAD.lat + dLat, lon: MG_ROAD.lon + dLon });
  const drops = { INDIRANAGAR: { lat: 12.9784, lon: 77.6408 }, KORAMANGALA: { lat: 12.9352, lon: 77.6245 }, JAYANAGAR: { lat: 12.925, lon: 77.5938 } };
  const customers = [];
  for (const [i, [name, place]] of [['Ravi', 'INDIRANAGAR'], ['Meera', 'KORAMANGALA'], ['Sam', 'JAYANAGAR']].entries()) {
    const reg = await person('customer', name, '+91' + (suffix + 10 + i));
    await call('PUT', '/api/customers/me/payment-method', reg.accessToken, { token: 'tok_demo' });
    customers.push({ ...reg, name, pickup: near(0.0004 * i, 0.0005 * (i - 1)), drop: drops[place] });
  }
  const helpers = [];
  for (const [i, [name, at]] of [['Dev', near(0.0036, 0)], ['Isha', near(0, 0.0038)]].entries()) {
    const reg = await person('rider', name, '+91' + (suffix + 20 + i), { vehicleType: 'SEDAN' });
    await call('POST', '/api/riders/me/online', reg.accessToken, { lat: at.lat, lon: at.lon });
    helpers.push({ ...reg, name, at });
  }

  const [riderSession, adminSession] = await Promise.all([
    signedIn(browser, RIDER, 'Phone number', people.rider.phone, PASSWORD),
    signedIn(browser, ADMIN, 'Phone or username', 'admin', ADMIN_PASSWORD),
  ]);
  const pane = { viewport: { width: 640, height: 720 }, bypassCSP: true, recordVideo: { dir: OUT, size: { width: 640, height: 720 } } };
  const rctx = await browser.newContext({ ...pane, geolocation: geo(MG_ROAD), permissions: ['geolocation'], storageState: riderSession });
  const actx = await browser.newContext({ ...pane, storageState: adminSession });
  const scene = new Scene('rush');
  const [r, a] = await Promise.all([rctx.newPage(), actx.newPage()]);
  await Promise.all([prepare(r, 'DRIVER'), prepare(a, 'OPERATIONS')]);
  await Promise.all([r.goto(RIDER + '/'), a.goto(ADMIN + '/map', { waitUntil: 'domcontentloaded' })]);
  // the server sets a driver who has stopped reporting offline, which is what happens between scenes when one is re-recorded on its own
  const goOnline = r.getByRole('button', { name: 'Go online' });
  if (await goOnline.waitFor({ timeout: 6000 }).then(() => true, () => false)) await goOnline.click();
  await r.getByRole('button', { name: 'Go offline' }).waitFor({ timeout: 30000 });
  await sleep(1200);

  await scene.beat('rush', async () => {
    await say(r, 'Waiting for requests…');
    await say(a, 'Live operations map');
    await sleep(2500);
    // all three at the same instant
    await Promise.all(customers.map(async (cu) => {
      const q = await call('POST', '/api/fares/quote', cu.accessToken, { vehicleType: 'SEDAN', pickupLat: cu.pickup.lat, pickupLon: cu.pickup.lon, dropLat: cu.drop.lat, dropLon: cu.drop.lon });
      return call('POST', '/api/trips', cu.accessToken, { quoteId: q.quoteId });
    }));
    await say(r, 'Three requests at once');
    await say(a, 'Three new requests on the map');
  });

  await scene.beat('stack', async () => {
    await r.getByRole('button', { name: /Accept/ }).nth(2).waitFor({ timeout: 40000 }).catch(() => {});
    await sleep(2500);
    // the driver sends two of the three on to drivers nearby, then takes the first
    await say(a, 'Two go on to drivers nearby');
    const declines = r.getByRole('button', { name: 'Decline' });
    for (let i = 0; i < 2 && (await declines.count()) > 1; i++) { await sleep(1500); await declines.nth(1).click().catch(() => {}); }
    await sleep(1500);
    await r.getByRole('button', { name: /Accept/ }).first().click();
    await say(r, 'The driver takes the first');
    // Dev and Isha answer their offers
    await Promise.all(helpers.map(async (h) => {
      for (const deadline = Date.now() + 40000; Date.now() < deadline; await sleep(700)) {
        const offers = await call('GET', '/api/riders/me/offers', h.accessToken);
        if (offers.length) { await call('POST', `/api/riders/me/offers/${offers[0].offerId}/accept`, h.accessToken); return; }
      }
    }));
  });

  await scene.beat('ops', async () => {
    await say(a, 'Every request and every driver, live');
    // they all set off at once
    await Promise.all(helpers.map(async (h, i) => {
      for (const p of leg(h.at, customers[i + 1].pickup, 3)) { await call('PUT', '/api/riders/me/location', h.accessToken, { lat: p.lat, lon: p.lon }); await sleep(2500); }
    }));
  });

  const [rv, av] = [r.video(), a.video()];
  await Promise.all([rctx.close(), actx.close()]);
  fs.renameSync(await rv.path(), path.join(OUT, 'rush-rider.webm'));
  fs.renameSync(await av.path(), path.join(OUT, 'rush-admin.webm'));
  scene.save();
}

async function adminScene(browser) {
  const session = await signedIn(browser, ADMIN, 'Phone or username', 'admin', ADMIN_PASSWORD);
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 720 }, bypassCSP: true, storageState: session, recordVideo: { dir: OUT, size: { width: 1280, height: 720 } } });
  const p = await ctx.newPage();
  const scene = new Scene('admin');
  await prepare(p, 'ADMIN PANEL');

  const visit = async (route, caption, ms) => {
    await p.goto(ADMIN + route, { waitUntil: 'domcontentloaded' });
    await p.waitForFunction(() => !/Loading/i.test(document.body.innerText), null, { timeout: 20000 }).catch(() => {});
    await sleep(700);
    await say(p, caption);
    await sleep(ms);
  };
  await scene.beat('admin', async () => {
    await visit('/', 'What needs attention, right now', 3200);
    await visit('/payments', 'Every payment: charge, commission and the driver’s share', 3200);
    await visit('/ledger', 'A double-entry ledger that must balance', 3200);
  });
  await scene.beat('disputes', async () => {
    await visit('/disputes', 'Chargebacks freeze the driver’s share until decided', 2800);
    const answer = p.getByRole('link', { name: /Answer|Evidence/ }).first();
    if (await answer.count()) {
      await answer.click();
      await sleep(1500);
      await say(p, 'Evidence is compiled from the trip and submitted to Stripe');
      await sleep(2200);
    }
    await visit('/audit', 'Every admin action is recorded', 2400);
  });

  const v = p.video();
  await ctx.close();
  fs.renameSync(await v.path(), path.join(OUT, 'admin.webm'));
  scene.save();
}

(async () => {
  const only = (process.env.SCENES || '').split(',').filter(Boolean); // e.g. SCENES=admin: re-record one scene, keep the other clips
  const run = (name) => only.length === 0 || only.includes(name);
  if (only.length === 0) fs.rmSync(OUT, { recursive: true, force: true });
  fs.mkdirSync(OUT, { recursive: true });
  const peopleFile = path.join(OUT, 'people.json');
  const people = only.length && fs.existsSync(peopleFile) ? JSON.parse(fs.readFileSync(peopleFile, 'utf8')) : {
    customer: { name: 'Priya Sharma', phone: '+91' + suffix },
    rider: { name: 'Karan Mehta', phone: '+91' + (suffix + 1) },
  };
  fs.writeFileSync(peopleFile, JSON.stringify(people));
  const browser = await chromium.launch();
  if (run('title')) await card(browser, '0-title', 'intro', 'Ridehail', 'A ride-sharing platform: customers, drivers and operations');
  if (run('ride')) { await rideScene(browser, people); console.log('ride scene done'); }
  if (run('rush')) { await rushScene(browser, people); console.log('busy scene done'); }
  if (run('admin')) await adminScene(browser);
  if (run('end')) await card(browser, '9-end', 'outro', 'Built and tested end to end', 'Spring Boot API · Next.js and Expo apps · Stripe payments and payouts · 387 backend tests');
  await browser.close();
  console.log(fs.readdirSync(OUT).join('\n'));
})().catch((e) => { console.error(e); process.exit(1); });
