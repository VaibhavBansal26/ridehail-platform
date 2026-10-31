# Admin panel

`admin-web/` is the operations console, a Next.js app that sits on top of the admin API (`/api/admin/**`). It shows what a
person running the platform needs: what is on the road right now, every trip and rider, payments and payouts, disputes and
finance incidents, and one "needs attention" summary that tells you whether anything is waiting for a human.

| Page | What it is for |
|---|---|
| Overview | The attention summary: open disputes (and how many are past their evidence deadline), open incidents by type, stuck payments and payouts, rider earnings on hold, provider funds vs what riders are waiting for, ledger balanced or not, riders and trips on the road. |
| Live map | Online riders and active trips on a map, refreshed every 5 s. Riders whose app stopped reporting a position are drawn faded and counted ("not reporting"). Click a trip or rider in the side list to centre on it. |
| Trips / trip detail | Filter by status. Detail shows names, fare, the timeline, the payment, every dispatch offer sent to riders and what each answered, and disputes. **Cancel trip** (with confirmation). |
| Riders | Status, rating, last position and whether they are still reporting. |
| Payments / payouts | Charges with **Retry** (stuck) and **Refund** (captured), both behind a confirmation; payouts with how long each has been waiting for funds. |
| Support | Conversations with customers and riders: who is waiting and for how long, the thread (plain text), reply, mark resolved. A badge in the menu counts people waiting. |
| Disputes | Status, amount, reason, evidence deadline (overdue ones in red), what the ledger did, an **Answer** page (facts from the trip, your explanation, submit once) and a link into the payment provider's dashboard (only `https://*.stripe.com` links are ever offered). Evidence itself is submitted there, not here. |
| Incidents | Failed bank payouts, reversed transfers, failed refunds. **Resolve** records what was done (it moves no money). |
| Ledger | The trial balance by account type and whether debits equal credits. |
| Audit log | Who did what: every change an admin makes, with the person, the time and the target. The table is append-only (a Postgres trigger refuses updates and deletes, like the ledger). |
| Admins | One account per person (`POST /api/admin/admins`, password of 12+ characters). Disable or re-enable accounts; you cannot disable yourself or the last active admin. The `ADMIN_PASSWORD` bootstrap account is the first one: create named accounts, then disable it if you like. |

## Run it

```bash
# 1. the API with a demo city in it (18 riders, 18 customers, 22 trips in every state, 5 disputes, 4 incidents)
mvn spring-boot:run -Dspring-boot.run.arguments=--ridehail.demo-data.enabled=true

# 2. the panel
cd admin-web
cp .env.example .env.local          # BACKEND_URL=http://localhost:8080, and ALLOW_INSECURE_COOKIES=true for plain-http local use
npm ci
npm run dev                          # http://localhost:3000, sign in as admin / dev-admin-password
```

Or the whole stack, `DEMO_DATA=true docker compose up --build`, serves the panel on http://localhost:3000 (it reaches the API
through the nginx gateway). The demo data is loaded by one instance only, only when the database has no riders yet, and it can
never run under the `prod` profile.

Checks: `npm run lint && npm run typecheck && npm test && npm run build` (CI runs the same).

## Configuration (server environment, read at request time)

| Variable | Meaning |
|---|---|
| `BACKEND_URL` | Where the Spring API is, as seen from the panel's server. Never reaches the browser. |
| `MAP_TILE_URL`, `MAP_TILE_ATTRIBUTION` | Tile server. The default is OpenStreetMap's public server: **development only**, its usage policy forbids heavy use. Use your own provider in production. The tile host is added to the page's `img-src` automatically. |
| `MAP_DEFAULT_VIEW` | `lat,lon,zoom` the map starts at when nothing is on the road. |
| `ALLOW_INSECURE_COOKIES` | `true` only when serving over plain http on localhost. Unset, cookies are `Secure` and the CSP upgrades insecure requests. |

## How it is built

**Backend for frontend.** The browser never sees a token and never talks to the Spring API. It talks to the panel's own
origin; the panel's server holds the session and calls the API.

```
browser ──cookies──▶ Next.js (proxy.ts, /api/session/*, /api/admin/[...path]) ──Bearer──▶ Spring API
```

- **Session.** `POST /api/session/login` signs in against `/api/auth/login` and **refuses any account that is not `ADMIN`**,
  with the same answer as a wrong password. The access and refresh tokens go into `httpOnly`, `SameSite=Strict`, `Secure`
  cookies; the refresh cookie is scoped to `/api` so it is only ever sent to API routes. Page JavaScript cannot read either.
- **Refresh.** When the API answers 401 the proxy route refreshes once with the refresh token, repeats the call, and sets the
  new cookies on the response. A refresh that fails, or yields a non-admin token, ends the session (cookies cleared, 401), and
  the client sends the user to the sign-in page with a `next` path (only same-site paths are honoured).
- **The proxy route** forwards only to `/api/admin/…`, only for path segments made of letters, digits, `_` and `-`
  (no `..`, no dots, no encoded separators). It forwards a bearer token and a JSON body and nothing else from the browser.
- **CSRF.** `SameSite=Strict` is the first lock. Every non-GET call must also come from the panel's own `Origin` and carry
  `x-ridehail-admin: 1`; a cross-site form cannot set that header.
- **Route protection.** `proxy.ts` (Next 16's name for middleware) redirects signed-out visitors to `/login`. That is a
  convenience only: the API enforces `ROLE_ADMIN` on every call, so a forged cookie gets a 401 from the API, not data.
- **CSP.** A fresh nonce per request: `script-src 'self' 'nonce-…' 'strict-dynamic'`, `style-src 'self' 'nonce-…'`,
  `frame-ancestors 'none'`, `connect-src 'self'`, `object-src 'none'`. The one relaxation is `style-src-attr 'unsafe-inline'`
  because Leaflet positions markers with style attributes. (Development relaxes `style-src` and allows `eval` because the dev
  server needs both; the production build was checked in a browser with zero violations.) Plus `X-Frame-Options`, HSTS,
  `nosniff`, `Referrer-Policy`, `Permissions-Policy`. Every page is rendered per request so the nonce can be applied.
- **Responses are validated.** Every API response is parsed with a zod schema (`lib/schemas.ts`); if the backend and the panel
  drift apart the page says so, naming the field, instead of showing `undefined`.
- **Data freshness.** TanStack Query polls (map 5 s, lists 15–30 s, summary 30 s), pauses in background tabs, and every action
  invalidates everything it could have changed. There is no push to the panel (see below).

## Admin API additions

The panel needed more than the first admin endpoints returned, so they grew (all `ADMIN`-only, all covered by tests):

- `GET /api/admin/trips` now carries pickup/drop coordinates, the four timestamps and customer/rider **names** (two batched
  lookups per page, not two per row); `GET /api/admin/riders` adds `rating` and `locationUpdatedAt`.
- `GET /api/admin/trips/{id}`: trip + payment + every offer (with the rider's answer) + disputes.
- `GET /api/admin/ops/live`: online riders with a position and active trips with coordinates, plus counts by status and
  `generatedAt`. Capped at 5,000 of each, with `truncated: true` when it was cut.

### The API contract is tested from both sides

`admin-web/fixtures/*.json` are real responses. The backend tests (`AdminOpsApiTest`, `DemoDataSeederTest`) fail if a response
stops having the same fields and types as its fixture; the panel's tests (`lib/contract.test.ts`) fail if its zod schemas cannot
parse the fixture. Change the API on purpose with `mvn test -Dfixtures.write=true`, review the fixture diff, and the panel's
tests then tell you what to update.

## Tests

`npm test` (Vitest, 93 tests): formatting helpers; open-redirect guard; the BFF's CSRF/Origin check, path allow-list, refresh
and logout behaviour; login and proxy route handlers (including that tokens never appear in a response body and that a customer
or rider login is refused); the API client; the incident-resolve flow in the page; and the contract fixtures. The security
checks were mutation-tested: removing the CSRF header requirement, the admin-role check, the path allow-list or the refresh
cookie's path each fails a test.

## Known limits

- **Login throttling is per account, not per address.** `/api/auth/login` allows 5 attempts per phone number at once, then one per
  minute (`ridehail.login-limit.*`), refusing with 429 + `Retry-After` *before* the password is checked, so a correct guess during a
  lockout does not get in. Unknown numbers are throttled identically, so the answer reveals nothing about which accounts exist.
  Shared across instances with Redis. Two consequences: guessing is capped at roughly 1,400 tries a day per account, and someone
  who knows the admin's phone can keep its allowance empty, so the real admin may wait up to a minute to sign in. There is no
  per-IP limit, because behind a proxy every user shares the proxy's address; if you front the panel with a WAF or reverse proxy,
  add an address limit there. A long `ADMIN_PASSWORD` and a VPN or IP allow-list are still the right baseline.
- **A disabled admin's open session lasts up to 15 minutes.** Disabling stops sign-in and token refresh at once, but an access token
  already issued stays valid until it expires. Change `ridehail.security.access-ttl-minutes` if that is too long.
- **The audit log covers the panel's actions** (cancel trip, retry/refund payment, resolve incident, create/disable/enable admin),
  recorded after they succeed. Failed attempts and sign-ins are not logged, and actions taken by calling the API directly with an
  admin token are recorded the same way (the log is in the API, not the panel).
- **Polling, not push.** Admins deliberately cannot subscribe to the WebSocket streams (they carry rider and customer data
  addressed to those users), so the map is a 5-second snapshot. A `/user/topic/ops` stream for admins would make it live.
- **Dispute evidence is text only.** The panel submits the trip facts and your explanation to the provider; files (receipts,
  screenshots) still go through the provider's dashboard.
- **The map shows riders as of their last reported position**, so a rider whose app is closed stays where they were until the
  "not reporting" threshold (2 minutes) marks them.
- **`package.json` pins `@emnapi/core`, `@emnapi/runtime` and `@emnapi/wasi-threads`.** npm leaves the WebAssembly fallbacks of
  `sharp` and Tailwind out of a lockfile generated on macOS, which makes `npm ci` fail on Linux (Docker, CI). Pinning them keeps the
  lockfile valid on every platform; remove them only after checking `npm ci` inside a Linux container.
