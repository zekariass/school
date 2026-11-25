#!/usr/bin/env bash
# Encrypted PostgreSQL backup to an S3-compatible bucket (Cloudflare R2, SeaweedFS, AWS), with retention.
#
#   pg_dump (custom format) | age or gpg (public key) -> ciphertext file -> upload -> delete old backups
#
# The dump is encrypted while it streams, so plaintext is NEVER written to disk; the server only holds the
# PUBLIC key, so a stolen backup host or bucket cannot read the backups. Keep the private key (age identity)
# off the server, in the password manager and on the machine that runs the restore drill.
#
# Configuration (environment; secrets are never printed):
#   Database (libpq):   PGHOST PGPORT PGUSER PGPASSWORD PGDATABASE [PGSSLMODE]
#   Bucket:             BACKUP_S3_ENDPOINT BACKUP_S3_BUCKET BACKUP_S3_ACCESS_KEY BACKUP_S3_SECRET_KEY
#                       [BACKUP_S3_REGION=auto]  Use a separate bucket and token from the media bucket.
#   Encryption:         BACKUP_AGE_RECIPIENT=age1...  (comma-separated for several)   or
#                       BACKUP_AGE_RECIPIENTS_FILE=/path/recipients.txt                or
#                       BACKUP_GPG_RECIPIENT=<key id or email>  (public key already imported)
#   Naming/retention:   BACKUP_ENV=staging|prod (label, default staging)  BACKUP_PREFIX=backups
#                       BACKUP_RETENTION_DAYS=14  BACKUP_MIN_KEEP=7 (never delete below this many)
#   Tooling:            PG_CLIENT_IMAGE=postgres:18  run pg_dump from that image through Docker (use when the
#                       host's pg_dump is older than the server); PG_CLIENT_NETWORK=<docker network, default host>
#   Monitoring:         BACKUP_HEARTBEAT_URL=https://...  GET after a successful run (healthchecks.io style)
#
# Key layout: <prefix>/<env>/<database>-<UTC timestamp>.dump.<age|gpg>
# Exit codes: 0 ok, 1 failure, 2 configuration error. Nightly via cron or the scheduled workflow
# (docs/ops/Runbook.md).

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

for name in PGHOST PGUSER PGPASSWORD PGDATABASE; do
  [ -n "${!name:-}" ] || die "$name is not set" 2
done
export PGHOST PGUSER PGPASSWORD PGDATABASE
export PGPORT="${PGPORT:-5432}"
[ -n "${PGSSLMODE:-}" ] && export PGSSLMODE

label="${BACKUP_ENV:-staging}"
prefix="${BACKUP_PREFIX:-backups}/${label}"
retention_days="${BACKUP_RETENTION_DAYS:-14}"
min_keep="${BACKUP_MIN_KEEP:-7}"

# ---- encryption command (reads plaintext on stdin, writes ciphertext on stdout) ----
encrypt=()
ext=""
if [ -n "${BACKUP_AGE_RECIPIENT:-}" ] || [ -n "${BACKUP_AGE_RECIPIENTS_FILE:-}" ]; then
  command -v age >/dev/null 2>&1 || die "age is not installed (https://github.com/FiloSottile/age)" 2
  encrypt=(age --encrypt)
  if [ -n "${BACKUP_AGE_RECIPIENT:-}" ]; then
    IFS=',' read -r -a recipients <<<"$BACKUP_AGE_RECIPIENT"
    for r in "${recipients[@]}"; do encrypt+=(-r "$r"); done
  fi
  [ -n "${BACKUP_AGE_RECIPIENTS_FILE:-}" ] && encrypt+=(-R "$BACKUP_AGE_RECIPIENTS_FILE")
  ext="age"
elif [ -n "${BACKUP_GPG_RECIPIENT:-}" ]; then
  command -v gpg >/dev/null 2>&1 || die "gpg is not installed" 2
  encrypt=(gpg --batch --yes --trust-model always --encrypt --recipient "$BACKUP_GPG_RECIPIENT")
  ext="gpg"
else
  die "set BACKUP_AGE_RECIPIENT (or BACKUP_AGE_RECIPIENTS_FILE) or BACKUP_GPG_RECIPIENT: backups are never stored unencrypted" 2
fi

pg_tool() {
  if [ -n "${PG_CLIENT_IMAGE:-}" ]; then
    docker run --rm -i --network "${PG_CLIENT_NETWORK:-host}" \
      -e PGHOST -e PGPORT -e PGUSER -e PGPASSWORD -e PGDATABASE -e PGSSLMODE "$PG_CLIENT_IMAGE" "$@"
  else
    "$@"
  fi
}

work="$(mktemp -d)"
cleanup() { rm -rf "$work"; s3_cleanup; }
trap cleanup EXIT

ts="$(date -u +%Y%m%dT%H%M%SZ)"
key="${prefix}/${PGDATABASE}-${ts}.dump.${ext}"
file="$work/backup.${ext}"

log "dumping ${PGDATABASE} on ${PGHOST} (encrypted with ${ext}, no plaintext on disk)"
started=$SECONDS
pg_tool pg_dump --format=custom --compress=6 --no-password "$PGDATABASE" | "${encrypt[@]}" >"$file"
size="$(wc -c <"$file" | tr -d ' ')"
[ "$size" -gt 1024 ] || die "backup is suspiciously small ($size bytes); not uploading"
log "encrypted dump: $size bytes in $((SECONDS - started)) s"

log "uploading to ${S3_BUCKET}/${key}"
s3_put "$file" "$key" || die "upload failed"
s3_list "$key" | grep -qx "$key" || die "uploaded object is not listed in the bucket"
rm -f "$file" # nothing of the backup stays on this host

# ---- retention: delete backups older than N days, always keeping the newest M ----
epoch_to_ts() { # portable: GNU date -d @, BSD date -r
  date -u -d "@$1" +%Y%m%dT%H%M%SZ 2>/dev/null || date -u -r "$1" +%Y%m%dT%H%M%SZ
}
cutoff="$(epoch_to_ts $(($(date -u +%s) - retention_days * 86400)))"
all=()
while IFS= read -r line; do
  [ -n "$line" ] && all+=("$line")
done < <(s3_list "${prefix}/${PGDATABASE}-" | grep -E "${PGDATABASE}-[0-9]{8}T[0-9]{6}Z\.dump\.(age|gpg)$" || true)
total="${#all[@]}"
deleted=0
for ((i = 0; i < total; i++)); do
  object="${all[$i]}"
  stamp="$(printf '%s' "$object" | sed -E 's/.*-([0-9]{8}T[0-9]{6}Z)\.dump\..*/\1/')"
  remaining=$((total - deleted))
  if [[ "$stamp" < "$cutoff" ]] && [ "$remaining" -gt "$min_keep" ]; then
    s3_delete "$object"
    deleted=$((deleted + 1))
    log "retention: deleted ${object}"
  fi
done
log "done: ${key} ($size bytes); $((total - deleted)) backup(s) kept, ${deleted} deleted (retention ${retention_days} days, minimum ${min_keep})"

if [ -n "${BACKUP_HEARTBEAT_URL:-}" ]; then
  curl --silent --show-error --max-time 10 --retry 2 -o /dev/null "$BACKUP_HEARTBEAT_URL" || log "warning: heartbeat ping failed"
fi
