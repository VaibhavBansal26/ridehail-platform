-- Rider verification: who may drive. Documents are stored encrypted (AES-256-GCM) in rider_document_blobs; the application holds
-- the key, so a database dump or backup alone does not expose identity documents.

-- existing riders stay as they are; a rider who registers while verification is required starts as not verified
ALTER TABLE riders ADD COLUMN verified boolean NOT NULL DEFAULT true;

CREATE TABLE rider_verifications (
    rider_id      uuid          PRIMARY KEY,
    status        varchar(32)   NOT NULL,
    submitted_at  timestamptz,
    reviewed_at   timestamptz,
    reviewed_by   uuid,
    note          varchar(500),
    updated_at    timestamptz   NOT NULL
);
CREATE INDEX idx_rider_verifications_status ON rider_verifications (status, submitted_at);

CREATE TABLE rider_documents (
    id             uuid          PRIMARY KEY,
    rider_id       uuid          NOT NULL,
    type           varchar(32)   NOT NULL,
    content_type   varchar(64)   NOT NULL,
    size_bytes     integer       NOT NULL,
    expires_on     date,
    expiry_warned  boolean       NOT NULL DEFAULT false,
    uploaded_at    timestamptz   NOT NULL,
    CONSTRAINT rider_documents_rider_type_key UNIQUE (rider_id, type)
);
CREATE INDEX idx_rider_documents_expiry ON rider_documents (expires_on) WHERE expires_on IS NOT NULL;

CREATE TABLE rider_document_blobs (
    document_id  uuid   PRIMARY KEY,
    data         bytea  NOT NULL
);
