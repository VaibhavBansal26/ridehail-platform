#!/usr/bin/env bash
# Proves a backup works: restores it into a scratch database and checks what must survive. Never touches the live database.
#
#   scripts/restore-drill.sh backups/ridehail-20261030T120000Z.dump
#   DOCKER_CONTAINER=ridehail-postgres-1 PGUSER=ridehail scripts/restore-drill.sh backups/latest.dump
#
# Checks (exit code is non-zero if any fails):
#   1. every Flyway migration in the backup succeeded
#   2. the ledger balances: debits equal credits overall AND inside every single transaction
#   3. the tamper-proofing is still there: the append-only triggers on the ledger and the admin audit log
#   4. prints row counts, so a backup that restored "fine" but empty is obvious
# Run it on a schedule (monthly at least) and after any change to the schema.
set -euo pipefail

dump="${1:?usage: restore-drill.sh <dump file>}"
[ -f "$dump" ] || { echo "no such file: $dump" >&2; exit 2; }
SCRATCH_DB="${SCRATCH_DB:-ridehail_restore_drill}"
ADMIN_DB="${ADMIN_DB:-postgres}"    # a database to connect to while creating and dropping the scratch one

pg() { if [ -n "${DOCKER_CONTAINER:-}" ]; then docker exec -i -e PGUSER -e PGPASSWORD -e PGHOST -e PGPORT "$DOCKER_CONTAINER" "$@"; else "$@"; fi; }
sql() { pg psql --no-psqlrc -X -q -t -A -v ON_ERROR_STOP=1 "$@"; }   # unaligned, tuples only: easy to compare

cleanup() { sql -d "$ADMIN_DB" -c "DROP DATABASE IF EXISTS \"$SCRATCH_DB\"" >/dev/null 2>&1 || true; }
trap cleanup EXIT

sql -d "$ADMIN_DB" -c "DROP DATABASE IF EXISTS \"$SCRATCH_DB\"" -c "CREATE DATABASE \"$SCRATCH_DB\"" >/dev/null
echo "restoring $dump into scratch database $SCRATCH_DB ..."
pg pg_restore --no-owner --no-privileges --exit-on-error --dbname="$SCRATCH_DB" < "$dump"

fail=0
check() {   # check "<what it proves>" "<sql returning the number of problems>"
  local what="$1" n
  n="$(sql -d "$SCRATCH_DB" -c "$2")"
  if [ "$n" = "0" ]; then echo "  ok    $what"; else echo "  FAIL  $what (found $n)"; fail=1; fi
}

echo "checks:"
check "all Flyway migrations succeeded (and there are some)" \
  "SELECT CASE WHEN count(*) FILTER (WHERE NOT success) = 0 AND count(*) > 0 THEN 0 ELSE 1 END FROM flyway_schema_history"
check "ledger: total debits equal total credits" \
  "SELECT CASE WHEN coalesce(sum(CASE direction WHEN 'DEBIT' THEN amount ELSE -amount END), 0) = 0 THEN 0 ELSE 1 END FROM ledger_entries"
check "ledger: every transaction balances on its own" \
  "SELECT count(*) FROM (SELECT transaction_id FROM ledger_entries GROUP BY transaction_id HAVING sum(CASE direction WHEN 'DEBIT' THEN amount ELSE -amount END) <> 0) t"
check "ledger: no entry points at a missing transaction or account" \
  "SELECT (SELECT count(*) FROM ledger_entries e LEFT JOIN ledger_transactions t ON t.id = e.transaction_id WHERE t.id IS NULL) + (SELECT count(*) FROM ledger_entries e LEFT JOIN ledger_accounts a ON a.id = e.account_id WHERE a.id IS NULL)"
check "append-only triggers are present (ledger and admin audit log)" \
  "SELECT CASE WHEN count(*) = 5 THEN 0 ELSE 1 END FROM pg_trigger WHERE tgname IN ('ledger_entries_append_only','ledger_transactions_append_only','ledger_entries_no_truncate','ledger_transactions_no_truncate','admin_actions_append_only')"

echo "row counts (a backup that restored empty would show here):"
for t in user_accounts riders customers trips payments payouts ledger_transactions ledger_entries admin_actions; do
  n="$(sql -d "$SCRATCH_DB" -c "SELECT count(*) FROM $t" 2>/dev/null || echo "(no such table)")"
  printf '  %-22s %s\n' "$t" "$n"
done

if [ "$fail" = 0 ]; then echo "RESTORE DRILL PASSED"; else echo "RESTORE DRILL FAILED" >&2; exit 1; fi
