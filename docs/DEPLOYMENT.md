# Deploying and running it

This is the runbook for putting the platform in front of real users, and for the first hour of an incident. It says what is
proven, what is only built, and what is left to you. The shape is provider-neutral on purpose: every piece below is a container and a
few environment variables, so it moves to whatever platform you choose.

## What runs

```
                        TLS terminator (your load balancer / Caddy / nginx with certificates)
        ┌───────────────────────┬──────────────────────────┬───────────────────────────┐
  api.example.com        admin.example.com           rider.example.com        customer.example.com
        │                       │                          │                           │
     gateway (nginx)       admin-web (Next.js)       rider-web (Next.js)        customer-web (Next.js)
        │                       └──────────── all call the API server-to-server through the gateway ─────────┘
   app-a ... app-N  (Spring Boot, stateless; :8080 serving, :9090 metrics, internal only)
        │      │      │
  Postgres   Redis   RabbitMQ (STOMP)        Prometheus ← scrapes :9090 on every app instance
  (system of  (rate-limit    (realtime pushes
   record)     counters)      between instances)
```

- **The API is stateless.** Run as many instances as you need behind the gateway; there is no sticky session. Realtime reaches a
  user on whichever instance holds their WebSocket, via RabbitMQ (see [SCALING.md](SCALING.md)).
- **Postgres is the only thing that must never be lost.** Use a managed Postgres with point-in-time recovery, plus the backups below.
- **Redis and RabbitMQ can be lost without losing data.** Rate limits fall back to per-instance counters; realtime pushes stop
  crossing instances, and apps fall back to polling REST. Customers and riders notice slowness, not loss.
- **The phone apps are not hosted**: they are built and distributed through the app stores (see
  [../clients/README.md](../clients/README.md)); they only need the API's public address.

`docker-compose.prod.yml` is the reference for this topology (`cp .env.production.example .env.production`, fill it in, then
`docker compose -f docker-compose.yml -f docker-compose.prod.yml --env-file .env.production up -d --build`). On a managed
platform each service becomes that platform's equivalent.

## What was actually run, and what was not

Run and checked (in this repository's history, on a laptop):

| | |
|---|---|
| The `prod` profile boots against real Postgres, Redis and RabbitMQ | with throwaway secrets: Flyway applied all 5 migrations, both instances healthy, JSON logs |
| Metrics are **not** served on the public port | the gateway answers `/actuator/prometheus` with 401; the internal port serves it; Prometheus saw both instances `up` and loaded all 11 alert rules |
| A rolling restart under traffic loses nothing | one instance restarted while a request loop hit the gateway every 100 ms: 282 of 282 returned 200, and the instance logged a graceful shutdown |
| A backup restores, and the restore check can fail | `scripts/restore-drill.sh` passed on a real backup and failed (exit 1) on one whose ledger was made unbalanced, and on a truncated file |
| A whole ride under load, ledger intact | 30 riders and 15 customers for 50 s: 56 trips requested, 46 completed, **0 failed requests**, ledger balanced afterwards |

Built but **not** run here (a gap in this environment, not a known defect):

- The two web-app images (`clients/Dockerfile.web`): the dependency step and the standalone layout were checked, and CI builds
  both images on every push, but they were not started locally (disk space).
- Anything with real Stripe, real TLS, a real domain, or more than one machine.
- A load test against a stack sized like production. The numbers above are for one laptop running everything; they show the code does
  not fall over and where it is slow, not how much traffic production can take.

## First deployment checklist

1. **Secrets.** Generate `JWT_SECRET` (`openssl rand -base64 48`), `ADMIN_PASSWORD`, `DB_PASSWORD`, `REDIS_PASSWORD`,
   `RABBITMQ_PASSWORD`. Keep them in your platform's secret store, not in a file on a server. Rotating `JWT_SECRET` signs everyone out.
2. **The `prod` profile** (`SPRING_PROFILES_ACTIVE=prod`) refuses to start without them, and refuses the simulated payment
   gateway and the demo data. If it starts, the configuration is complete.
3. **Stripe**: follow [STRIPE_TEST_MODE_SETUP.md](STRIPE_TEST_MODE_SETUP.md) first in test mode, then section 6 to go live. The app
   logs `Stripe gateway enabled in LIVE mode` at startup and refuses mismatched keys.
4. **Origins.** `ALLOWED_ORIGINS` must list the https origins of the three web apps (the browser's WebSocket connects straight to the
   API). `PUBLIC_REALTIME_URL` is the `wss://` address of the API's `/ws`.
5. **TLS everywhere.** The web apps set `Secure` cookies and refuse to work over plain http in production.
   `ALLOW_INSECURE_COOKIES` must be unset or `false`. The gateway adds `X-Request-Id`; keep `X-Forwarded-For`/`-Proto` from your
   terminator so the API sees the real client address.
6. **The bootstrap admin.** Sign in to the admin panel with `ADMIN_PASSWORD`, create one named account per person under *Admins*,
   then disable the shared one.
7. **Webhooks.** Create both Stripe webhook endpoints (account scope and Connect scope) against `https://api.example.com/api/webhooks/stripe`.
8. **Monitoring.** Point Prometheus at the instances' `:9090` and route its alerts (below) to a person. Without that, nothing tells you
   a chargeback deadline is running.
9. **Backups.** Schedule `scripts/backup-postgres.sh` (or your managed database's snapshots) and put the restore drill on a calendar.

## Rider verification

New riders upload four documents (driving licence, vehicle registration, insurance, selfie) and an admin approves them under
*Verification* in the admin panel. Until then the rider cannot go online or be offered trips.

- **`DOCUMENT_ENCRYPTION_KEY`** (base64 of 32 random bytes) has **no default in production**. Documents are encrypted with AES-256-GCM
  before they reach the database. If the key is lost, every stored document is unreadable (riders would have to re-upload); keep an
  offline copy in your secret manager. Rotating it needs a re-encryption job that does not exist yet.
- **`RIDER_VERIFICATION_REQUIRED`** defaults to `true` in production. It only affects riders who sign up after it is set; existing riders
  stay verified.
- Documents are JPEG, PNG or PDF (checked by content, not file name), at most 5 MB. They live in Postgres (`rider_document_blobs`), so
  they are in your backups, and the backup is as sensitive as the documents. There is no virus scan and no retention/deletion job:
  decide your retention period before launch.
- Every time an admin opens a document it is recorded in the audit log (`VERIFICATION_DOCUMENT_VIEWED`).
- Insurance, licence and registration expiry is checked hourly: riders are warned 14 days ahead and taken offline when a document
  expires. Uploads are accepted only while not submitted, rejected or expired (no early renewal), and phone apps upload photos only.
- The `RiderVerificationsWaiting` alert fires when the oldest pending review is over two days old.

## Support chat

Customers and riders have one running conversation with support (the *Support* tab in every app). Admins answer from *Support* in
the admin panel, longest wait first; the person gets a push notification that says only that there is a reply, not what it says.

- Messages are plain text, up to 2000 characters, 20 per person per hour. A message may name one of the sender's own trips.
- A conversation marked resolved reopens when the person writes again. Nobody can read anyone else's conversation: the API only
  ever returns the caller's own.
- Staff replies and resolutions are in the audit log (without the message text).
- **Not built, decide before launch:** staff assignment (everyone sees every conversation), attachments and photos, typing and read
  receipts, live delivery (apps refresh every 15 seconds and on a push), and **retention**: messages are kept forever, and they can
  contain personal details, so set a deletion period.
- The `SupportUnanswered` alert fires when someone has waited over six hours; `ridehail_support_awaiting` is the queue length.

## Phone notifications

A rider only hears about an offer while the app is open unless notifications are on. The server sends them through **Expo's push
service**, which forwards to Apple and Google; nothing is sent until you choose a provider.

| | |
|---|---|
| `PUSH_PROVIDER` | `none` (default in development: nothing leaves the server) or `expo` (the default under the `prod` profile) |
| `EXPO_ACCESS_TOKEN` | only if you turned on "enhanced security for push" in your Expo project |

- **What is sent**, and only these: a new ride request to the rider (fare and distance, held by the provider for no longer than the
  offer lasts, so a stale offer never arrives late), "your rider is on the way / has arrived" to the customer, a cancellation to both,
  "no riders available", a failed payment, and a failed bank payout. **No names beyond a rider's first name, no phone numbers, no
  addresses, no coordinates.** While the app is open nothing is shown (the live connection already told them).
- **Sending never touches a ride.** It runs on its own small thread pool with a bounded queue; a provider outage drops notifications,
  logs a warning, and nothing else. Phones the provider reports as gone (app uninstalled) are forgotten.
- **To make it work on real phones** you need, outside this repository: an Expo account and an EAS project (put its id in each phone
  app's `app.json` as `extra.eas.projectId`), push credentials (Apple's APNs key and Google's FCM credentials) added to the EAS
  project, and a **development or production build** of each phone app. Expo Go cannot receive remote push on Android. Without a
  project id the app skips registration quietly and works as before.
- **Not verified:** delivery to a real phone, and the Expo service itself. What is tested is the server (registration, who gets what,
  privacy of the text, retries, uninstalled phones, outage isolation), the Expo adapter against a local stand-in, and the phone apps'
  registration logic with the OS modules mocked.

## Rolling out a new version

- **Rolling restarts are safe**: instances shut down gracefully (in-flight requests finish, up to 25 s), the gateway re-resolves and
  retries another instance, and the health endpoint `/api/health` (200 or 503) is what a load balancer should poll. Orchestrators can
  use `/actuator/health/liveness` and `/readiness` on the internal port.
- **Flyway runs at startup**, on the first instance up; the others wait for its lock. Migrations are forward-only, so **rolling back
  the code does not roll back the schema.** Write every migration so the *previous* version of the app still works with it
  (add columns and tables, do not rename or drop in the same release), and deploy the destructive step one release later.
- **If a release is bad:** roll the code back; leave the schema. Restoring a database backup loses everything since the backup,
  including real money movements, so it is the last resort and needs the Stripe Dashboard to reconcile.

## Watching it

Metrics (Prometheus, internal port) and what each is for:

| Metric | Why |
|---|---|
| `ridehail_ledger_balanced` | **Must be 1.** Debits equal credits, checked every 5 minutes. 0 pages immediately. |
| `ridehail_payments{status="REQUIRES_ATTENTION"}` | Charges that failed for a reason only a person can fix. Those customers cannot book. |
| `ridehail_payments{status="PENDING"}` | Charges waiting for a retry. Growing means the provider or our key is the problem. |
| `ridehail_payouts{status}` | `REQUIRES_ATTENTION` = a transfer may or may not have gone out; `AWAITING_FUNDS` = riders waiting for card money to settle. |
| `ridehail_disputes_open`, `ridehail_incidents_open` | Chargebacks (with a response deadline) and failed bank payouts, reversed transfers, failed refunds. |
| `ridehail_riders{status}` | Supply: how many riders are online and on a trip. |
| `ridehail_ride_events_total{type}` | Requests, accepts, completions, cancellations, payments: rates and ratios. |
| `http_server_requests_*`, `jvm_*`, `hikaricp_*` | The usual: error rate, latency, heap, database pool. |

The alert rules are in [`docker/prometheus/alerts.yml`](../docker/prometheus/alerts.yml). Prometheus evaluates them; **something
must deliver them** (Alertmanager to a pager or chat, or your platform's alerting). That wiring is yours.

Logs are one JSON object per line on stdout in production (collect them with your platform's log shipper). Every request has an id
in the `requestId` field and in the `X-Request-Id` response header: ask a user for the time and, from the app, the id, and you
have the whole story. Never log tokens or card data; none are logged today.

## Backups and restoring

```bash
PGDATABASE=ridehail PGHOST=… PGUSER=… PGPASSWORD=… BACKUP_DIR=/backups RETENTION_DAYS=14 scripts/backup-postgres.sh
scripts/restore-drill.sh /backups/ridehail-<timestamp>.dump       # restores to a scratch database and checks it, then drops it
```

- A backup is only counted if `pg_restore --list` can read it back and it is not suspiciously small. The drill then checks that
  every migration succeeded, the ledger balances overall and per transaction, the tamper-proofing triggers survived, and prints row
  counts (a backup that restored empty is obvious). **Run the drill at least monthly and after schema changes.**
- Copy backups off the database's machine, ideally to another region (`AFTER_BACKUP` runs a command with the file path).
- The ledger and the admin audit log are append-only **in the database**: even a mistaken `UPDATE` or `DELETE` is refused by a
  trigger, and a ledger transaction that does not balance is refused at commit. Those protections are part of what the drill checks.

## When something goes wrong

| Symptom | First moves |
|---|---|
| **LedgerUnbalanced** | Stop payouts and refunds (disable the payout and refund buttons by not operating them; if needed stop the instances). Do not edit the ledger: it is append-only for a reason. Find the transaction with `scripts/restore-drill.sh`'s checks against a fresh backup, compare with Stripe, and post a corrective entry through the application. |
| **PaymentsNeedAttention** | Admin panel > Payments. Declined card: the customer must save another, then **Retry**. Before retrying after a timeout, check in Stripe that the first attempt did not charge them (idempotency keys expire after about 24 hours). |
| **PaymentsBacklog / `Stripe rejected our API key` in logs** | The key was revoked, restricted too far, or is the wrong mode. Fix `STRIPE_API_KEY`; pending charges retry by themselves. |
| **ChargebackOpen** | Respond in the Stripe Dashboard before the deadline (the panel links to it). Unanswered disputes are lost by default. |
| **Webhooks failing** (Stripe Dashboard shows 400s) | Wrong signing secret (the log says `Ignoring ... event ... Check STRIPE_WEBHOOK_SECRET` for a mode mix-up), or something rewriting the request body. Stripe retries for days, so fixing it catches up. |
| **Redis down** | Rate limits fall back to per instance; nothing else breaks. Fix at leisure. |
| **RabbitMQ down** | Live pushes between instances stop; apps fall back to polling (offers every 5 s, trips every 10-15 s). A rider can miss the 15-second offer window: restore it promptly. |
| **Postgres failover** | Instances reconnect by themselves. In-flight requests fail and the apps retry. Check `hikaricp_connections` recovered, then `ridehail_ledger_balanced`. |
| **Everyone signed out** | `JWT_SECRET` changed. Expected after a rotation. |
| **Sign-ups slow** | Password hashing is deliberately expensive (bcrypt) and CPU-bound: a burst of 45 simultaneous sign-ups took 2-5 s each on the test laptop. Add instances, or rate-limit sign-ups at the gateway during a campaign. |

## Known limits worth knowing before launch

- **No per-IP rate limit** on sign-in (it is per account); put a WAF or the gateway's `limit_req` in front for that.
- **A web page only reports a rider's position while it is open**; the phone app is what keeps reporting with the screen off, and it
  has not yet been run on a device.
- **Push notifications are built but unproven on a device** (see above): until credentials and a build exist, a rider only receives offers while the app is open.
- **Single region.** Losing the region loses the service until you restore elsewhere from backups.
- **Capacity is unmeasured** on production-sized hardware. Run `scripts/loadtest.mjs` against a staging copy before a launch and
  after big changes; it prints latency percentiles, failures, and whether the books still balance.
