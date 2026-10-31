-- Phones that may be sent notifications (the lock-screen kind) for a customer or a rider. The token is the push provider's address
-- for the device; it belongs to whoever registered it last.
CREATE TABLE device_tokens (
    token         varchar(255)  PRIMARY KEY,
    profile_id    uuid          NOT NULL,
    role          varchar(16)   NOT NULL,
    platform      varchar(16)   NOT NULL,
    created_at    timestamptz   NOT NULL,
    last_seen_at  timestamptz   NOT NULL
);
CREATE INDEX idx_device_tokens_profile ON device_tokens (profile_id, last_seen_at DESC);
