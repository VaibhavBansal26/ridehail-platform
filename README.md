# ridehail-platform

A ride-sharing platform. This repository holds the Spring Boot 3 (Java 21) backend (the API behind the rider (driver) app and the customer app) and the Next.js admin panel in [`admin-web/`](admin-web).

- **Dynamic fares:** upfront quotes with demand/supply surge pricing, locked at booking.
- **Dispatch:** nearest-rider offers with timeouts; the rider accepts or declines.
- **Auth:** JWT with `CUSTOMER`, `RIDER`, `ADMIN` roles.
- **Realtime:** STOMP WebSocket push for offers, trip updates and the rider's live location (`/ws`; see [docs/LIVE_LOCATION.md](docs/LIVE_LOCATION.md)), with rate limiting. Runs on one instance out of the box and across many with RabbitMQ and Redis ([docs/SCALING.md](docs/SCALING.md)).
- **Payments:** charge on trip completion, double-entry ledger, commission, rider payouts (which wait for the provider's settled funds), refunds.
- **Stripe:** charges, refunds, Connect payouts and signed webhooks behind a `PaymentGateway` port (simulated gateway for dev/tests).
- **Admin panel:** live ops map, trips, riders, payments, disputes and incidents with a "needs attention" summary ([docs/ADMIN_PANEL.md](docs/ADMIN_PANEL.md)).
- **Disputes & provider problems:** chargebacks are recorded, the rider's share is frozen in the ledger and settled when decided; failed bank payouts, reversed transfers and failed refunds become admin incidents ([docs/DISPUTES.md](docs/DISPUTES.md)).

## Run

```bash
mvn spring-boot:run      # in-memory H2, simulated payment gateway, dev-only secrets
mvn test                 # 282 tests (H2; plus tests against a real Redis, RabbitMQ and Postgres when Docker is available)
docker compose up --build   # TWO instances + Postgres, RabbitMQ, Redis, an nginx gateway on http://localhost:8080 and the admin panel on http://localhost:3000
DEMO_DATA=true docker compose up --build   # the same, with a demo city loaded so the panel has something to show
```

Needs JDK 21 and Maven. Docker is needed for the compose stack and for the Redis/RabbitMQ tests (they start containers themselves and are skipped when Docker is not available).

**Database.** Production schema is owned by Flyway (`src/main/resources/db/migration`). Beyond the tables it enforces the
money rules in the database itself: the ledger is append-only and every ledger transaction must balance at commit, there is
at most one open payout per rider, and amounts and statuses are constrained. Dev and tests on H2 generate tables from the
entities instead.

**Running the tests on Postgres.** The same integration tests run against a real database (migrations, schema validation,
triggers, row locking) when you point them at one:

```bash
docker run -d --name pg -e POSTGRES_DB=ridehail -e POSTGRES_USER=ridehail -e POSTGRES_PASSWORD=ridehail -p 5432:5432 postgres:16-alpine
RIDEHAIL_TEST_PG_URL=jdbc:postgresql://localhost:5432/ridehail mvn test   # 282 tests, none skipped
```

CI (`.github/workflows/ci.yml`) does exactly this against a Postgres service container, checks the admin panel (lint, types, 87 tests, production build), builds both Docker images, and brings up the
two-instance compose stack to check that realtime and rate limits work across instances (`scripts/verify-realtime.py`).

## Stripe

See [docs/STRIPE_TEST_MODE_SETUP.md](docs/STRIPE_TEST_MODE_SETUP.md) for the test-mode setup and an end-to-end verification script.

## Before production

Everything under "Known gaps" in the Stripe doc.
The `prod` profile requires `JWT_SECRET`, `ADMIN_PASSWORD`, `ALLOWED_ORIGINS`, DB settings and the Stripe variables,
runs the Flyway migrations at startup, and refuses to start with the simulated gateway.
