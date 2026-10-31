// Records the demo video: a customer and a rider take a ride (side by side), then the admin panel.
//
// Needs the local stack running with demo data and simulated payments (see scripts/demo/README.md), Playwright with Chromium,
// and ffmpeg. Everything it shows is the real UI talking to the real API; nothing is mocked.
//
//   NODE_PATH=$HOME/node_modules node scripts/demo/record-demo.cjs <output-folder>
//
// It writes clips to <output-folder>; scripts/demo/make-video.sh joins them into the final mp4 and a short gif.
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');

const OUT = path.resolve(process.argv[2] || 'demo-out');
const CUSTOMER = process.env.CUSTOMER_URL || 'http://localhost:3002';
const RIDER = process.env.RIDER_URL || 'http://localhost:3001';
const ADMIN = process.env.ADMIN_URL || 'http://localhost:3000';
const ADMIN_PASSWORD = process.env.ADMIN_PASSWORD || 'dev-admin-password';
const PASSWORD = 'demo-password-1';
const here = { latitude: 12.9716, longitude: 77.5946 }; // MG Road, Bengaluru: the demo city's centre

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const suffix = String(Date.now()).slice(-8);

// ---------------------------------------------------------------------------------------------------------------- overlays
// Drawn inside the page so they are part of the recording (contexts use bypassCSP: the apps' policy forbids inline styles): a caption, a label for the pane, and a ripple where a click lands.
const overlay = () => {
  if (window.__demo) return;
  window.__demo = true;
  // an init script runs before the page has a root element, so nothing is added to the DOM until it is first needed
  const ensureStyle = () => {
    if (document.getElementById('demo-style') || !document.documentElement) return;
    const css = document.createElement('style');
    css.id = 'demo-style';
    css.textContent = `
      #demo-caption{position:fixed;left:50%;transform:translateX(-50%);bottom:84px;max-width:88%;z-index:2147483647;
        background:rgba(15,23,42,.94);color:#fff;font:600 17px/1.35 system-ui,sans-serif;padding:11px 18px;border-radius:12px;
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
async function say(page, text, ms = 1800) {
  await page.evaluate((t) => { window.__say && window.__say(t); }, text).catch(() => {});
  await sleep(ms);
}
async function typeInto(locator, text) { await locator.click(); await locator.pressSequentially(text, { delay: 45 }); }

// ---------------------------------------------------------------------------------------------------------------- scenes
async function card(browser, name, title, sub, ms) {
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 720 }, bypassCSP: true, recordVideo: { dir: OUT, size: { width: 1280, height: 720 } } });
  const page = await ctx.newPage();
  await page.setContent(`<body style="margin:0;height:100vh;display:grid;place-items:center;background:linear-gradient(135deg,#0f172a,#1e3a8a);
    color:#fff;font-family:system-ui,sans-serif;text-align:center"><div><div style="font-size:64px;font-weight:800;letter-spacing:-.02em">${title}</div>
    <div style="margin-top:18px;font-size:26px;opacity:.85;max-width:900px">${sub}</div></div></body>`);
  await sleep(ms);
  await ctx.close();
  fs.renameSync(await page.video().path(), path.join(OUT, name + '.webm'));
}

async function rideScene(browser) {
  const phone = { viewport: { width: 640, height: 720 }, bypassCSP: true, geolocation: here, permissions: ['geolocation'], recordVideo: { dir: OUT, size: { width: 640, height: 720 } } };
  const cctx = await browser.newContext(phone), rctx = await browser.newContext(phone);
  const c = await cctx.newPage(), r = await rctx.newPage();
  await Promise.all([prepare(c, 'CUSTOMER'), prepare(r, 'RIDER')]);
  const started = Date.now();

  // --- sign up, side by side
  const customerSignup = async () => {
    await c.goto(CUSTOMER + '/login');
    await say(c, 'A customer creates an account', 1200);
    await c.getByRole('tab', { name: 'Create account' }).click();
    await typeInto(c.getByRole('textbox', { name: 'Your name' }), 'Priya Sharma');
    await typeInto(c.getByRole('textbox', { name: 'Phone number' }), '+91' + suffix);
    await typeInto(c.getByRole('textbox', { name: /Password/ }), PASSWORD);
    await c.getByRole('button', { name: 'Create account' }).click();
  };
  const riderSignup = async () => {
    await r.goto(RIDER + '/login');
    await say(r, 'A driver signs up in the rider app', 1200);
    await r.getByRole('tab', { name: 'Create account' }).click();
    await typeInto(r.getByRole('textbox', { name: 'Your name' }), 'Karan Mehta');
    await typeInto(r.getByRole('textbox', { name: 'Phone number' }), '+91' + (Number(suffix) + 1));
    await typeInto(r.getByRole('textbox', { name: /Password/ }), PASSWORD);
    await r.getByRole('button', { name: 'Create account' }).click();
  };
  await Promise.all([customerSignup(), riderSignup()]);

  // --- driver goes online, customer adds a card
  await Promise.all([
    (async () => {
      await r.getByRole('button', { name: 'Go online' }).click({ timeout: 30000 });
      await say(r, 'The driver goes online and shares their position', 2600);
    })(),
    (async () => {
      await c.getByRole('link', { name: 'Account' }).click({ timeout: 30000 });
      await say(c, 'Cards are added on the payment provider’s page; here, a development test card', 1800);
      await c.getByRole('button', { name: /test card/ }).click();
      await sleep(1500);
      await say(c, 'Card on file. We only charge it when the trip ends', 1800);
    })(),
  ]);

  // --- the customer asks for a ride
  await c.getByRole('link', { name: 'Ride' }).click();
  await say(c, 'Where to? Pickup uses the phone’s location', 1200);
  await c.getByRole('button', { name: 'Use my location' }).click();
  await sleep(900);
  await c.getByRole('combobox', { name: 'Drop-off' }).selectOption('Indiranagar');
  await sleep(700);
  await c.getByRole('button', { name: 'See fare' }).click();
  await c.getByRole('button', { name: 'Request ride' }).waitFor({ timeout: 30000 });
  // the demo city has trips of its own: remember this fare so the driver takes THIS customer's trip
  const fare = ((await c.locator('main').innerText()).split('Your fare')[1] || '').match(/\$\d+\.\d{2}/)[0];
  await say(c, 'An upfront fare, with demand-based pricing, locked when you book', 3000);
  await c.getByRole('button', { name: 'Request ride' }).click();
  await say(c, 'The nearest driver is offered the trip', 1200);

  // --- the driver accepts and drives
  await say(r, 'The offer arrives in real time', 800);
  for (const deadline = Date.now() + 90000; ; await sleep(400)) {
    if (Date.now() > deadline) throw new Error('no offer for ' + fare);
    if (!(await r.getByRole('button', { name: /Accept/ }).count())) continue;
    if ((await r.locator('main').innerText()).includes(fare)) { await r.getByRole('button', { name: /Accept/ }).click(); break; }
    await r.getByRole('button', { name: 'Decline' }).click().catch(() => {}); // someone else's trip
  }
  await say(r, 'Accepted. Head to the pickup', 1800);
  await say(c, 'Your driver is on the way', 1500);
  await r.getByRole('button', { name: "I've arrived" }).click();
  await say(r, 'Arrived at the pickup', 1000);
  await say(c, 'Your driver has arrived', 1800);
  await r.getByRole('button', { name: 'Start trip' }).click();
  await say(r, 'Trip started', 1000);
  await say(c, 'On your way', 2000);
  await r.getByRole('button', { name: 'Complete trip' }).click();
  await say(r, 'Trip complete', 800);
  await say(c, 'Trip complete: the card is charged automatically', 3200);

  // --- the driver's earnings
  await r.getByRole('link', { name: 'Earnings' }).click();
  await say(r, 'The driver’s share is credited: a double-entry ledger takes the commission', 3600);
  await c.getByRole('button', { name: 'Done' }).click().catch(() => {});
  await c.getByRole('link', { name: 'Support' }).click();
  await say(c, 'Customers and drivers can message support from the app', 2800);

  const [cv, rv] = [c.video(), r.video()];
  await Promise.all([cctx.close(), rctx.close()]);
  fs.renameSync(await cv.path(), path.join(OUT, 'ride-customer.webm'));
  fs.renameSync(await rv.path(), path.join(OUT, 'ride-rider.webm'));
  return (Date.now() - started) / 1000;
}

async function adminScene(browser) {
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 720 }, bypassCSP: true, recordVideo: { dir: OUT, size: { width: 1280, height: 720 } } });
  const p = await ctx.newPage();
  await prepare(p, 'ADMIN PANEL');
  await p.goto(ADMIN + '/login');
  await say(p, 'Operations sign in to the admin panel', 800);
  await typeInto(p.getByRole('textbox', { name: 'Phone or username' }), 'admin');
  await typeInto(p.getByRole('textbox', { name: 'Password' }), ADMIN_PASSWORD);
  await p.getByRole('button', { name: 'Sign in' }).click();
  await p.waitForURL((u) => !u.pathname.startsWith('/login'), { timeout: 30000 });

  const visit = async (route, text, ms = 3000, scroll = 0) => {
    const t0 = Date.now();
    await p.goto(ADMIN + route, { waitUntil: 'domcontentloaded' });
    await p.waitForFunction(() => !/Loading/i.test(document.body.innerText), null, { timeout: 20000 }).catch(() => {});
    await sleep(route === '/map' ? 3500 : 900); // map tiles arrive after the page does
    await say(p, text, 500);
    if (scroll) { await p.mouse.move(640, 400); await p.mouse.wheel(0, scroll); }
    await sleep(ms);
    console.log('admin', route, ((Date.now() - t0) / 1000).toFixed(1) + 's');
  };
  await visit('/', 'A live overview, and what needs attention right now', 3000);
  await visit('/map', 'Every online driver and active trip on a live map', 3000);
  await visit('/trips', 'All trips and their status, including the one just taken', 3000);
  await visit('/payments', 'Payments: the charge, the commission and the driver’s share', 3000);
  await visit('/ledger', 'An append-only double-entry ledger that must balance', 3000);
  await visit('/disputes', 'Chargebacks freeze the driver’s share until they are decided', 3000);
  // open the first dispute that has an "Answer" link: evidence is submitted from here
  const answer = p.getByRole('link', { name: /Answer|Evidence/ }).first();
  if (await answer.count()) {
    await answer.click();
    await sleep(1800);
    await say(p, 'Evidence for a chargeback is compiled from the trip and submitted to Stripe', 4000);
  }
  await visit('/incidents', 'Failed payouts, reversed transfers and refunds need a person: they become incidents', 3000);
  await visit('/verification', 'New drivers’ documents are reviewed and approved here', 3000);
  await visit('/support', 'Support conversations from customers and drivers', 3000);
  await visit('/audit', 'Every admin action is recorded in an audit log', 3000);

  const v = p.video();
  await ctx.close();
  fs.renameSync(await v.path(), path.join(OUT, 'admin.webm'));
}

(async () => {
  fs.rmSync(OUT, { recursive: true, force: true });
  fs.mkdirSync(OUT, { recursive: true });
  const browser = await chromium.launch();
  await card(browser, '0-title', 'Ridehail', 'A ride-sharing platform: customers, drivers and operations', 3500);
  const secs = await rideScene(browser);
  console.log('ride scene took', secs.toFixed(1), 's');
  await adminScene(browser);
  await card(browser, '9-end', 'Built and tested end to end', 'Spring Boot API · Next.js and Expo apps · Stripe payments and payouts · 387 backend tests', 4500);
  await browser.close();
  console.log(fs.readdirSync(OUT));
})().catch((e) => { console.error(e); process.exit(1); });
