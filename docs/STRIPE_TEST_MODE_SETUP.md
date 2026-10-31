# Stripe test-mode setup and verification

A checklist to get the Stripe integration running end to end in **test mode**, then prove it works by driving a real
ride through charge, ledger, payout and refund. Nothing here moves real money.

The automated tests already cover the adapter against a *stub* of Stripe. This checklist is the part they cannot do:
confirm it against Stripe itself. Expect to find small things; the troubleshooting table at the end covers the likely ones.

**Time:** about 45 minutes the first time. **You need:** a Stripe account, the [Stripe CLI](https://docs.stripe.com/stripe-cli),
JDK 21, Maven, `curl` and `jq`.

---

## 1. Stripe Dashboard (once)

Do this in **test mode** (toggle at the top of the Dashboard, or use a sandbox). Dashboard menus move around; if a
name below differs slightly, search for the term in italics.

- [ ] **1.1 Copy the API keys.** *Developers → API keys*: the **secret key** (`sk_test_…`) and **publishable key** (`pk_test_…`).
  Test mode accepts the plain secret key. Before going live, switch to a restricted key (section 6).
- [ ] **1.2 Turn on Connect.** *Connect → Get started*. Choose **platform or marketplace**, and fill in the platform
  profile it asks for. Riders are paid as **Express** accounts (Stripe hosts their identity and bank forms; we never see them).
- [ ] **1.3 Note your platform's country** (*Settings → Business details*). Riders must be in the same country as the
  platform for this integration; see "Known gaps" at the end. Set `RIDEHAIL_STRIPE_CONNECT_COUNTRY` to its two-letter code (e.g. `US`).
- [ ] **1.4 Check cards are enabled.** *Settings → Payment methods*: **Cards** on.
- [ ] **1.5 Create the two webhook endpoints.** Only needed for a deployed environment. For local work use the Stripe CLI
  (section 3) and skip this. Stripe signs each endpoint with its **own secret**, and delivers events by *scope*, so we need two endpoints
  pointing at the same URL, `https://<your-host>/api/webhooks/stripe`:

  | Endpoint | *Events from* | Events to subscribe |
  |---|---|---|
  | **A: your account** | **Your account** | `setup_intent.succeeded`, `payment_intent.succeeded`, `balance.available`, `charge.dispute.created`, `charge.dispute.updated`, `charge.dispute.closed`, `refund.failed`*, `transfer.reversed` |
  | **B: Connect** | **Connected accounts** | `account.updated`, `payout.failed`, `account.external_account.created`, `account.external_account.updated` |

  \* If `refund.failed` is not in the event list, skip it; refunds still work, you just lose one alert.
  The `charge.dispute.*` events drive dispute handling (see [DISPUTES.md](DISPUTES.md)): subscribe to all three. A dispute
  you are not subscribed to is invisible to us, and its deadline to respond keeps running.
  `balance.available` is how a payout that is waiting for card funds to settle gets released the moment Stripe has the money;
  without it the payout is still released, just at the next poll (every 15 minutes by default).
  Set the endpoints' **API version** to the same one the SDK uses (the Dashboard shows the choices; pick the newest),
  then copy each endpoint's **Signing secret** (`whsec_…`). **Both** go into `STRIPE_WEBHOOK_SECRET`, comma-separated (section 2).

  > Why two: `account.updated` (how we learn a rider finished onboarding) and `payout.failed` happen *on the rider's
  > connected account*, so Stripe only sends them to a Connected-accounts endpoint. With only endpoint A, riders would
  > finish onboarding and never become payable.

- [ ] **1.6 Leave the platform's payout settings alone for now.** Riders' payouts to their banks run on their connected
  accounts' default (automatic) schedule.

---

## 2. Environment variables

| Variable | Value | Where it comes from |
|---|---|---|
| `RIDEHAIL_PAYMENTS_GATEWAY` | `stripe` | Selects the Stripe adapter (the `prod` profile sets this itself). Default is the simulator. |
| `STRIPE_API_KEY` | `sk_test_…` | 1.1 |
| `STRIPE_PUBLISHABLE_KEY` | `pk_test_…` | 1.1. Handed to the mobile SDK. |
| `STRIPE_WEBHOOK_SECRET` | `whsec_A,whsec_B` | 1.5 (deployed), or the CLI's secret (section 3). **Comma-separated**, one per endpoint. |
| `RIDEHAIL_STRIPE_CONNECT_COUNTRY` | `US` | 1.3 |
| `STRIPE_ONBOARDING_RETURN_URL` | where the rider lands after onboarding | A deep link into your rider app, e.g. `ridehail://payouts/done`. For curl testing any https URL works. |
| `STRIPE_CARD_RETURN_URL` | where Stripe's hosted card page sends the customer after saving a card | `ridehail://card/done` for the phone app; for the web app an https page of its own, e.g. `https://customer.example.com/account` |
| `STRIPE_CARD_CANCEL_URL` | where it sends them if they back out | e.g. `ridehail://card/cancelled` or the same web page |
| `STRIPE_ONBOARDING_REFRESH_URL` | where Stripe sends a rider whose onboarding link expired | e.g. `ridehail://payouts/retry`. The app should call the onboarding endpoint again for a fresh link. |
| `JWT_SECRET`, `ADMIN_PASSWORD`, `ALLOWED_ORIGINS`, `DB_URL`, `DB_USER`, `DB_PASSWORD` | | Only for the `prod` profile. Not needed for local testing. |

The app refuses to start in Stripe mode without an API key and at least one webhook secret, and it checks they look right
so a mix-up fails at startup instead of as failed payments later: the API key must be a secret or restricted key
(`sk_`/`rk_` + `test`/`live`), the publishable key must be a `pk_` key **of the same mode**, and every webhook secret must start
with `whsec_`. The error names the setting, never its value. A healthy start logs `Stripe gateway enabled in TEST mode` (or `LIVE`).

Signed events from the *other* mode are ignored: a live-mode event reaching a test-key deployment (or the reverse) answers `200`
so Stripe stops retrying, changes nothing, and logs `Ignoring … event … Check STRIPE_WEBHOOK_SECRET` at `ERROR`.

Secrets never go in git. For local work, keep them in an untracked `.env.stripe` file:

```bash
# .env.stripe  (add to .gitignore)
export RIDEHAIL_PAYMENTS_GATEWAY=stripe
export STRIPE_API_KEY=sk_test_...
export STRIPE_PUBLISHABLE_KEY=pk_test_...
export STRIPE_WEBHOOK_SECRET=whsec_...          # from `stripe listen`, section 3
export RIDEHAIL_STRIPE_CONNECT_COUNTRY=US
export STRIPE_ONBOARDING_RETURN_URL=https://example.com/done
export STRIPE_ONBOARDING_REFRESH_URL=https://example.com/retry
# keep data across app restarts (drill H2 restarts the app); the default in-memory database would be wiped
export SPRING_DATASOURCE_URL='jdbc:h2:file:./data/ridehail;MODE=PostgreSQL'
export SPRING_JPA_HIBERNATE_DDL_AUTO=update
```

Add `.env.stripe` and `data/` to `.gitignore`. To start over from scratch, delete the `data/` folder.

---

## 3. Local webhooks with the Stripe CLI

- [ ] **3.1** Install and log in: `brew install stripe/stripe-cli/stripe`, then `stripe login`. Make sure you're pointing at your test account.
- [ ] **3.2** In a dedicated terminal, forward **both** event scopes to the app and leave it running:

  ```bash
  stripe listen \
    --forward-to         localhost:8080/api/webhooks/stripe \
    --forward-connect-to localhost:8080/api/webhooks/stripe
  ```

  It prints `Ready! Your webhook signing secret is whsec_…`. Put that value in `STRIPE_WEBHOOK_SECRET`
  (one secret covers both scopes while using the CLI). Every forwarded event shows up in this terminal with the
  HTTP status the app returned. **`[200]` is what you want to see.**
- [ ] **3.3** Start the app with the environment loaded:

  ```bash
  source .env.stripe
  mvn spring-boot:run
  ```

  Expect a clean start. If it fails with `ridehail.stripe.api-key and ... webhook-secret are required`, a variable didn't load.

---

## 4. Verification script

Run these in order in a third terminal. Each step says what you should see, and where to double-check in Stripe.
Variables carry over between steps, so keep the same shell.

```bash
API=http://localhost:8080
json() { curl -sS -H 'Content-Type: application/json' "$@"; }   # prints the response body
```

### Phase A: the wiring

- [ ] **A1. App is up:** `curl -s $API/actuator/health` returns `{"status":"UP"}`.
- [ ] **A2. Unsigned webhooks are refused:**

  ```bash
  curl -s -o /dev/null -w '%{http_code}\n' -X POST $API/api/webhooks/stripe -d '{}'
  ```

  Expect `400`. (This endpoint needs no login, but it must never accept an unsigned request.)
- [ ] **A3. Signed webhooks get through:**

  ```bash
  stripe trigger customer.created
  ```

  In the `stripe listen` terminal you should see `[200] POST …/api/webhooks/stripe`. (The app acknowledges events it doesn't act on.)
  A `[400]` here means the secret is wrong; see troubleshooting.

### Phase B: users

- [ ] **B1. Register a customer and a rider, capture their tokens:**

  ```bash
  CUST=$(json -X POST $API/api/auth/register/customer \
    -d '{"name":"Test Customer","phone":"+15550000001","password":"password123"}')
  RIDER=$(json -X POST $API/api/auth/register/rider \
    -d '{"name":"Test Rider","phone":"+15550000002","password":"password123","vehicleType":"SEDAN"}')
  CT=$(echo "$CUST"  | jq -r .accessToken); CID=$(echo "$CUST"  | jq -r .profileId)
  RT=$(echo "$RIDER" | jq -r .accessToken); RID=$(echo "$RIDER" | jq -r .profileId)
  ADMIN=$(json -X POST $API/api/auth/login -d '{"phone":"admin","password":"dev-admin-password"}' | jq -r .accessToken)
  auth() { echo "Authorization: Bearer $1"; }
  ```

  Expect three non-empty tokens. (Because the database now persists, re-running this on a later day fails with `409` for the same phone numbers: use new ones, or delete `data/`.)

### Phase C: the customer's card

- [ ] **C1. Start card setup** (this is what the mobile app does first):

  ```bash
  SETUP=$(json -X POST $API/api/customers/me/payment-method/setup -H "$(auth $CT)")
  echo "$SETUP" | jq .
  SETI=$(echo "$SETUP" | jq -r .clientSecret | sed 's/_secret_.*//')
  ```

  Expect a `clientSecret` like `seti_…_secret_…` and your publishable key. *Dashboard → Customers* now has a new, empty customer.
- [ ] **C1b. The customer apps use Stripe's hosted page instead of the SDK:**

  ```bash
  json -X POST $API/api/customers/me/payment-method/checkout -H "$(auth $CT)" | jq .
  ```

  Expect a `url` on `checkout.stripe.com`. Open it in a browser and pay with the test card `4242 4242 4242 4242` (any future
  expiry and CVC); the page then redirects to `STRIPE_CARD_RETURN_URL`. In the `stripe listen` terminal expect
  `setup_intent.succeeded` → `[200]` (Checkout's SetupIntent ends in the same event as the SDK flow), and C4 then shows
  `"hasPaymentMethod": true`. Which payment methods the page offers comes from the Dashboard's payment method settings:
  leave only **Cards** on (1.4). This path has been tested against a stub of Stripe, not against Stripe itself.
- [ ] **C2. A client cannot just claim a card** (it would let someone attach another person's card):

  ```bash
  curl -s -o /dev/null -w '%{http_code}\n' -X PUT $API/api/customers/me/payment-method \
    -H "$(auth $CT)" -H 'Content-Type: application/json' -d '{"token":"cus_x|pm_x"}'
  ```

  Expect `403`.
- [ ] **C3. Play the part of the mobile SDK.** The real app confirms the setup with the card the user typed; the CLI can
  do the same using a Stripe test card. This one is the card that **adds funds straight to your available balance**,
  which the payout step later needs (see "Why this card" after Phase H):

  ```bash
  stripe setup_intents confirm $SETI --payment-method pm_card_bypassPending
  ```

  In the `stripe listen` terminal expect `setup_intent.succeeded` → `[200]`.
- [ ] **C4. The app now knows the card (arrived by webhook, not by the client):**

  ```bash
  json $API/api/customers/me -H "$(auth $CT)" | jq .
  ```

  Expect `"hasPaymentMethod": true`.

### Phase D: the rider's payout account

- [ ] **D1. Start onboarding:**

  ```bash
  json -X POST $API/api/riders/me/payout-account/onboarding -H "$(auth $RT)" | jq .
  ```

  Expect a `url` on `connect.stripe.com`. *Dashboard → Connect → Accounts* shows a new Express account (restricted, onboarding incomplete).
- [ ] **D2. Withdrawals are blocked until onboarding finishes:**

  ```bash
  curl -s -o /dev/null -w '%{http_code}\n' -X POST $API/api/riders/me/payouts -H "$(auth $RT)"
  ```

  Expect `409`.
- [ ] **D3. Complete onboarding in a browser** by opening the `url`. In test mode use Stripe's test values
  ([reference](https://docs.stripe.com/connect/testing)):

  | Field | Value |
  |---|---|
  | Phone / SMS code | `0000000000` / `000-000` |
  | SSN / ID number | `000000000` (last 4: `0000`) |
  | Date of birth | `1901-01-01` |
  | Address line 1 | `address_full_match` (with a real city, state and ZIP) |
  | Business website | `https://accessible.stripe.com` |
  | Bank account (US) | routing `110000000`, account `000123456789` |

  Test mode also shows shortcuts such as "Use test account" on some screens; use whichever your screens offer.
- [ ] **D4. The app learns the rider can be paid:** in the `stripe listen` terminal expect `account.updated` → `[200]`
  (possibly several, as Stripe verifies things). Then:

  ```bash
  json $API/api/riders/me/earnings -H "$(auth $RT)" | jq '{available, hasPayoutAccount}'
  ```

  Expect `"hasPayoutAccount": true`. If it stays `false`, see troubleshooting ("account.updated never arrives").

### Phase E: a ride, and the charge

- [ ] **E1. Rider goes online near the pickup, customer asks for a fare and books:**

  ```bash
  json -X POST $API/api/riders/me/online -H "$(auth $RT)" -d '{"lat":12.9766,"lon":77.5946}' >/dev/null
  QUOTE=$(json -X POST $API/api/fares/quote -H "$(auth $CT)" -d \
    '{"vehicleType":"SEDAN","pickupLat":12.9716,"pickupLon":77.5946,"dropLat":12.9352,"dropLon":77.6245}')
  echo "$QUOTE" | jq '{quoteId, total, surgeMultiplier, currency}'
  TRIP=$(json -X POST $API/api/trips -H "$(auth $CT)" -d "{\"quoteId\":\"$(echo "$QUOTE" | jq -r .quoteId)\"}" | jq -r .id)
  ```

- [ ] **E2. The rider accepts and drives the trip** (the same calls the rider app makes):

  ```bash
  OFFER=$(json $API/api/riders/me/offers -H "$(auth $RT)" | jq -r '.[0].offerId')
  curl -s -X POST $API/api/riders/me/offers/$OFFER/accept -H "$(auth $RT)"
  for step in arrive start complete; do
    json -X POST $API/api/trips/$TRIP/$step -H "$(auth $RT)" | jq -r '.status'
  done
  ```

  Expect `ARRIVED`, `IN_PROGRESS`, `COMPLETED`.
- [ ] **E3. The customer was charged.** Wait a couple of seconds (the charge runs in the background), then:

  ```bash
  json "$API/api/admin/payments?status=CAPTURED" -H "$(auth $ADMIN)" | jq '.items[0]'
  PAYMENT=$(json "$API/api/admin/payments?status=CAPTURED" -H "$(auth $ADMIN)" | jq -r '.items[0].id')
  ```

  Check three things:
  1. The payment has `status: CAPTURED`; `amount` equals the quote's `total`; `commission` is 20% and `riderAmount` the rest.
  2. *Dashboard → Payments*: a successful payment for the same amount **in cents** (e.g. `9.99` shows as `$9.99`), on the test customer, with description "Ride payment".
  3. `stripe listen` shows `payment_intent.succeeded` → `[200]`. That event finds the payment already captured and changes nothing.
- [ ] **E4. The books balance and the rider has been credited:**

  ```bash
  json $API/api/admin/ledger/trial-balance -H "$(auth $ADMIN)" | jq '{balanced, totalDebits, totalCredits}'
  json $API/api/riders/me/earnings -H "$(auth $RT)" | jq '{available, recent}'
  ```

  Expect `"balanced": true`, equal debits and credits, and `available` = the rider's share.

### Phase F: payout (Connect transfer)

- [ ] **F1. The rider withdraws everything available:**

  ```bash
  json -X POST $API/api/riders/me/payouts -H "$(auth $RT)" | jq .
  ```

  Expect `"status": "PAID"` and the rider's share as `amount`.
  - *Dashboard → Connect → Transfers*: a transfer to the rider's account for that amount.
  - *Connect → Accounts → (the account)*: the funds appear in its balance and are paid to the test bank on its schedule.
  - Run the earnings call again: `available` is `0`, and `recent` now includes a `PAYOUT_RESERVE` entry.
  - If you get `"status": "AWAITING_FUNDS"` instead, Stripe has not settled the money yet; see H3 and "Why this card" below.

### Phase G: refund

- [ ] **G1. Refund the charge as an admin:**

  ```bash
  json -X POST $API/api/admin/payments/$PAYMENT/refund -H "$(auth $ADMIN)" | jq '.status'
  json $API/api/admin/ledger/trial-balance -H "$(auth $ADMIN)" | jq .balanced
  ```

  Expect `"REFUNDED"` and `true`. *Dashboard → Payments*: the payment shows as refunded. Note the refund reverses the
  rider's earnings in our ledger (their balance goes negative if they already withdrew). It does **not** pull the money back
  from the rider's Stripe account; that's a follow-up, see "Known gaps".

### Phase H: failure drills

These are the cases that matter most in production. Use a fresh customer each time (new phone numbers) and the
same rider.

- [ ] **H1. A card that saves fine but fails when charged** (Stripe's `4000000000000341`):
  1. Register a customer. Run C1, then `stripe setup_intents confirm $SETI --payment-method pm_card_chargeCustomerFail`.
  2. Ride and complete a trip (E1 and E2 with the new customer's token).
  3. Check the payment: `json "$API/api/admin/payments?status=REQUIRES_ATTENTION" -H "$(auth $ADMIN)" | jq '.items[0]'`.
     Expect a decline reason, and **no** earnings for the rider (E4's `available` unchanged).
  4. The customer is blocked from booking: the quote then book returns `409` "You have an unpaid trip".
  5. Recover: the customer saves a working card (C1, then confirm with `pm_card_visa`), and then
     `json -X POST $API/api/admin/payments/<id>/retry -H "$(auth $ADMIN)"` → `CAPTURED`, rider credited, booking allowed again.
- [ ] **H2. Stripe unreachable or the key is wrong:** stop the app, restart with `STRIPE_API_KEY=sk_test_invalid`, and complete a trip.
  The payment stays `PENDING` (not `REQUIRES_ATTENTION`, because it isn't the customer's fault) and the log shows
  `Stripe rejected our API key`. Restart with the right key and wait for the retry job (or about 30 seconds of backoff to elapse);
  it captures with the same idempotency key, so there is no double charge. *Dashboard → Developers → Logs* shows the 401s.
- [ ] **H3. Payout before funds are available** (the payout *waits*; it does not fail):
  1. Save a customer's card with `pm_card_visa` (4242…) instead of `pm_card_bypassPending`, so the charge lands in
     Stripe's **pending** balance.
  2. Complete a trip, then request a payout. Expect `201` with `"status": "AWAITING_FUNDS"` and a `waitingSince` time.
     The app read your Stripe balance, saw nothing settled, and **did not attempt the transfer** (check *Dashboard → Connect →
     Transfers*: nothing there). The rider's money is parked "in transit" (earnings show `payoutsInProgress`), and a second request returns `409`.
  3. See the picture ops sees: `json $API/api/admin/payouts/funding -H "$(auth $ADMIN)" | jq .`: `providerAvailable` (settled money
     at Stripe) against `awaitingTotal` (what riders are waiting for).
  4. It looks again every 15 minutes and **does not use up error retries**; it stays `AWAITING_FUNDS` for up to 10 days, not 8 minutes.
     To see it release without waiting days, add settled funds from a second customer using the `pm_card_bypassPending` card and
     run a trip. The waiting payout goes to `PAID` at the next 15-minute look at the latest; if Stripe also sends `balance.available`
     (watch `stripe listen`; I have not confirmed it fires for this test card) it is released within seconds.
  5. If funds never arrive: after 10 days, because nothing was ever sent, the money **returns to the rider's balance** and the
     payout shows `FAILED` ("returned to your balance"). If an earlier call had timed out (outcome unknown) it instead becomes
     `REQUIRES_ATTENTION` and stays reserved for a person, because a transfer might have gone out.
- [ ] **H4. Webhook redelivery is harmless:** in the Dashboard, *Developers → Webhooks → (endpoint) → an event → Resend*
  (deployed endpoints only). The app answers `200` with `DUPLICATE` and changes nothing.
- [ ] **H5. Authentication-required card** (`4000002760003184`): this card always needs the customer's 3-D Secure
  step, so it cannot be confirmed from the CLI and, if saved by the real app, off-session charges against it are declined
  with "Customer authentication required". Treat as expected today; see "Known gaps".

### Why "this card" matters (`pm_card_bypassPending`)

Normal test charges land in your platform's **pending** balance and only become available after a simulated delay of days.
Connect transfers draw on **available** balance, so a payout straight after a 4242 payment is refused. Stripe's test card
`4000000000000077` (token `pm_card_bypassPending`) credits the **available** balance immediately, so the whole chain
charge → ledger → payout can be tested in one sitting. Use it for every happy-path run; use 4242 only for H3.

### Pass criteria

You're done when A1–A3, B1, C1–C4, D1–D4, E1–E4, F1, G1 all behaved as stated, H1 recovered, and `trial-balance` says
`balanced: true` after everything.

---

## 5. If something fails

| Symptom | Likely cause and fix |
|---|---|
| `stripe listen` shows `[400]` for every event | The app has the wrong secret. Copy the `whsec_…` that `stripe listen` printed *this session* into `STRIPE_WEBHOOK_SECRET` and restart the app. |
| Deployed endpoint gets `400` for some events only | Missing the second secret. `account.updated` and `payout.failed` come from endpoint B; its secret must be in `STRIPE_WEBHOOK_SECRET` too (comma-separated). |
| A proxy or load balancer in front of the app and signatures fail | Something is modifying the request body. Signatures are computed over the exact bytes Stripe sent; make sure nothing rewrites, re-encodes or compresses the body. |
| `account.updated` never arrives locally | You started `stripe listen` without `--forward-connect-to`. Connected-account events are only forwarded with that flag. |
| `account.updated` never arrives when deployed | Endpoint B was created as *Your account* instead of *Connected accounts*. |
| Rider stays `hasPayoutAccount: false` | Onboarding isn't finished (Stripe still needs details) or the bank step was skipped. Open a fresh link with D1 and look at *Connect → Accounts → the account → Requirements*. |
| `502` from `/payment-method/setup` or `/payout-account/onboarding` | Stripe refused the call. Check the app log, and *Developers → Logs*. Usual causes: wrong key, Connect not enabled (1.2), or a country that doesn't match the platform (1.3). |
| `403` on `PUT /payment-method` or `PUT /payout-account` | Expected in Stripe mode: these dev shortcuts are disabled so clients cannot attach someone else's card or bank. Use the setup/onboarding flows. |
| Payment stuck `PENDING`, log says `Stripe rejected our API key` | Fix `STRIPE_API_KEY`; it will retry by itself. |
| Payment `REQUIRES_ATTENTION`, reason `Card declined: …` | The customer's card failed. After they save another card, retry from the admin endpoint (H1 step 5). |
| Payout stays `AWAITING_FUNDS` | Normal while card payments are still pending at Stripe (H3); compare `GET /api/admin/payouts/funding`. It releases itself when funds settle. Long-running: check you subscribed `balance.available`, and that the card payments are really succeeding. |
| Payout `REQUIRES_ATTENTION` | Stripe errored or timed out repeatedly (8 attempts over about an hour), so the outcome is unknown: check *Dashboard → Connect → Transfers* for the payout's id in the metadata `ridehail_key`, then resolve by hand. Its `failureReason` says why. |
| App fails at start: `Stripe is misconfigured: …` | A key is the wrong kind or the wrong mode (the message says which setting). Typical: a `pk_` key in `STRIPE_API_KEY`, a test publishable key next to a live secret key, or an endpoint id instead of its `whsec_` signing secret. |
| Log: `Ignoring … event … it is a live-mode event but the API key is test-mode` (or the reverse) | `STRIPE_WEBHOOK_SECRET` belongs to the other mode's endpoint. Use the secrets of the endpoints created in the same mode as the API key. |
| App fails at start: `webhook-secret are required` | `STRIPE_API_KEY` or `STRIPE_WEBHOOK_SECRET` isn't set in that shell. |
| `409` "Phone number already registered" | The local database persists in `data/`. Use new phone numbers, or delete `data/` and restart. |

---

## 6. Going live (different from test mode)

- [ ] Complete Stripe's account activation, and **Connect activation** for your platform (live Connect needs its own review).
- [ ] Use **live** keys, and create **new live-mode webhook endpoints A and B** (test endpoints don't carry over). Each has
  a new secret; set both in `STRIPE_WEBHOOK_SECRET`.
- [ ] Switch `STRIPE_API_KEY` to a **restricted key** with write access to only: PaymentIntents, SetupIntents, Customers,
  Refunds, Transfers, Connect accounts and Account Links, and read access to PaymentIntents (refunds read the original payment's currency).
- [ ] Run with `SPRING_PROFILES_ACTIVE=prod`. It requires the secrets above plus `JWT_SECRET`, `ADMIN_PASSWORD`,
  `ALLOWED_ORIGINS` and the database settings, and it refuses to start with the simulator.
- [ ] Database: the `prod` profile runs the Flyway migrations at startup and then validates the schema against the entities.
- [ ] **Check the startup log says `Stripe gateway enabled in LIVE mode`.** If it says TEST, the key is a test key.
- [ ] **Do a real first transaction before opening to customers.** With your own card and your own rider account: save the card,
  complete a trip for the minimum fare, confirm the charge in the live Dashboard and the ledger (`balanced: true`), pay the rider out,
  then refund it from the admin panel. Both webhook endpoints should show `200` deliveries under *Developers → Webhooks*;
  a delivery that keeps failing is a wrong secret (see section 5).
- [ ] **Know the platform's float.** Payouts draw on *available* Stripe balance, and card money takes days to settle (H3).
  Until the first charges settle, early payouts show `AWAITING_FUNDS`. That is expected; tell the first riders.
- [ ] Alert on `GET /api/admin/finance/attention` reporting `needsAction: true` (open chargebacks, open incidents, payments or payouts
  in `REQUIRES_ATTENTION`), and on the `ERROR`-level log lines `Finance incident …` and `Dispute … cannot match`. A chargeback has a
  response deadline (`evidenceDueBy`): somebody must respond (admin panel > Disputes > Answer, or the Stripe Dashboard) before it, or the dispute is lost by default.

---

## 7. Known gaps (what this checklist will *not* prove)

- **Funds can be late, and payouts wait for them.** A payout waits (`AWAITING_FUNDS`) until Stripe shows enough *settled*
  platform balance, looking every 15 minutes and on `balance.available`, oldest first, for up to 10 days
  (`ridehail.payments.funds-poll-seconds`, `funds-max-wait-days`). It does not reserve funds for the oldest waiting payout: a
  smaller, newer payout can go ahead of a large one if the balance only covers the small one. An alternative design that avoids
  waiting altogether is to tie each transfer to a specific charge (Stripe's `source_transaction`), at the cost of paying out per trip.
- **Provider errors retry for about an hour**, with a doubling delay from 30 seconds capped at 30 minutes, for at most 8 attempts
  (`max-attempts`, `retry-backoff-seconds`, `retry-backoff-max-seconds`). A longer outage still lands in `REQUIRES_ATTENTION`.
- **Riders in another country** from the platform need Stripe's "recipient" service agreement on their accounts; the adapter
  doesn't set it, so riders must be in the platform's country.
- **Refunds don't claw back from the rider's Stripe account** (no transfer reversal), only in our ledger.
- **3-D Secure for saved cards.** Off-session charges on cards that demand authentication are declined; there's no flow yet to
  bring the customer back in to authenticate. Cards saved through the setup flow (which authenticates once, with off-session
  usage) should mostly avoid this per Stripe's guidance, but some issuers still ask; H5 is the only drill that touches it and
  real-card behaviour has not been observed. The customer is then blocked until they save another card. Closing the
  gap needs the app to confirm the payment on-device, so it belongs with the customer client.
- **Stripe forgets idempotency keys after about 24 hours.** The automatic retries (about an hour) are well inside that. An admin
  **Retry** deliberately uses a fresh key, so retry only after checking the first attempt did not charge the customer (the
  payment's reference in the Dashboard).
- **Disputes are recorded, frozen and settled in the ledger, and can be answered with text evidence from the admin panel.** File
  evidence is added in the Stripe Dashboard before the deadline (the admin API gives the link). Submitting evidence has not been
  tried against a real Stripe account yet: make a test dispute (card `4000000000000259`) and answer it from the panel. Reversed transfers and failed refunds are
  raised as incidents for a person; we do not repair the books automatically. See [DISPUTES.md](DISPUTES.md) for the details and
  what is deliberately left manual.
- **Not covered by any automated test:** the real Stripe network. Everything above exists to cover that.
