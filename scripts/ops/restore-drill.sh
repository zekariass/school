#!/usr/bin/env bash
# Restore drill: proves that the latest backup can actually be restored.
#
#   download the newest backup -> decrypt (streaming) -> pg_restore into a SCRATCH database
#   -> integrity checks -> drop the scratch database
#
# Never restores over the real database: it creates its own scratch database (name below) and drops it again,
# also when a check fails. Plaintext is never written to disk (the decrypted stream goes straight into
# pg_restore); the encrypted download lives in a private temp directory that is removed on exit.
#
# Run it on a machine that holds the PRIVATE key (not the backup host) and has access to a PostgreSQL server
# where the role may CREATE DATABASE (a throwaway server is best; production only if nothing else fits).
#
# Configuration (environment; secrets are never printed):
#   Server for the scratch database (libpq): PGHOST PGPORT PGUSER PGPASSWORD [PGSSLMODE]
#   Backup bucket:   BACKUP_S3_ENDPOINT BACKUP_S3_BUCKET BACKUP_S3_ACCESS_KEY BACKUP_S3_SECRET_KEY [BACKUP_S3_REGION=auto]
#   What to restore: BACKUP_ENV=staging  BACKUP_PREFIX=backups  BACKUP_DATABASE=<source database name, e.g. school>
#                    DRILL_KEY=<exact object key> (default: the newest backup of that database)
#   Decryption:      RESTORE_AGE_IDENTITY_FILE=/path/to/identity.txt   or   gpg (private key in the keyring)
#   Checks:          DRILL_MIN_USERS=0 (users must be at least this many)  DRILL_EXPECT_MIGRATION=<version> (optional;
#                    fail when the restored schema is not at this Flyway version, e.g. 6.1)
#   Tooling:         PG_CLIENT_IMAGE=postgres:18 [PG_CLIENT_NETWORK=host]  run pg_restore/psql from Docker
#   Keep:            DRILL_KEEP=1 leaves the scratch database in place (for debugging)
#
# Exit codes: 0 all checks passed, 1 a step or check failed, 2 configuration error.

set -euo pipefail
umask 077

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/s3.sh
. "$here/lib/s3.sh"

die() { echo "error: $1" >&2; exit "${2:-1}"; }
log() { printf '%s  %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*"; }

S3_ENDPOINT="${BACKUP_S3_ENDPOINT:-}"
S3_BUCKET="${BACKUP_S3_BUCKET:-}"
S3_ACCESS_KEY="${BACKUP_S3_ACCESS_KEY:-}"
S3_SECRET_KEY="${BACKUP_S3_SECRET_KEY:-}"
S3_REGION="${BACKUP_S3_REGION:-auto}"
s3_require || die "set the BACKUP_S3_* variables" 2
for name in PGHOST PGUSER PGPASSWORD BACKUP_DATABASE; do
  [ -n "${!name:-}" ] || die "$name is not set" 2
done
export PGHOST PGUSER PGPASSWORD
export PGPORT="${PGPORT:-5432}"
export PGDATABASE=postgres
[ -n "${PGSSLMODE:-}" ] && export PGSSLMODE

prefix="${BACKUP_PREFIX:-backups}/${BACKUP_ENV:-staging}"
scratch="restore_drill_$(date -u +%Y%m%d%H%M%S)"

pg_tool() {
  if [ -n "${PG_CLIENT_IMAGE:-}" ]; then
    docker run --rm -i --network "${PG_CLIENT_NETWORK:-host}" \
      -e PGHOST -e PGPORT -e PGUSER -e PGPASSWORD -e PGDATABASE -e PGSSLMODE "$PG_CLIENT_IMAGE" "$@"
  else
    "$@"
  fi
}
psql_scratch() { PGDATABASE="$scratch" pg_tool psql --no-psqlrc --tuples-only --no-align --set ON_ERROR_STOP=1 "$@"; }

work="$(mktemp -d)"
created=0
cleanup() {
  local status=$?
  rm -rf "$work"
  s3_cleanup
  if [ "$created" = 1 ] && [ -z "${DRILL_KEEP:-}" ]; then
    PGDATABASE=postgres pg_tool psql --no-psqlrc -q -c "drop database if exists \"$scratch\" with (force)" >/dev/null 2>&1 &&
      log "scratch database ${scratch} dropped" || log "warning: could not drop scratch database ${scratch}"
  elif [ "$created" = 1 ]; then
    log "DRILL_KEEP set: scratch database ${scratch} left in place"
  fi
  exit "$status"
}
trap cleanup EXIT

failures=0
check() { # check <description> <ok: 0|1> <detail>
  if [ "$2" = 1 ]; then log "PASS  $1 ($3)"; else log "FAIL  $1 ($3)"; failures=$((failures + 1)); fi
}

started=$SECONDS

# ---- 1. pick the backup ----
key="${DRILL_KEY:-}"
if [ -z "$key" ]; then
  key="$(s3_list "${prefix}/${BACKUP_DATABASE}-" | grep -E "${BACKUP_DATABASE}-[0-9]{8}T[0-9]{6}Z\.dump\.(age|gpg)$" | tail -n 1 || true)"
fi
[ -n "$key" ] || die "no backup found under ${S3_BUCKET}/${prefix}/${BACKUP_DATABASE}-"
case "$key" in
  *.age) decrypt=(age --decrypt -i "${RESTORE_AGE_IDENTITY_FILE:-}")
         [ -n "${RESTORE_AGE_IDENTITY_FILE:-}" ] && [ -r "$RESTORE_AGE_IDENTITY_FILE" ] || die "set RESTORE_AGE_IDENTITY_FILE to the age identity (private key) file" 2 ;;
  *.gpg) decrypt=(gpg --batch --quiet --decrypt) ;;
  *) die "unknown backup extension: $key" 2 ;;
esac
log "restoring ${key} into scratch database ${scratch} on ${PGHOST}"

# ---- 2. download (ciphertext only) ----
s3_get "$key" "$work/backup.enc" || die "download failed"
log "downloaded $(wc -c <"$work/backup.enc" | tr -d ' ') bytes of ciphertext"

# ---- 3. restore into the scratch database, decrypting on the fly ----
pg_tool psql --no-psqlrc -q -c "create database \"$scratch\"" >/dev/null
created=1
"${decrypt[@]}" <"$work/backup.enc" | PGDATABASE="$scratch" pg_tool pg_restore --no-owner --no-privileges --exit-on-error \
  --dbname="$scratch" \
  || die "pg_restore failed"
restore_seconds=$((SECONDS - started))
log "restored in ${restore_seconds} s"

# ---- 4. integrity checks ----
failed_migrations="$(psql_scratch -c "select count(*) from flyway_schema_history where not success")"
check "no failed migrations in flyway_schema_history" "$([ "$failed_migrations" = 0 ] && echo 1 || echo 0)" "failed=${failed_migrations}"

version="$(psql_scratch -c "select version from flyway_schema_history where success and version is not null order by installed_rank desc limit 1")"
check "schema has a migration version" "$([ -n "$version" ] && echo 1 || echo 0)" "version=${version:-none}"
if [ -n "${DRILL_EXPECT_MIGRATION:-}" ]; then
  check "migration version equals expected" "$([ "$version" = "$DRILL_EXPECT_MIGRATION" ] && echo 1 || echo 0)" "expected=${DRILL_EXPECT_MIGRATION} found=${version}"
fi

count() { psql_scratch -c "select count(*) from $1"; }
for table in countries roles permissions; do
  n="$(count "$table")"
  check "seed table ${table} is not empty" "$([ "$n" -ge 1 ] && echo 1 || echo 0)" "rows=${n}"
done
users="$(count users)"
check "users >= ${DRILL_MIN_USERS:-0}" "$([ "$users" -ge "${DRILL_MIN_USERS:-0}" ] && echo 1 || echo 0)" "rows=${users}"
for table in schools role_assignments auth_sessions audit_logs media; do
  n="$(count "$table" 2>/dev/null || echo "n/a")"
  log "INFO  rows in ${table}: ${n}"
done

# a multi-table query: foreign keys and indexes survived the restore
joined="$(psql_scratch -c "select count(*) from role_assignments ra join users u on u.id = ra.user_id join roles r on r.code = ra.role_code")" || joined=""
check "sample join (role_assignments x users x roles) runs" "$([ -n "$joined" ] && echo 1 || echo 0)" "rows=${joined:-error}"
unvalid="$(psql_scratch -c "select count(*) from pg_constraint where not convalidated")"
check "all constraints validated" "$([ "$unvalid" = 0 ] && echo 1 || echo 0)" "unvalidated=${unvalid}"

total=$((SECONDS - started))
if [ "$failures" -eq 0 ]; then
  log "DRILL OK: ${key} restored and verified in ${total} s (restore ${restore_seconds} s), migration version ${version}"
else
  die "DRILL FAILED: ${failures} check(s) failed for ${key}"
fi
