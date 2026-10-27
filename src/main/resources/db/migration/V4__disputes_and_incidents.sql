-- Payment disputes (chargebacks) and other provider problems that need a person.

-- new ledger accounts and posting kinds
ALTER TABLE ledger_accounts DROP CONSTRAINT ledger_accounts_type_check;
ALTER TABLE ledger_accounts ADD CONSTRAINT ledger_accounts_type_check CHECK (type IN
    ('CASH', 'RIDER_PAYABLE', 'PAYOUTS_IN_TRANSIT', 'COMMISSION_REVENUE',
     'RIDER_FUNDS_HELD', 'DISPUTES_IN_PROGRESS', 'DISPUTE_LOSSES', 'DISPUTE_FEES'));

ALTER TABLE ledger_transactions DROP CONSTRAINT ledger_transactions_kind_check;
ALTER TABLE ledger_transactions ADD CONSTRAINT ledger_transactions_kind_check CHECK (kind IN
    ('PAYMENT_CAPTURE', 'PAYMENT_REFUND', 'PAYOUT_RESERVE', 'PAYOUT_SETTLE', 'PAYOUT_REVERSE',
     'DISPUTE_OPEN', 'DISPUTE_FEE', 'DISPUTE_WON', 'DISPUTE_LOST'));

-- a disputed charge is no longer plain "captured"
ALTER TABLE payments DROP CONSTRAINT payments_status_check;
ALTER TABLE payments ADD CONSTRAINT payments_status_check CHECK (status IN
    ('PENDING', 'CAPTURED', 'DISPUTED', 'CHARGED_BACK', 'REQUIRES_ATTENTION', 'REFUNDED'));

-- disputes and refunds point back at our payment/payout by the provider's reference
CREATE UNIQUE INDEX uq_payments_gateway_reference ON payments (gateway_reference) WHERE gateway_reference IS NOT NULL;
CREATE INDEX idx_payouts_gateway_reference ON payouts (gateway_reference) WHERE gateway_reference IS NOT NULL;

CREATE TABLE disputes (
    id                uuid           PRIMARY KEY,
    version           bigint,
    provider_id       varchar(255)   NOT NULL,   -- the provider's dispute id; one row per dispute
    payment_id        uuid,                      -- our payment, when we could match it
    trip_id           uuid,
    customer_id       uuid,
    rider_id          uuid,
    matched           boolean        NOT NULL,   -- false: recorded for a human, but no ledger entries were made
    amount            numeric(12,2)  NOT NULL,
    currency          varchar(255)   NOT NULL,
    reason            varchar(255),
    provider_status   varchar(255),
    status            varchar(255)   NOT NULL,
    evidence_due_by   timestamptz,
    frozen            boolean        NOT NULL,   -- the ledger has moved the contested money and held the rider's share
    rider_held_amount numeric(12,2)  NOT NULL,
    fee_booked        numeric(12,2)  NOT NULL,
    provider_url      varchar(512),
    ledger_note       varchar(500),
    created_at        timestamptz    NOT NULL,
    updated_at        timestamptz    NOT NULL,
    resolved_at       timestamptz,
    CONSTRAINT disputes_provider_id_key UNIQUE (provider_id),
    CONSTRAINT disputes_status_check CHECK (status IN ('INQUIRY', 'OPEN', 'WON', 'LOST', 'CLOSED')),
    CONSTRAINT disputes_amounts_check CHECK (amount > 0 AND rider_held_amount >= 0 AND rider_held_amount <= amount AND fee_booked >= 0)
);
CREATE INDEX idx_dispute_status ON disputes (status, created_at DESC);
CREATE INDEX idx_dispute_customer ON disputes (customer_id, status);
CREATE INDEX idx_dispute_payment ON disputes (payment_id);

CREATE TABLE finance_incidents (
    id              uuid           PRIMARY KEY,
    version         bigint,
    type            varchar(255)   NOT NULL,
    provider_ref    varchar(255)   NOT NULL,     -- the provider's id for the thing that went wrong
    rider_id        uuid,
    payment_id      uuid,
    payout_id       uuid,
    amount          numeric(12,2),
    currency        varchar(255),
    detail          varchar(1000),
    status          varchar(255)   NOT NULL,
    created_at      timestamptz    NOT NULL,
    resolved_at     timestamptz,
    resolution_note varchar(500),
    CONSTRAINT finance_incidents_ref_key UNIQUE (type, provider_ref),   -- a redelivered event cannot raise it twice
    CONSTRAINT finance_incidents_type_check CHECK (type IN
        ('CONNECT_PAYOUT_FAILED', 'PLATFORM_PAYOUT_FAILED', 'TRANSFER_REVERSED', 'REFUND_FAILED')),
    CONSTRAINT finance_incidents_status_check CHECK (status IN ('OPEN', 'RESOLVED'))
);
CREATE INDEX idx_incident_status ON finance_incidents (status, created_at DESC);
CREATE INDEX idx_incident_rider ON finance_incidents (rider_id, type, status);
