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
mvn test                 # 76 tests
```

Needs JDK 21 and Maven. `docker-compose.yml` starts Postgres for the `prod` profile.

## Stripe

See [docs/STRIPE_TEST_MODE_SETUP.md](docs/STRIPE_TEST_MODE_SETUP.md) for the test-mode setup and an end-to-end verification script.

## Before production

Flyway migrations (the schema is auto-generated today), a payout-timing policy for Stripe's pending balance,
and everything under "Known gaps" in the Stripe doc. The `prod` profile requires `JWT_SECRET`, `ADMIN_PASSWORD`,
`ALLOWED_ORIGINS`, DB settings and the Stripe variables, and refuses to start with the simulated gateway.
