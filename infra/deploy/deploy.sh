#!/usr/bin/env bash
# Deploys pinned images of the School platform on this Docker host and rolls back when the smoke test fails.
#
#   ./deploy.sh --backend ghcr.io/<owner>/school-backend:sha-abc1234 \
#               --web     ghcr.io/<owner>/school-web:sha-abc1234 \
#               [--commit <full git sha>]      # the smoke test then also checks GET /actuator/info -> app.commit
#   ./deploy.sh --rollback                      # re-deploy the previously deployed images
#   ./deploy.sh --status                        # what is deployed, container health
#   DEPLOY_SKIP_PULL=1 ./deploy.sh ...          # use images already on the host (rehearsals, air-gapped hosts)
#
# Image references are pinned: a tag that images.yml publishes (sha-<short commit>, or a vX.Y.Z release tag) or a
# digest (ghcr.io/<owner>/school-backend@sha256:...). "latest" and "main" are refused.
#
# Order of a deploy:
#   1. preflight (docker, .env, rendered compose file)           5. smoke test through the local proxy
#   2. pull both images                                          6. on failure: roll the previous images back
#   3. run the migrate job ONCE (Flyway; nothing else migrates)  7. on success: remember the images as current
#   4. roll the services (backend, web, proxy)
#
# Migrations are forward-only. They must stay backward-compatible with the PREVIOUS release (add columns/tables
# first, remove them a release later), because a rollback re-starts the previous images on the migrated schema
# (docs/ops/Runbook.md). The script never touches the database except through the migrate job.
#
# Exit codes: 0 deployed, 1 failed and rolled back (or rollback itself failed: see the output), 2 usage or
# configuration error, 3 migration failed (nothing was rolled; the old version is still running).

set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"

STATE_DIR=".deploy"
CURRENT="$STATE_DIR/images.env"
PREVIOUS="$STATE_DIR/images.previous.env"
COMPOSE=(docker compose --env-file .env)

log() { printf '%s  %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*"; }
die() { echo "error: $*" >&2; exit "${2:-2}"; }

backend_ref="" web_ref="" expect_commit="" mode="deploy"
while [ $# -gt 0 ]; do
  case "$1" in
    --backend) backend_ref="${2:-}"; shift 2 ;;
    --web) web_ref="${2:-}"; shift 2 ;;
    --commit) expect_commit="${2:-}"; shift 2 ;;
    --rollback) mode="rollback"; shift ;;
    --status) mode="status"; shift ;;
    -h|--help) sed -n '2,24p' "$0"; exit 0 ;;
    *) die "unknown argument: $1 (see --help)" ;;
  esac
done

command -v docker >/dev/null 2>&1 || die "docker is not installed"
docker compose version >/dev/null 2>&1 || die "docker compose v2 is required"
[ -f .env ] || die ".env is missing: copy .env.example to .env and fill it in (chmod 600)"
mkdir -p "$STATE_DIR"

read_ref() { # read_ref <file> <VAR>
  [ -f "$1" ] && sed -n "s/^$2=//p" "$1" | tail -n 1 || true
}

compose() {
  local env_files=(--env-file .env)
  [ -f "$CURRENT" ] && env_files+=(--env-file "$CURRENT")
  docker compose "${env_files[@]}" "$@"
}

env_value() { # env_value <VAR> -> value from .env (without quotes)
  sed -n "s/^$1=//p" .env | tail -n 1 | sed -e "s/^'//" -e "s/'\$//" -e 's/^"//' -e 's/"$//'
}

if [ "$mode" = "status" ]; then
  echo "current:  backend=$(read_ref "$CURRENT" BACKEND_IMAGE_REF)  web=$(read_ref "$CURRENT" WEB_IMAGE_REF)"
  echo "previous: backend=$(read_ref "$PREVIOUS" BACKEND_IMAGE_REF)  web=$(read_ref "$PREVIOUS" WEB_IMAGE_REF)"
  compose ps
  exit 0
fi

if [ "$mode" = "rollback" ]; then
  [ -f "$PREVIOUS" ] || die "no previous release is recorded"
  backend_ref="$(read_ref "$PREVIOUS" BACKEND_IMAGE_REF)"
  web_ref="$(read_ref "$PREVIOUS" WEB_IMAGE_REF)"
  expect_commit=""
  log "rolling back to backend=${backend_ref} web=${web_ref} (migrations are not reverted)"
fi

[ -n "$backend_ref" ] && [ -n "$web_ref" ] || die "--backend and --web image references are required (see --help)"
for ref in "$backend_ref" "$web_ref"; do
  case "$ref" in
    *@sha256:*) ;;
    *:latest|*:main|*:master) die "refusing the moving tag in '$ref': deploy a sha-<commit> tag, a release tag or a digest" ;;
    *:*) ;;
    *) die "image reference '$ref' has neither a tag nor a digest" ;;
  esac
done

# ---------------------------------------------------------------- 1. preflight
log "preflight"
printf 'BACKEND_IMAGE_REF=%s\nWEB_IMAGE_REF=%s\n' "$backend_ref" "$web_ref" >"$STATE_DIR/images.next.env"
render() { docker compose --env-file .env --env-file "$STATE_DIR/images.next.env" "$@"; }
render config --quiet || die "docker-compose.yml does not render: a required variable in .env is missing (see .env.example)"

app_host="$(env_value APP_HOST)"
api_host="$(env_value API_HOST)"
proxy_port="$(env_value PROXY_HTTP_PORT)"; proxy_port="${proxy_port:-8080}"
proxy_bind="$(env_value PROXY_BIND)"; proxy_bind="${proxy_bind:-127.0.0.1}"
[ "$proxy_bind" = "0.0.0.0" ] && proxy_bind="127.0.0.1"
local_url="http://${proxy_bind}:${proxy_port}"

ghcr_user="$(env_value GHCR_USER)"
ghcr_token="$(env_value GHCR_TOKEN)"
if [ -n "$ghcr_user" ] && [ -n "$ghcr_token" ]; then
  printf '%s' "$ghcr_token" | docker login ghcr.io -u "$ghcr_user" --password-stdin >/dev/null
fi

# ---------------------------------------------------------------- 2. pull
if [ -n "${DEPLOY_SKIP_PULL:-}" ]; then
  log "DEPLOY_SKIP_PULL set: using images that are already on this host"
else
  log "pulling images"
  render pull backend web || die "image pull failed (is the tag published by images.yml?)"
fi

# Services that must exist before migrations: database and Redis when they run on this host.
# (naming a service of a disabled profile would start it, so only the ones COMPOSE_PROFILES enabled are used)
enabled_services="$(render config --services)"
data_services="$(printf '%s\n' "$enabled_services" | grep -E '^(postgres|redis)$' | tr '\n' ' ' || true)"
if [ -n "$data_services" ]; then
  log "starting data services on this host: ${data_services}"
  # shellcheck disable=SC2086
  render up -d --wait --no-recreate $data_services || die "the database or Redis container did not become healthy"
fi

# ---------------------------------------------------------------- 3. migrate (once, before traffic)
log "running the migrate job (Flyway) with the new backend image"
if ! render run --rm migrate; then
  rm -f "$STATE_DIR/images.next.env"
  die "migration failed: the running version was left untouched. Fix the migration or the data and deploy again" 3
fi

# ---------------------------------------------------------------- 4. roll
rolled_back=0
rollback() {
  local prev_backend prev_web
  prev_backend="$(read_ref "$CURRENT" BACKEND_IMAGE_REF)"
  prev_web="$(read_ref "$CURRENT" WEB_IMAGE_REF)"
  if [ -z "$prev_backend" ] || [ -z "$prev_web" ]; then
    log "no previous release to roll back to: stopping the failed services"
    compose stop backend web proxy tunnel 2>/dev/null || true
    return 1
  fi
  log "ROLLING BACK to backend=${prev_backend} web=${prev_web}"
  docker compose --env-file .env --env-file "$CURRENT" up -d --remove-orphans --wait backend web proxy \
    && rolled_back=1
}

log "rolling the services"
if ! render up -d --remove-orphans --wait backend web proxy; then
  log "services did not become healthy"
  rollback || true
  rm -f "$STATE_DIR/images.next.env"
  exit 1
fi
# A changed Caddyfile is picked up without dropping connections (the file is bind-mounted; the pipeline rsyncs with --inplace).
render exec -T proxy caddy reload --config /etc/caddy/Caddyfile >/dev/null 2>&1 || log "warning: caddy reload failed (the proxy keeps its previous configuration)"
# The tunnel (when its profile is on) needs no restart; start it if it is not running yet.
if printf '%s\n' "$enabled_services" | grep -qx tunnel; then render up -d --no-recreate tunnel; fi

# ---------------------------------------------------------------- 5. smoke test
smoke_failed=""
smoke() { # smoke <description> <command...>
  local description="$1"; shift
  if "$@" >/dev/null 2>&1; then log "PASS  $description"; else log "FAIL  $description"; smoke_failed=1; fi
}
curl_host() { curl -fsS --max-time 10 -H "Host: $1" "${local_url}$2"; }

log "smoke test (${local_url}, hosts ${api_host} / ${app_host})"
ok=""
for _ in 1 2 3 4 5 6; do
  if curl_host "$api_host" /actuator/health/readiness >/dev/null 2>&1; then ok=1; break; fi
  sleep 5
done
if [ -z "$ok" ]; then log "FAIL  API readiness never became UP"; smoke_failed=1; else log "PASS  API readiness is UP (database and Redis reachable)"; fi
smoke "API liveness" curl_host "$api_host" /actuator/health/liveness
smoke "API metadata endpoint" curl_host "$api_host" /api/v1/meta
smoke "web app answers" curl_host "$app_host" /en/sign-in
if [ -n "$expect_commit" ]; then
  info="$(curl_host "$api_host" /actuator/info 2>/dev/null || true)"
  if printf '%s' "$info" | grep -q "\"commit\":\"${expect_commit}\""; then
    log "PASS  API reports the deployed commit ${expect_commit}"
  else
    log "FAIL  API commit does not match ${expect_commit}"; smoke_failed=1
  fi
fi
# metrics must not be public through the proxy
code="$(curl -s -o /dev/null -w '%{http_code}' -H "Host: $api_host" "${local_url}/actuator/prometheus" || true)"
if [ "$code" = "404" ]; then log "PASS  /actuator/prometheus is not exposed by the proxy"; else log "FAIL  /actuator/prometheus answered ${code} through the proxy"; smoke_failed=1; fi

public_api="$(env_value PUBLIC_API_URL)"
public_web="$(env_value PUBLIC_WEB_URL)"
if [ -n "$public_api" ]; then
  smoke "public API readiness ${public_api}" curl -fsS --max-time 15 "${public_api}/actuator/health/readiness"
fi
if [ -n "$public_web" ]; then
  smoke "public web ${public_web}" curl -fsSL --max-time 15 -o /dev/null "${public_web}/en/sign-in"
fi

if [ -n "$smoke_failed" ]; then
  log "smoke test FAILED"
  if [ "$mode" = "rollback" ]; then
    rm -f "$STATE_DIR/images.next.env"
    die "the rollback target does not pass the smoke test either: investigate (docker compose logs backend)" 1
  fi
  rollback || true
  rm -f "$STATE_DIR/images.next.env"
  [ "$rolled_back" = 1 ] && log "rolled back; the previous release is running again"
  exit 1
fi

# ---------------------------------------------------------------- 7. remember
if [ -f "$CURRENT" ] && [ "$mode" != "rollback" ]; then cp "$CURRENT" "$PREVIOUS"; fi
if [ "$mode" = "rollback" ]; then
  # the release we rolled back FROM becomes "previous" so that --rollback twice returns to it
  [ -f "$CURRENT" ] && cp "$CURRENT" "$STATE_DIR/images.swap.env"
fi
mv "$STATE_DIR/images.next.env" "$CURRENT"
[ -f "$STATE_DIR/images.swap.env" ] && mv "$STATE_DIR/images.swap.env" "$PREVIOUS"
docker image prune -f --filter "until=168h" >/dev/null 2>&1 || true
log "DEPLOYED backend=${backend_ref} web=${web_ref}"
