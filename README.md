# ridehail-platform

A ride-sharing platform. This repository holds the Spring Boot 3 (Java 21) backend: the API behind the rider (driver) app, the customer app and the admin panel.

- **Dynamic fares:** upfront quotes with demand/supply surge pricing, locked at booking.
- **Dispatch:** nearest-rider offers with timeouts; the rider accepts or declines.
- **Auth:** JWT with `CUSTOMER`, `RIDER`, `ADMIN` roles.
- **Realtime:** STOMP WebSocket push for offers and trip updates (`/ws`).
- **Payments:** charge on trip completion, double-entry ledger, commission, rider payouts, refunds.
- **Stripe:** charges, refunds, Connect payouts and signed webhooks behind a `PaymentGateway` port (simulated gateway for dev/tests).

## Run

```bash
mvn spring-boot:run      # in-memory H2, simulated payment gateway, dev-only secrets
mvn test                 # 76 tests on H2
docker compose up --build   # API + real Postgres with Flyway migrations, on http://localhost:8080
```

Needs JDK 21 and Maven (Docker only for the compose stack).

**Database.** Production schema is owned by Flyway (`src/main/resources/db/migration`). Beyond the tables it enforces the
money rules in the database itself: the ledger is append-only and every ledger transaction must balance at commit, there is
at most one open payout per rider, and amounts and statuses are constrained. Dev and tests on H2 generate tables from the
entities instead.

**Running the tests on Postgres.** The same integration tests run against a real database (migrations, schema validation,
triggers, row locking) when you point them at one:

```bash
docker run -d --name pg -e POSTGRES_DB=ridehail -e POSTGRES_USER=ridehail -e POSTGRES_PASSWORD=ridehail -p 5432:5432 postgres:16-alpine
RIDEHAIL_TEST_PG_URL=jdbc:postgresql://localhost:5432/ridehail mvn test   # 117 tests, none skipped
```

CI (`.github/workflows/ci.yml`) does exactly this against a Postgres service container, and builds the Docker image.

## Stripe

See [docs/STRIPE_TEST_MODE_SETUP.md](docs/STRIPE_TEST_MODE_SETUP.md) for the test-mode setup and an end-to-end verification script.

## Before production

A payout-timing policy for Stripe's pending balance and everything under "Known gaps" in the Stripe doc.
The `prod` profile requires `JWT_SECRET`, `ADMIN_PASSWORD`, `ALLOWED_ORIGINS`, DB settings and the Stripe variables,
runs the Flyway migrations at startup, and refuses to start with the simulated gateway.
