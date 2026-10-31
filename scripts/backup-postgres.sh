#!/usr/bin/env bash
# Takes a verified, timestamped backup of the platform's Postgres database.
#
#   scripts/backup-postgres.sh                        # uses libpq settings: PGHOST PGPORT PGUSER PGPASSWORD PGDATABASE
#   DOCKER_CONTAINER=ridehail-postgres-1 PGUSER=ridehail PGDATABASE=ridehail scripts/backup-postgres.sh   # runs pg_dump inside a container
#
# Settings: BACKUP_DIR (default ./backups), RETENTION_DAYS (default 14; older backups are deleted), AFTER_BACKUP (an optional
# command run with the new file's path as $1, e.g. an upload to object storage in another region).
# A backup that has not been restored is a hope, not a backup: run scripts/restore-drill.sh on it regularly.
set -euo pipefail

BACKUP_DIR="${BACKUP_DIR:-./backups}"
RETENTION_DAYS="${RETENTION_DAYS:-14}"
: "${PGDATABASE:?set PGDATABASE (and PGUSER, PGPASSWORD, PGHOST as needed)}"

pg() { if [ -n "${DOCKER_CONTAINER:-}" ]; then docker exec -i -e PGUSER -e PGPASSWORD -e PGHOST -e PGPORT "$DOCKER_CONTAINER" "$@"; else "$@"; fi; }

mkdir -p "$BACKUP_DIR"
stamp="$(date -u +%Y%m%dT%H%M%SZ)"
final="$BACKUP_DIR/ridehail-$stamp.dump"
tmp="$final.partial"
trap 'rm -f "$tmp"' EXIT

# custom format: compressed, and restorable table by table with pg_restore
pg pg_dump --format=custom --no-owner --no-privileges "$PGDATABASE" > "$tmp"

# a dump that cannot be read back is worse than none: read its table of contents before trusting it
if ! pg pg_restore --list < "$tmp" > /dev/null; then
  echo "backup FAILED: the dump is not readable" >&2
  exit 1
fi
size=$(wc -c < "$tmp" | tr -d ' ')
[ "$size" -gt 1024 ] || { echo "backup FAILED: the dump is suspiciously small ($size bytes)" >&2; exit 1; }

mv "$tmp" "$final"
trap - EXIT
(cd "$BACKUP_DIR" && { shasum -a 256 "$(basename "$final")" 2>/dev/null || sha256sum "$(basename "$final")"; } > "$(basename "$final").sha256")
echo "backup ok: $final ($size bytes)"

# retention
find "$BACKUP_DIR" -name 'ridehail-*.dump*' -mtime +"$RETENTION_DAYS" -print -delete | sed 's/^/removed old backup: /' || true

if [ -n "${AFTER_BACKUP:-}" ]; then
  $AFTER_BACKUP "$final"
fi
