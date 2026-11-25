#!/usr/bin/env bash
# Guard Flyway migrations on a pull request.
#
#   1. Versioned migrations (V*.sql) that already exist on the base branch must not be
#      modified, renamed or deleted (applied migrations are immutable). Repeatable
#      migrations (R__*.sql, the code-owned seed data) are meant to be edited and are exempt.
#   2. New migrations must have a version strictly greater than the highest
#      version on the base branch (no out-of-order migrations).
#
# Usage: scripts/ci/check-migrations-immutable.sh [base-branch]   (default: main)
# Compares origin/<base>...HEAD, so the base must be fetched (fetch-depth: 0).
set -euo pipefail

BASE="${1:-main}"
BASE_REF="origin/${BASE}"
DIR="apps/backend/src/main/resources/db/migration"

cd "$(git rev-parse --show-toplevel)"

if ! git rev-parse --verify --quiet "${BASE_REF}^{commit}" >/dev/null; then
  echo "error: ${BASE_REF} not found; fetch it first (git fetch origin ${BASE})" >&2
  exit 2
fi

status=0

# --- 1. immutability ---------------------------------------------------------
# -M so renames are reported as R (an unmodified rename is still a violation).
violations="$(git diff --name-status -M --diff-filter=MDR "${BASE_REF}...HEAD" -- ":(glob)${DIR}/V*.sql")"
if [[ -n "${violations}" ]]; then
  echo "::error title=Migration changed::Applied Flyway migrations are immutable; add a new migration instead."
  echo "These existing migrations were modified, renamed or deleted:" >&2
  echo "${violations}" >&2
  status=1
fi

# --- 2. ordering -------------------------------------------------------------
# Version = text between the leading V and the first double underscore,
# e.g. V0_02__x.sql -> 0.02. Compared segment by segment (separators _ or .).
version_of() {
  local name
  name="$(basename "$1")"
  name="${name#V}"
  name="${name%%__*}"
  echo "${name//_/.}"
}

# Prints "lt", "eq" or "gt" for $1 compared to $2.
compare_versions() {
  local -a a b
  IFS=. read -r -a a <<<"$1"
  IFS=. read -r -a b <<<"$2"
  local n=${#a[@]}
  ((${#b[@]} > n)) && n=${#b[@]}
  local i x y
  for ((i = 0; i < n; i++)); do
    x=$((10#${a[i]:-0}))
    y=$((10#${b[i]:-0}))
    if ((x < y)); then echo lt; return; fi
    if ((x > y)); then echo gt; return; fi
  done
  echo eq
}

highest=""
while IFS= read -r path; do
  [[ -z "${path}" ]] && continue
  v="$(version_of "${path}")"
  if [[ -z "${highest}" || "$(compare_versions "${v}" "${highest}")" == gt ]]; then
    highest="${v}"
  fi
done < <(git ls-tree --name-only -r "${BASE_REF}" -- "${DIR}/" | grep -E '/V[^/]*\.sql$' || true)

if [[ -n "${highest}" ]]; then
  added="$(git diff --name-only --diff-filter=A "${BASE_REF}...HEAD" -- "${DIR}/" | grep -E '/V[^/]*\.sql$' || true)"
  while IFS= read -r path; do
    [[ -z "${path}" ]] && continue
    v="$(version_of "${path}")"
    if [[ "$(compare_versions "${v}" "${highest}")" != gt ]]; then
      echo "::error file=${path},title=Out-of-order migration::Version ${v} must be greater than the highest version on ${BASE} (${highest})."
      status=1
    fi
  done <<<"${added}"
fi

if ((status == 0)); then
  echo "Migrations OK (highest version on ${BASE}: ${highest:-none})."
fi
exit "${status}"
