-- The ledger is the system of record for money, so the database itself refuses to let it be altered or unbalanced,
-- whatever the application (or a person with a SQL console) tries.

-- 1. Append-only: ledger rows can never be updated or deleted. Mistakes are fixed by posting a reversal.
CREATE FUNCTION ledger_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'ledger is append-only: % on % is not allowed', TG_OP, TG_TABLE_NAME
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$;

CREATE TRIGGER ledger_entries_append_only BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION ledger_append_only();
CREATE TRIGGER ledger_transactions_append_only BEFORE UPDATE OR DELETE ON ledger_transactions
    FOR EACH ROW EXECUTE FUNCTION ledger_append_only();
CREATE TRIGGER ledger_entries_no_truncate BEFORE TRUNCATE ON ledger_entries
    FOR EACH STATEMENT EXECUTE FUNCTION ledger_append_only();
CREATE TRIGGER ledger_transactions_no_truncate BEFORE TRUNCATE ON ledger_transactions
    FOR EACH STATEMENT EXECUTE FUNCTION ledger_append_only();

-- 2. Double entry: when a transaction commits, its debits must equal its credits. Checked at COMMIT (deferred) so
--    the application can insert the lines one by one.
CREATE FUNCTION ledger_require_balanced() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    debits  numeric;
    credits numeric;
BEGIN
    SELECT COALESCE(SUM(amount) FILTER (WHERE direction = 'DEBIT'), 0),
           COALESCE(SUM(amount) FILTER (WHERE direction = 'CREDIT'), 0)
      INTO debits, credits
      FROM ledger_entries
     WHERE transaction_id = NEW.transaction_id;

    IF debits <> credits THEN
        RAISE EXCEPTION 'unbalanced ledger transaction %: debits % <> credits %', NEW.transaction_id, debits, credits
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER ledger_entries_balanced AFTER INSERT ON ledger_entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ledger_require_balanced();
