-- Who did what in the admin panel. Append-only, like the ledger: history that can be edited is not evidence.
CREATE TABLE admin_actions (
    id                uuid          PRIMARY KEY,
    actor_account_id  uuid          NOT NULL,
    actor_phone       varchar(255)  NOT NULL,   -- the login name at the time, kept even if the account is later disabled
    action            varchar(64)   NOT NULL,
    target_type       varchar(64),
    target_id         varchar(64),
    detail            varchar(500),
    created_at        timestamptz   NOT NULL
);
CREATE INDEX idx_admin_actions_created ON admin_actions (created_at DESC);

CREATE FUNCTION admin_actions_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'admin_actions is append-only: % is not allowed', TG_OP;
END;
$$;
CREATE TRIGGER admin_actions_append_only BEFORE UPDATE OR DELETE ON admin_actions
    FOR EACH ROW EXECUTE FUNCTION admin_actions_append_only();
