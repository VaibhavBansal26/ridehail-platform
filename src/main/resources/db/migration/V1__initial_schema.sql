-- Initial schema. Column names/types mirror the JPA entities exactly (Hibernate validates them at startup in
-- prod), plus what Hibernate cannot express well: NOT NULLs, money checks, foreign keys on the ledger, and the
-- indexes the hot queries use.
--
-- NOTE: status/kind/role columns carry CHECK constraints listing the enum values. Adding a new enum value in Java
-- therefore needs a migration that updates the matching CHECK.

CREATE TABLE customers (
    id                   uuid          PRIMARY KEY,
    version              bigint,
    name                 varchar(255)  NOT NULL,
    phone                varchar(255)  NOT NULL,
    payment_method_token varchar(255),
    provider_customer_id varchar(255)
);
-- one provider customer maps to exactly one of ours (webhooks look customers up by it)
CREATE UNIQUE INDEX uq_customers_provider_customer ON customers (provider_customer_id) WHERE provider_customer_id IS NOT NULL;

CREATE TABLE riders (
    id                  uuid              PRIMARY KEY,
    version             bigint,
    name                varchar(255)      NOT NULL,
    phone               varchar(255)      NOT NULL,
    vehicle_type        varchar(255)      NOT NULL,
    status              varchar(255)      NOT NULL,
    lat                 double precision,
    lon                 double precision,
    rating              double precision  NOT NULL,
    location_updated_at timestamptz,
    payout_account_token varchar(255),
    provider_account_id varchar(255),
    CONSTRAINT riders_vehicle_type_check CHECK (vehicle_type IN ('BIKE', 'AUTO', 'SEDAN', 'SUV')),
    CONSTRAINT riders_status_check CHECK (status IN ('OFFLINE', 'AVAILABLE', 'ON_TRIP'))
);
CREATE INDEX idx_rider_avail ON riders (status, vehicle_type, lat, lon);
CREATE UNIQUE INDEX uq_riders_provider_account ON riders (provider_account_id) WHERE provider_account_id IS NOT NULL;

CREATE TABLE user_accounts (
    id            uuid          PRIMARY KEY,
    version       bigint,
    phone         varchar(255)  NOT NULL,
    password_hash varchar(255)  NOT NULL,
    role          varchar(255)  NOT NULL,
    profile_id    uuid          NOT NULL,
    enabled       boolean       NOT NULL,
    created_at    timestamptz   NOT NULL,
    CONSTRAINT user_accounts_phone_key UNIQUE (phone),
    CONSTRAINT user_accounts_role_check CHECK (role IN ('CUSTOMER', 'RIDER', 'ADMIN'))
);

CREATE TABLE fare_quotes (
    id               uuid              PRIMARY KEY,
    version          bigint,
    customer_id      uuid              NOT NULL,
    vehicle_type     varchar(255)      NOT NULL,
    pickup_lat       double precision  NOT NULL,
    pickup_lon       double precision  NOT NULL,
    drop_lat         double precision  NOT NULL,
    drop_lon         double precision  NOT NULL,
    distance_km      double precision  NOT NULL,
    duration_min     double precision  NOT NULL,
    subtotal         numeric(12,2)     NOT NULL,
    surge_multiplier numeric(4,1)      NOT NULL,
    total            numeric(12,2)     NOT NULL,
    currency         varchar(255)      NOT NULL,
    demand           bigint            NOT NULL,
    supply           bigint            NOT NULL,
    created_at       timestamptz       NOT NULL,
    expires_at       timestamptz       NOT NULL,
    used             boolean           NOT NULL,
    CONSTRAINT fare_quotes_vehicle_type_check CHECK (vehicle_type IN ('BIKE', 'AUTO', 'SEDAN', 'SUV')),
    CONSTRAINT fare_quotes_total_check CHECK (total >= 0)
);
CREATE INDEX idx_quote_demand ON fare_quotes (vehicle_type, created_at, pickup_lat, pickup_lon);

CREATE TABLE trips (
    id               uuid              PRIMARY KEY,
    version          bigint,
    customer_id      uuid              NOT NULL,
    rider_id         uuid,
    quote_id         uuid              NOT NULL,
    vehicle_type     varchar(255)      NOT NULL,
    status           varchar(255)      NOT NULL,
    pickup_lat       double precision  NOT NULL,
    pickup_lon       double precision  NOT NULL,
    drop_lat         double precision  NOT NULL,
    drop_lon         double precision  NOT NULL,
    fare             numeric(12,2)     NOT NULL,
    surge_multiplier numeric(4,1)      NOT NULL,
    currency         varchar(255)      NOT NULL,
    offers_sent      integer           NOT NULL,
    requested_at     timestamptz       NOT NULL,
    accepted_at      timestamptz,
    started_at       timestamptz,
    completed_at     timestamptz,
    CONSTRAINT trips_quote_id_key UNIQUE (quote_id),   -- booking is idempotent per quote
    CONSTRAINT trips_vehicle_type_check CHECK (vehicle_type IN ('BIKE', 'AUTO', 'SEDAN', 'SUV')),
    CONSTRAINT trips_status_check CHECK (status IN
        ('REQUESTED', 'ACCEPTED', 'ARRIVED', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED', 'NO_RIDERS_AVAILABLE')),
    CONSTRAINT trips_fare_check CHECK (fare >= 0)
);
CREATE INDEX idx_trip_customer_status ON trips (customer_id, status);   -- "does this customer have an active trip?"
CREATE INDEX idx_trip_rider ON trips (rider_id) WHERE rider_id IS NOT NULL;
CREATE INDEX idx_trip_status_requested ON trips (status, requested_at DESC);  -- admin listing

CREATE TABLE trip_offers (
    id                uuid              PRIMARY KEY,
    version           bigint,
    trip_id           uuid              NOT NULL,
    rider_id          uuid              NOT NULL,
    status            varchar(255)      NOT NULL,
    rider_distance_km double precision  NOT NULL,
    created_at        timestamptz       NOT NULL,
    expires_at        timestamptz       NOT NULL,
    responded_at      timestamptz,
    CONSTRAINT trip_offers_status_check CHECK (status IN ('PENDING', 'ACCEPTED', 'DECLINED', 'EXPIRED', 'CANCELLED'))
);
CREATE INDEX idx_offer_rider ON trip_offers (rider_id, status);
CREATE INDEX idx_offer_trip ON trip_offers (trip_id, status);
CREATE INDEX idx_offer_expiry ON trip_offers (status, expires_at);

-- ---------------------------------------------------------------- ledger

CREATE TABLE ledger_accounts (
    id         uuid          PRIMARY KEY,
    type       varchar(255)  NOT NULL,
    owner_id   uuid          NOT NULL,
    currency   varchar(255)  NOT NULL,
    created_at timestamptz   NOT NULL,
    CONSTRAINT ledger_accounts_type_owner_id_key UNIQUE (type, owner_id),
    CONSTRAINT ledger_accounts_type_check CHECK (type IN ('CASH', 'RIDER_PAYABLE', 'PAYOUTS_IN_TRANSIT', 'COMMISSION_REVENUE'))
);

CREATE TABLE ledger_transactions (
    id          uuid          PRIMARY KEY,
    ref_key     varchar(255)  NOT NULL,   -- idempotency: the same business event can only be posted once
    kind        varchar(255)  NOT NULL,
    description varchar(255),
    created_at  timestamptz   NOT NULL,
    CONSTRAINT ledger_transactions_ref_key_key UNIQUE (ref_key),
    CONSTRAINT ledger_transactions_kind_check CHECK (kind IN
        ('PAYMENT_CAPTURE', 'PAYMENT_REFUND', 'PAYOUT_RESERVE', 'PAYOUT_SETTLE', 'PAYOUT_REVERSE'))
);

CREATE TABLE ledger_entries (
    id             uuid           PRIMARY KEY,
    transaction_id uuid           NOT NULL REFERENCES ledger_transactions (id),
    account_id     uuid           NOT NULL REFERENCES ledger_accounts (id),
    direction      varchar(255)   NOT NULL,
    amount         numeric(12,2)  NOT NULL,
    created_at     timestamptz    NOT NULL,
    CONSTRAINT ledger_entries_direction_check CHECK (direction IN ('DEBIT', 'CREDIT')),
    CONSTRAINT ledger_entries_amount_check CHECK (amount > 0)
);
CREATE INDEX idx_entry_account ON ledger_entries (account_id, direction);
CREATE INDEX idx_entry_tx ON ledger_entries (transaction_id);

-- ---------------------------------------------------------------- payments

CREATE TABLE payments (
    id                uuid           PRIMARY KEY,
    version           bigint,
    trip_id           uuid           NOT NULL,
    customer_id       uuid           NOT NULL,
    rider_id          uuid           NOT NULL,
    amount            numeric(12,2)  NOT NULL,
    commission_amount numeric(12,2)  NOT NULL,
    rider_amount      numeric(12,2)  NOT NULL,
    currency          varchar(255)   NOT NULL,
    status            varchar(255)   NOT NULL,
    attempts          integer        NOT NULL,
    next_attempt_at   timestamptz    NOT NULL,
    charge_key        uuid           NOT NULL,
    gateway_reference varchar(255),
    failure_reason    varchar(255),
    created_at        timestamptz    NOT NULL,
    captured_at       timestamptz,
    refunded_at       timestamptz,
    CONSTRAINT payments_trip_id_key UNIQUE (trip_id),    -- exactly one payment per trip
    CONSTRAINT payments_status_check CHECK (status IN ('PENDING', 'CAPTURED', 'REQUIRES_ATTENTION', 'REFUNDED')),
    CONSTRAINT payments_amounts_check CHECK (amount >= 0 AND commission_amount >= 0 AND rider_amount >= 0
                                             AND amount = commission_amount + rider_amount)
);
CREATE INDEX idx_payment_due ON payments (status, next_attempt_at);
CREATE INDEX idx_payment_customer_status ON payments (customer_id, status);
CREATE UNIQUE INDEX uq_payments_charge_key ON payments (charge_key);   -- webhooks find the payment by it
CREATE INDEX idx_payment_status_created ON payments (status, created_at DESC);

CREATE TABLE payouts (
    id                uuid           PRIMARY KEY,   -- doubles as the provider idempotency key
    version           bigint,
    rider_id          uuid           NOT NULL,
    amount            numeric(12,2)  NOT NULL,
    currency          varchar(255)   NOT NULL,
    status            varchar(255)   NOT NULL,
    attempts          integer        NOT NULL,
    next_attempt_at   timestamptz    NOT NULL,
    gateway_reference varchar(255),
    failure_reason    varchar(255),
    created_at        timestamptz    NOT NULL,
    completed_at      timestamptz,
    CONSTRAINT payouts_status_check CHECK (status IN ('PENDING', 'PAID', 'FAILED', 'REQUIRES_ATTENTION')),
    CONSTRAINT payouts_amount_check CHECK (amount > 0)
);
CREATE INDEX idx_payout_due ON payouts (status, next_attempt_at);
CREATE INDEX idx_payout_rider ON payouts (rider_id, created_at);
-- at most one unfinished payout per rider, enforced by the database and not only by application code
CREATE UNIQUE INDEX uq_payouts_one_open_per_rider ON payouts (rider_id) WHERE status IN ('PENDING', 'REQUIRES_ATTENTION');

CREATE TABLE processed_webhook_events (
    id           varchar(255)  PRIMARY KEY,
    type         varchar(255),
    processed_at timestamptz   NOT NULL
);
