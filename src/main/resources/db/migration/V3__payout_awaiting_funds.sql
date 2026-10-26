-- Payouts can now wait (patiently) for the platform's funds at the payment provider to settle.
ALTER TABLE payouts ADD COLUMN waiting_since timestamptz;

ALTER TABLE payouts DROP CONSTRAINT payouts_status_check;
ALTER TABLE payouts ADD CONSTRAINT payouts_status_check
    CHECK (status IN ('PENDING', 'AWAITING_FUNDS', 'PAID', 'FAILED', 'REQUIRES_ATTENTION'));

-- a waiting payout is still an open one: a rider cannot start a second
DROP INDEX uq_payouts_one_open_per_rider;
CREATE UNIQUE INDEX uq_payouts_one_open_per_rider ON payouts (rider_id)
    WHERE status IN ('PENDING', 'AWAITING_FUNDS', 'REQUIRES_ATTENTION');
