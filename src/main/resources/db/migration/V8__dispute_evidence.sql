-- Our response to a payment dispute: the admin's draft explanation and, once submitted, what was sent to the provider.
CREATE TABLE dispute_evidence (
    dispute_id          uuid           PRIMARY KEY,
    version             bigint,
    explanation         varchar(5000),
    saved_at            timestamptz,
    saved_by            uuid,
    submitted           boolean        NOT NULL,
    submitted_at        timestamptz,
    submitted_by        uuid,
    customer_name       varchar(255),
    product_description varchar(500),
    service_date        varchar(32),
    access_activity_log varchar(5000),
    last_error          varchar(500)
);
