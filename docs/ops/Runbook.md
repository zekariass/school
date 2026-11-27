# Operations runbook

How the School platform is deployed, configured, backed up and operated. Written for M1.5 before any hosting account exists: every hostname, account id and secret below is a placeholder (`example.org`, `<ACCOUNT_ID>`). What is **verified** and what is **not** is stated per section; the verification status is summarised in section 14.

Related: [Hosting_Options.md](Hosting_Options.md) (where to run it, costs, what the owner must decide), [../research/R2_Compatibility.md](../research/R2_Compatibility.md) (R2 behaviour and the manual smoke test), [../Implementation_Plan.md](../Implementation_Plan.md) (M1.5), `infra/deploy/` (the deployment kit), `scripts/ops/` (backup, restore drill, R2 smoke test).

## 1. Environments

| | Local | Staging | Production |
|---|---|---|---|
| Spring profile | `local` | `staging` (+ `deployed`) | `prod` (+ `deployed`) |
| Purpose | development | demo and testing outside a laptop, real HTTPS, cookies, CORS | pilot schools |
| Deploy | `npm run infra:up`, `npm run dev` | automatic from `main` after the Images workflow (`deploy-staging.yml`) | `v*.*.*` tag, after approval (`deploy-prod.yml`) |
| Config check at startup | none | strict | strictest (no console SMS, no `log` push, no public metrics) |
| Data | fake | fake or anonymised only | real children's data: only after counsel's sign-off on region and the data-protection law |

Topology of the single-host kit (`infra/deploy/docker-compose.yml`):

```
Internet --> Cloudflare (DNS, TLS, CDN, WAF) --tunnel--> proxy (Caddy, plain HTTP)
                                                           |-- app host --> web (Next.js :3000) --BFF--> backend
                                                           '-- api host --> backend (Spring Boot :8080)
backend --> postgres (container or managed)   --> redis (container or managed)   --> Cloudflare R2 (media)
```

Browsers only call the web app (the BFF) and, for uploads and the WebSocket, the API host directly; presigned upload and download URLs point at R2.

## 2. What the deployed profiles enforce

`staging` and `prod` refuse to start unless the configuration is complete and safe. All problems are listed in one message (secret values are never printed):

```
APPLICATION FAILED TO START
Invalid configuration for profile(s) [staging]: 3 problems
  1. REDIS_URL is not set; without it Spring would silently use localhost:6379 (...)
  2. SCHOOL_AUTH_OTP_PEPPER is a documented sample or development value; generate a random one
  3. CORS_ALLOWED_ORIGINS every origin must use https:// (found: http://localhost:3100)
```

Rules (`system.startup.DeploymentConfigValidator`, covered by unit tests): database settings present, not localhost, no development password and at least 12 characters; `REDIS_URL` present and not localhost; `CORS_ALLOWED_ORIGINS` non-empty, `https://` only, exact origins, no `*`; JWT keys a valid JWK Set of EC P-256 keys whose first key has its private part, ephemeral keys off; OTP pepper at least 32 characters and none of the documented sample values; SMS provider `console` refused in `prod` and allowed in `staging` only with `SCHOOL_SMS_ALLOW_CONSOLE=true`; push provider `log` likewise; guardian invitation base URL https; storage endpoint, bucket and keys present, no localhost endpoints, public (presigned) endpoint https, `create-bucket` false, no development bucket or keys; the dev administrator (`+251900000001`) refused; `local`/`test` profiles must not be combined with a deployed one; in `prod`, public metrics and API docs off. Any other profile (for example none) only checks the two settings that would otherwise fall back to localhost: database and Redis.

**Consequence worth knowing:** only the `console` SMS provider exists today, so `prod` cannot start until a real SMS provider is implemented and chosen (Hosting_Options.md, SMS). Staging runs with `SCHOOL_SMS_ALLOW_CONSOLE=true` (one-time codes appear in the log, so use only test phone numbers).

Other deployed-profile behaviour: JSON logs (ECS), graceful shutdown (30 s), `school.migrations.mode=validate` (section 5).

## 3. Health, readiness and version

Unauthenticated, status only (no component details):

| URL (API host) | Meaning | Used by |
|---|---|---|
| `/actuator/health/liveness` | process is up | container restarts |
| `/actuator/health/readiness` | database **and** Redis reachable | deploy smoke test, load balancers, uptime checks (503 when down) |
| `/actuator/info` | `build.version`, `build.time`, `app.commit` (full git SHA of the image) | "is the right version live" checks |
| `/api/v1/meta` | service metadata and server time | clients |

`/actuator/prometheus` is **not** public in `prod` (and the proxy answers 404 for every `/actuator/*` path except health and info). Scrape it from inside the host (`http://backend:8080/actuator/prometheus`).

Web: **there is no dedicated health route in the Next.js app today** (`apps/web/app/api` has only `auth`, `bff`, `invitations`). Until one is added, the kit probes `GET /en/sign-in` (a public page that renders without the API). Its version is the image label `org.opencontainers.image.revision` set by `images.yml`; `deploy.sh` and the workflow compare the API commit only. A `GET /api/health` route returning `{"status":"ok","commit":...}` would be a small, worthwhile web change.

## 4. GitHub setup (owner)

Nothing deploys until these exist. Until then both deploy workflows end green with a notice ("Deploy skipped").

1. **Environments** (Settings > Environments): `staging` (no protection needed) and `production` (**Required reviewers**: at least one person other than the author; optionally a wait timer and "prevent self-review"; deployment branches/tags: `v*.*.*`).
2. **Secrets, per environment** (each environment has its own host and key):

   | Secret | Value |
   |---|---|
   | `DEPLOY_SSH_HOST` | server address (DNS name or IP) |
   | `DEPLOY_SSH_USER` | deploy user on the server (member of the `docker` group, nothing else) |
   | `DEPLOY_SSH_PRIVATE_KEY` | private half of a dedicated ed25519 key (`ssh-keygen -t ed25519 -f deploy_key -N ''`); public half in the user's `authorized_keys` (restrict with `from=` if the runner ranges are acceptable, otherwise leave open) |
   | `DEPLOY_SSH_KNOWN_HOSTS` | output of `ssh-keyscan -t ed25519 <host>` taken from a trusted network (the host key is pinned, no trust on first use) |

3. **Variables, per environment:** `PUBLIC_API_URL` (e.g. `https://api.staging.example.org`) and `PUBLIC_WEB_URL` (e.g. `https://staging.example.org`) for the post-deploy smoke test; optional `DEPLOY_PATH` (default `/opt/school`) and `DEPLOY_SSH_PORT` (default 22).
4. **Repository variables (the on-switches):** `DEPLOY_STAGING_ENABLED=true` and `DEPLOY_PROD_ENABLED=true`. They exist so a half-configured environment never hangs on an approval or fails; set each only after its environment is complete.
5. **Branch protection** stays as is (PR plus `js`, `backend`, `migrations`). Tag protection: restrict who can create `v*` tags (Settings > Tags) to the release managers: a tag is what triggers production.
6. **Scheduled backups (optional, section 8):** secrets `BACKUP_*` (see below) and repository variable `BACKUP_WORKFLOW_ENABLED=true`.
7. **GHCR packages** are public (images.yml). If the owner makes them private, set `GHCR_USER`/`GHCR_TOKEN` (a read-only token with `read:packages`) in the server's `.env`.

What the pipelines do: staging deploys the `sha-<short>` images of the commit that `main` just published; production resolves the tag's images to immutable digests, refuses tags that are not `vMAJOR.MINOR.PATCH` or not on `main`, and waits for reviewer approval. Both rsync `infra/deploy/` to the server (never `.env`, `certs/` or `.deploy/`), run `deploy.sh`, then smoke-test the public URLs from GitHub (readiness, `app.commit` equals the deployed SHA, web page, metrics not public). Application secrets live only in the server's `.env`; GitHub holds only the means to reach the server.

## 5. Deploy, migrations and rollback

### Server preparation (once)

1. A Linux VM with Docker Engine and Compose v2, a non-root `deploy` user in the `docker` group, firewall allowing only SSH (and 80/443 when no tunnel is used), unattended security upgrades, time sync.
2. `mkdir -p /opt/school && chown deploy /opt/school`; copy `infra/deploy/` there once (the pipeline keeps it in sync afterwards), then `cp .env.example .env && chmod 600 .env` and fill it in. Every variable is documented in `infra/deploy/.env.example` and marked `[SECRET]`/`[required]`.
3. Create the Cloudflare tunnel (section 9) and put its token in `.env`.
4. First deploy by hand: `./deploy.sh --backend ghcr.io/<owner>/school-backend:sha-<short> --web ghcr.io/<owner>/school-web:sha-<short> --commit <full sha>`.

### What `deploy.sh` does

1. Preflight: Docker, `.env`, the compose file renders (a missing required variable stops here); refuses `latest`/`main` tags.
2. Pulls the two images.
3. Starts the local Postgres/Redis containers when their profiles are on, and waits for them to be healthy.
4. **Runs the migrate job once** (`docker compose run --rm migrate`, the backend image with `--migrate`). If it fails (exit 3) nothing was rolled and the old version keeps running.
5. Rolls `backend`, `web` and `proxy` and waits for their health checks.
6. Smoke test through the local proxy: API readiness, liveness, `/api/v1/meta`, the web sign-in page, `app.commit` equals `--commit`, `/actuator/prometheus` is 404; plus the public URLs when `PUBLIC_*_URL` are set.
7. **On any failure it re-starts the previous images** (recorded in `.deploy/images.env`) and exits 1. On success the new references become the current ones and the previous ones are kept for `--rollback`.

Single host means a short interruption while containers are replaced (seconds to a minute). Schedule production deploys outside school hours until a second instance exists.

### Migrations: once per deploy, before traffic

- The backend image has a one-shot mode: `docker run --rm -e DB_URL=... -e DB_USERNAME=... -e DB_PASSWORD=... <image> --migrate` (or `SCHOOL_MIGRATE_ONLY=true`). It runs Flyway only (no web server, Redis or storage) and exits `0` (migrated or already current), `1` (a migration failed) or `2` (missing or invalid `DB_*`).
- In `staging`/`prod` the application itself runs Flyway in **validate-only** mode (`school.migrations.mode=validate`): at startup it checks that every migration and every repeatable seed script is applied exactly as shipped and otherwise refuses to start with "Run the migrate job first". Instances therefore never race on the schema, and a new image cannot start against an old schema. (Override with `SCHOOL_MIGRATIONS_MODE=migrate` only for a one-off emergency.)
- Concurrent migrate jobs are safe: Flyway takes a lock on its history table, so a second runner waits and finds nothing to do (tested with three parallel runs).
- **Migrations are forward-only and must be backward-compatible with the previous release** (expand, then contract): add columns/tables/nullable fields in release N; remove or tighten them in release N+1 after N is fully deployed. The roll-back below re-starts the old images on the new schema; a migration that breaks the old code makes rollback impossible, so never ship one in a single step. CI's migration checks (immutability, ordering) apply as usual.
- A dangerous migration (data rewrite, large table lock) is a deliberate, separate step: back up first (`scripts/ops/backup-postgres.sh`), run it in a quiet window, record the duration.

### Rollback

- Automatic: a failed health check or smoke test inside `deploy.sh`.
- Manual: `./deploy.sh --rollback` (previous images, same smoke test). `--status` shows current and previous.
- Database: not rolled back. If a migration itself was the problem and the data changed, restore from backup into a scratch database first (section 8) and decide; a restore over production is a last resort and loses everything since the dump.
- Verify afterwards: `curl -s https://<api host>/actuator/info` shows the commit you expect.

## 6. Secrets: inventory and rotation

All application secrets live in the server's `/opt/school/.env` (mode 600, owner `deploy`), generated on the server or stored in the owner's password manager. GitHub holds only the SSH deploy credentials. Nothing is committed; `.env.example` has placeholders only.

| Secret | Where used | How to create | Rotation |
|---|---|---|---|
| `SCHOOL_AUTH_JWT_KEYS` | signs and verifies access tokens | `npm run -s jwk:generate -w school-backend` | Every 6-12 months or on suspicion: generate a new key, put it **first** in the JWK Set (old key second), deploy; after 15 minutes (access-token lifetime) remove the old key and deploy again. Refresh tokens are not affected. |
| `SCHOOL_AUTH_OTP_PEPPER` | HMAC of one-time codes and **student login codes** | `openssl rand -base64 48` | Rotating invalidates every issued student login code and codes in flight (users request again; teachers re-issue class codes). Do it in a quiet window, only on suspicion or yearly. |
| `SCHOOL_SAFEGUARDING_ENCRYPTION_KEYS_K1` (and `_K2`, `_K3` for rotation) with `SCHOOL_SAFEGUARDING_ENCRYPTION_ACTIVE_KEY_ID` | AES-256-GCM key(s) that encrypt the narrative, the notes and the referral text of **safeguarding incidents** (children's data) | `openssl rand -base64 32` per key (32 random bytes, base64); the active id is the key new text is encrypted with (e.g. `k1`). The deployed profiles **refuse to start** without a valid key, and refuse the development and test keys of the repository | **Never delete a key that encrypted something** (the text could not be read again). Rotation: (1) generate the new key, add it as `..._KEYS_K2`; (2) set `..._ACTIVE_KEY_ID=k2` and deploy: new text uses `k2`, old text still reads with `k1`; (3) keep `k1` for as long as incidents written under it exist (the retention of closed incidents is 7 years by default). Back the keys up in the password manager and a sealed offline copy, **separately from the database backups**: a database backup without the keys cannot be read, which is the point. If a key leaks: add a new key, make it active, and re-encrypt is **not** automatic: tell the owner and counsel (this is exposure of children's data, section 12). |
| `SCHOOL_ATTENDANCE_SECRETS_KEYS_K1` (and `_K2`, `_K3` for rotation) with `SCHOOL_ATTENDANCE_SECRETS_ACTIVE_KEY_ID`, and `SCHOOL_ATTENDANCE_SECRETS_QR_KEY` | The **pepper keys of the attendance credentials** (the QR on a student's or staff member's card; only `HMAC(key, token)` is stored) and the **server key of the rotating codes** (`HMAC(qr key, session \| window)`) | `openssl rand -base64 32` per key (32 random bytes, base64). The deployed profiles **refuse to start** without valid values and refuse the development and test keys of the repository. One pepper per purpose: not the OTP pepper | **Credential keys, overlap rotation (no card is reprinted):** (1) generate a new key, add it as `..._KEYS_K2`; (2) set `..._ACTIVE_KEY_ID=k2` and deploy: new cards use `k2`, cards made under `k1` still scan, and each is re-hashed under `k2` on its first successful scan; (3) when `select key_id, count(*) from attendance_credentials where revoked_at is null and expires_at > now() group by 1` shows no live card under `k1` (or after the school year, when every card has been reissued or expired), remove `k1`. **Removing a key while live cards still use it makes them look unknown to every scanner (refused as invalid): reissue those cards.** If a credential key leaks: add a new key, make it active, and reissue the cards (a leaked pepper plus a database dump lets an attacker test guesses, but a token has 128 random bits, so this is not a way to forge a card; still, rotate). **QR key:** rotating it makes every code in flight invalid at once (a teacher's display and the codes already handed to it stop working): do it in a quiet window; students and teachers only need a new code (open a new session). Back the keys up with the other secrets, separately from database backups. |
| `DB_PASSWORD` | database role | random 24+ characters | Change it in Postgres (`ALTER ROLE ... PASSWORD`), update `.env` (and `REDIS`/migrate use the same), run `docker compose up -d backend`. Managed DB: use the provider's rotation. |
| `REDIS_PASSWORD` / `REDIS_URL` | Redis | random | Update `.env`, restart `redis` and `backend`. |
| R2 runtime token (`SCHOOL_STORAGE_ACCESS_KEY/SECRET_KEY`) | Object Read & Write on **one** bucket | Cloudflare dashboard (section 10) | Create a new token, update `.env`, deploy, then delete the old token. Existing presigned URLs expire within minutes (5 min read, 10 min upload). |
| R2 backup token (`BACKUP_S3_*`) | write-only-ish token for the **backup** bucket | separate bucket and token | Same as above, independent of the media token. |
| Backup encryption | age key pair | `age-keygen -o backup.key`; the server gets only the public key (`age1...`) | The private key is stored offline (password manager + a second sealed copy), never on the server. To rotate, add the new public key as an extra recipient first; old backups stay decryptable only with the old private key: keep it as long as they are retained. |
| `TUNNEL_TOKEN` | cloudflared | Zero Trust dashboard | Roll the tunnel token in the dashboard, update `.env`, restart `tunnel`. |
| `EXPO_ACCESS_TOKEN`, `SPRING_MAIL_PASSWORD`, `SENTRY_DSN` | push, email, error tracking | respective consoles | Per provider. A DSN is low risk (it only allows sending events) but rotate if leaked. |
| `DEPLOY_SSH_PRIVATE_KEY` (GitHub) | deploy over SSH | dedicated ed25519 key | Generate a new key, append the public half on the server, update the secret, delete the old public key. |
| Cloudflare API tokens, Expo/EAS token | IaC, mobile builds | per service | Least privilege, expiry dates, owned by a shared role account, not a person. |

If a secret leaks: rotate it, then look for use (audit log, provider logs), then record the incident (section 12). A leaked JWT signing key means every token can be forged: rotate at once and also revoke sessions (`UPDATE auth_sessions SET revoked_at = now() WHERE revoked_at IS NULL`, or `logout-all` through the API for affected users).

## 7. Observability

All optional and off by default except structured logs.

- **Logs:** JSON (ECS) on stdout of every container, rotated by Docker (`json-file`, 20 MB x 5). Each line has the request id and trace ids. Ship them with Grafana Alloy, Vector or the Better Stack/Datadog agent reading the Docker JSON logs (Hosting_Options.md lists free tiers). Do **not** ship logs from the staging console SMS provider: they contain sign-in codes. The application never logs login codes, tokens or passwords; phone numbers and emails are masked.
- **Traces:** set `MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT` in `.env` (an OTLP/HTTP collector URL such as `http://collector:4318/v1/traces`); leave the line out for no export (an empty value breaks startup). Sampling `TRACING_SAMPLING_PROBABILITY` (0.1). Metrics are scraped from `/actuator/prometheus` on the private network; OTLP metrics push is `OTLP_METRICS_EXPORT_ENABLED=true`.
- **Error tracking (backend):** `io.sentry:sentry-spring-boot-4-starter` 8.66.0 (Sentry's Spring Boot 4 starter, built against Boot 4.2.0, released 25 Nov 2026; verified here only by the full test suite booting with it on the classpath and a staging boot with a DSN). It is **inert unless `SENTRY_DSN` is set**. When set: server errors only (4xx `ApiException`s, validation and access-denied errors are dropped), no request data of any kind (URL, query, headers, cookies, body), no user, no server name, no breadcrumbs, `send-default-pii=false`, and exception messages are scrubbed of emails, phone numbers, database values (`Key (col)=(value)`), bearer tokens and long opaque strings (`system.observability`, unit-tested). Stack traces are sent. The DSN points at Sentry SaaS or a compatible self-hosted server (GlitchTip): pick the region/instance with counsel because event text could still contain residual personal data. Web and mobile error tracking are not wired (apps are out of scope of M1.5).
- **Uptime checks** (external, from outside the host): HTTP check every 1-5 minutes on `https://<api host>/actuator/health/readiness` (expect 200 and `"status":"UP"`), `https://<app host>/en/sign-in` (200), and a TLS-expiry check. Alert to a phone number or chat that someone actually watches. A heartbeat monitor (healthchecks.io style) for the nightly backup via `BACKUP_HEARTBEAT_URL`.
- **Monitoring checklist** (review weekly, alert on the starred items): * readiness DOWN > 2 min; * 5xx rate; * `audit_logs` growth and partition creation (the `audit` module creates months ahead; check that next month's partition exists); * disk usage of the VM and the `pgdata` volume (> 70 %); * Postgres connections vs `max_connections`; * Redis memory vs `REDIS_MAXMEMORY`; * failed logins and OTP rate-limit hits (a spike means abuse); * backup heartbeat missing > 26 h *; * R2 storage and Class A operations; * certificate/tunnel status; * stale `import_jobs` and `event_publication` rows (incomplete events pile up when a listener keeps failing).

## 8. Backups and restore

### What is backed up

PostgreSQL only (all business data and audit logs). Media objects live in R2 (durable, but **not** versioned: enable R2 bucket-level protections you can afford, and do not give application tokens delete rights on a backup bucket). Redis is a cache (rebuildable). Secrets are not in backups; keep them in the password manager.

### Nightly backup

`scripts/ops/backup-postgres.sh` streams `pg_dump --format=custom` through `age` (public-key encryption), writes only ciphertext to a private temp directory, uploads it to an S3-compatible **backup bucket** (separate from media, separate token), verifies the object is listed, deletes the local file, and prunes backups older than `BACKUP_RETENTION_DAYS` (default 14) while always keeping the newest `BACKUP_MIN_KEEP` (7). No plaintext ever touches the disk. `gpg` (`BACKUP_GPG_RECIPIENT`) works the same way.

Cron on the host (the host needs `age`, `curl`, and either `pg_dump` 18 or Docker with `PG_CLIENT_IMAGE=postgres:18`):

```cron
# /etc/cron.d/school-backup   nightly 01:30 UTC, i.e. 04:30 in Addis Ababa
30 1 * * *  deploy  set -a; . /opt/school/backup.env; set +a; /opt/school/scripts/ops/backup-postgres.sh >> /var/log/school-backup.log 2>&1
```

`/opt/school/backup.env` (mode 600, **not** in git) holds `PGHOST=postgres`, `PGUSER`, `PGPASSWORD`, `PGDATABASE`, `PG_CLIENT_IMAGE=postgres:18`, `PG_CLIENT_NETWORK=school-internal`, `BACKUP_S3_*`, `BACKUP_AGE_RECIPIENT`, `BACKUP_ENV`, `BACKUP_HEARTBEAT_URL` (variables are documented at the top of the script). The `scripts/` directory is not part of the deploy kit's rsync: copy it to the host (`git clone` read-only deploy key, or rsync) once and update it with releases.

Alternative for a **managed** database (no host of your own): `.github/workflows/backup.yml` runs the same script nightly on a GitHub runner against the database over a network path you provide. It is disabled until `BACKUP_WORKFLOW_ENABLED=true`; for a database that is not reachable from the internet use cron on a host inside the network instead. Prefer the provider's point-in-time recovery in addition when available (Hosting_Options.md).

### Restore drill (do it before launch, then quarterly, and after any Postgres major upgrade)

`scripts/ops/restore-drill.sh` downloads the newest backup (or `DRILL_KEY`), decrypts it on the fly with the **private** age key (`RESTORE_AGE_IDENTITY_FILE`), restores it into a **new scratch database** (`restore_drill_<timestamp>`, never the live one), runs integrity checks and drops the scratch database, also on failure. Checks: no failed Flyway migrations; the schema has a migration version (optionally equal to `DRILL_EXPECT_MIGRATION`); seed tables (`countries`, `roles`, `permissions`) are not empty; `users` has at least `DRILL_MIN_USERS` rows; a three-table join runs; all constraints are validated; row counts are printed for information. The elapsed time is the measured restore time (RTO for this data size).

```bash
set -a; . ./restore.env; set +a        # BACKUP_S3_*, PGHOST/PGUSER/PGPASSWORD of a scratch server, BACKUP_ENV, BACKUP_DATABASE
RESTORE_AGE_IDENTITY_FILE=~/secure/backup.key scripts/ops/restore-drill.sh
```

For a real disaster recovery: provision the host, deploy the kit with the **same** image version as the backup's migration version (the restore drill prints it), `pg_restore` into an empty database (the drill script shows the exact command and flags), run `deploy.sh`, verify, and re-point DNS/tunnel. Recovery point objective with nightly dumps is up to 24 hours; recovery time is the drill's elapsed time plus provisioning. Tighten both with point-in-time recovery (managed Postgres or WAL archiving) before real children's data is held.

### Drill record

**Drill of 25 November 2026 (local rehearsal, not a production result).** Setup: PostgreSQL 18.7 in Docker (`school_m15`, schema applied by the backend image's `--migrate` mode: 26 migrations, version 6.01, one bootstrap administrator), SeaweedFS 4.52 as the S3 store, `age` 1.3.2, `pg_dump`/`pg_restore`/`psql` from the `postgres:18` image through Docker.

| Step | Result |
|---|---|
| Backup (`backup-postgres.sh`) | 333,444 bytes encrypted, dump plus encryption 4 s, upload about 2 s, object listed in the bucket, temp directory removed (no plaintext on disk at any point) |
| Retention (`BACKUP_RETENTION_DAYS=0 BACKUP_MIN_KEEP=1`, two backups) | the older backup deleted, the newest kept ("1 kept, 1 deleted") |
| No encryption recipient configured | refused with exit 2 and no upload |
| Restore drill (`restore-drill.sh`) | newest backup restored in 28 s into `restore_drill_<timestamp>`; checks PASS: 0 failed migrations, version 6.01 (equals expected), countries 2, roles 9, permissions 19, users 1 (minimum 1), a three-table join, 0 unvalidated constraints; total 77 s; scratch database dropped |
| Drill with the wrong private key | `age: no identity matched`, `pg_restore failed`, exit 1, scratch database still dropped |

Caveats: the data set is tiny and the machine was busy with parallel image builds, so the timings say nothing about production recovery time (each check started a fresh `psql` container, about 3-4 s each); the bucket was SeaweedFS, not R2; the age private key lived on the same laptop. The first real drill must run against a copy of staging data, from a machine that is not the backup host, and its elapsed time becomes the documented recovery time. **Record every later drill here** (date, backup key, restore time, result, who ran it).


## 9. Cloudflare setup (owner steps)

None of this was exercised: no Cloudflare account existed. Steps follow Cloudflare's documentation as understood on 25 Nov 2026; verify each against the dashboard.

1. **Add the domain** to a Cloudflare account and switch the registrar's nameservers to Cloudflare. Create two hostnames per environment, for example `staging.example.org` (web) and `api.staging.example.org` (API); production `app.example.org`/`api.example.org`. Use hostnames under one registrable domain so cookies and CORS stay simple (the web app talks to the API server-side; only uploads and the WebSocket go browser-to-API).
2. **SSL/TLS:** mode **Full (strict)**; Always Use HTTPS on; minimum TLS 1.2; enable HSTS only after staging works for a week (start with a short max-age, no `includeSubDomains` until every subdomain is https). With the tunnel (recommended) the origin needs no certificate; without it, create a **Cloudflare Origin CA** certificate, place it in `infra/deploy/certs/origin.pem` and `origin.key` on the server, set `CADDYFILE=Caddyfile.origin-tls`, and open 443 only to Cloudflare's IP ranges.
3. **Tunnel (recommended for a single VM):** Zero Trust > Networks > Tunnels > create a *cloudflared* tunnel, copy the token into `.env` (`TUNNEL_TOKEN`) and add **public hostnames** for the app and API hosts, both pointing to `http://proxy:80`. No inbound ports are needed on the VM. DNS records are created automatically.
4. **WebSockets** are supported on all plans; the gateway uses `wss://<api host>/ws` and a 10 s heartbeat, which keeps connections alive through Cloudflare's idle limit.
5. **WAF and rate limiting** (free plans include basic rate limiting rules; check the current limits on your plan): 
   - Managed ruleset on; Bot Fight Mode on for the web host, **off or "skip" for the API host** (the mobile apps are not browsers and would be challenged).
   - Rate limit `POST /api/v1/auth/*` on the API host and `POST /api/auth/*` on the web host: 20 requests per minute per IP, action block for 10 minutes. This is on top of the application's own limits (OTP: 5/hour per destination and 30/hour per IP; password: 10 per 15 minutes).
   - Rate limit `/api/v1/**` per IP at a generous level (for example 300 per minute) as a flood guard. Schools share NAT addresses: a whole school can look like one IP; do not make this strict.
   - Custom rule: block `/actuator/*` except `/actuator/health*` and `/actuator/info` (the proxy already answers 404; defence in depth), block `/v3/api-docs*` and `/swagger-ui*` on production.
   - Country/ASN blocking: **not** recommended without counsel and the owner's input (Ethiopian users, diaspora guardians, roaming).
6. **Caching:** do not cache API paths. HTML of the web app is dynamic (cookies): no "Cache Everything". Static assets under `/_next/static/*` are cacheable (long immutable headers are already sent by Next.js).
7. **Cookies:** the web app sets `sch_at`/`sch_rt` as `httpOnly`, `SameSite=Lax`, host-only (no `Domain` attribute) and **`Secure` when `NODE_ENV=production`**, which the web image sets. Because Cloudflare terminates HTTPS and the tunnel/proxy forward plain HTTP, the browser still sees `https://` and accepts `Secure` cookies; the app must be reached over https only. `APP_ORIGIN` must equal the public origin exactly (the BFF Origin check).
8. **Zero Trust Access in front of staging** (recommended): a Cloudflare Access policy (email one-time PIN for the team) on the staging app host keeps strangers out of a demo environment. Do not put Access in front of the API host (mobile apps cannot pass it); exempt `/api/*` or use service tokens only if you also protect the API host.
9. **Email DNS** (when email sending is chosen): SPF, DKIM, DMARC records for the sender domain.
10. **Verify** after DNS: `curl -sI https://<app host>/en/sign-in`, `curl -s https://<api host>/actuator/info`, a sign-in on a phone over mobile data, and the `cf-ray` header's three-letter suffix to see which Cloudflare data centre serves Ethiopian networks (Hosting_Options.md, latency).

## 10. Cloudflare R2 setup (owner steps)

Read [R2_Compatibility.md](../research/R2_Compatibility.md) first. **Decide EU jurisdiction vs default placement with counsel before creating the production bucket** (it cannot be changed later; there is no African region).

1. Enable R2 (payment method required). One bucket per environment: `school-staging-media`, `school-prod-media`; a separate private bucket per environment for **backups** (`school-staging-backups`, `school-prod-backups`).
2. **Runtime token** (media): R2 > Manage API tokens > create an **Object Read & Write** token scoped to that **one bucket** only (prefer an account-owned token). Put the Access Key ID and Secret Access Key in `.env` (`SCHOOL_STORAGE_ACCESS_KEY`, `SCHOOL_STORAGE_SECRET_KEY`); the endpoint is `https://<ACCOUNT_ID>.r2.cloudflarestorage.com` (EU jurisdiction: `https://<ACCOUNT_ID>.eu.r2.cloudflarestorage.com`) in both `SCHOOL_STORAGE_ENDPOINT` and `SCHOOL_STORAGE_PUBLIC_ENDPOINT` (presigned URLs only work on the S3 API host, not custom domains). `SCHOOL_STORAGE_REGION=auto`. **Never** give the application an Admin token and never set `SCHOOL_STORAGE_CREATE_BUCKET`.
3. **Backup token:** another token, Object Read & Write on the backup bucket only, used by `backup-postgres.sh` (and read-only for the restore machine when possible). Do not reuse the media token.
4. **CORS rule** (bucket > Settings > CORS policy): exact web origins, nothing else. This is what lets browsers PUT presigned uploads and read ETag:

   ```json
   [
     {
       "AllowedOrigins": ["https://staging.example.org"],
       "AllowedMethods": ["PUT", "GET", "HEAD"],
       "AllowedHeaders": ["Content-Type"],
       "ExposeHeaders": ["ETag"],
       "MaxAgeSeconds": 3600
     }
   ]
   ```

   Changes take up to about 30 seconds. Mobile apps ignore CORS. If a bucket cannot allow the origin, build the web app with `NEXT_PUBLIC_UPLOAD_MODE=proxy` and set `UPLOAD_PROXY_ORIGINS`.
5. **Lifecycle rule for stale `PENDING_UPLOAD` objects: do not create the prefix rule that R2_Compatibility.md describes.** Finding from the code (`MediaUploads.objectKey`): a pending upload and a ready original share one key shape (`{owner}/{id}/{purpose}/{mediaId}/original`), so no prefix separates them and a lifecycle expiry would eventually delete **ready** originals (the document warns never to expire READY objects). Stale uploads are already removed by `MediaMaintenance` (hourly job: media rows still `PENDING_UPLOAD` after 24 hours are deleted together with their object). Optional safe rule: *Abort incomplete multipart uploads* after 1 day (the application does not use multipart, so this is only a backstop). A prefix rule becomes possible if the backend one day uploads pending objects under a dedicated prefix (for example `pending/`) and moves them on `complete`: record that as a backlog item.
6. **Run the smoke test** against the real staging bucket before the first user: `SMOKE_ENDPOINT=... SMOKE_BUCKET=... SMOKE_ACCESS_KEY=... SMOKE_SECRET_KEY=... SMOKE_WEB_ORIGIN=https://staging.example.org SMOKE_OTHER_BUCKET=<another bucket> node scripts/ops/r2-smoke.mjs` (add `SMOKE_ADMIN_ACCESS_KEY/SMOKE_ADMIN_SECRET_KEY` with an Admin token for step 8). It prints PASS/FAIL/INFO per step and records the open Content-Length question (step 3). Then do one real upload from the staging web app and from a phone: that exercises the SDK's `chunkedEncodingEnabled(false)` path the script cannot.
7. R2 free tier and prices: see R2_Compatibility.md (egress is free). Presigned downloads are not edge-cached and are bearer links until they expire (5 minutes for reads).

## 11. Mobile builds (Expo EAS)

`apps/mobile/eas.json` defines `development` (dev client, simulator), `preview` (internal APK/ad hoc, channel `preview`), `staging` (store build for TestFlight/internal testing, channel `staging`) and `production` (store, channel `production`). Each sets `EXPO_PUBLIC_API_URL` (the only variable the app reads for the API base URL) and, for store builds, `SCHOOL_UNIVERSAL_LINK_HOST` (the web host that serves invitation links). The hostnames in the file are placeholders (`example.com`): replace them when the domain exists. Owner steps, none done yet:

1. Create an Expo account (organisation), install EAS CLI, `eas login`.
2. In `apps/mobile`: `npx eas-cli init` (creates the project and writes `extra.eas.projectId` into `app.json`), then `npx eas-cli update:configure` (adds `updates.url` and `runtimeVersion` to `app.json`). **Both edit `app.json`**, which this milestone deliberately did not touch.
3. Apple Developer Program (USD 99/year) and Google Play Console (USD 25 once): bundle id/package `com.nemaz.school` are set. `eas credentials` to let EAS manage signing; fill the `submit` profiles (`ascAppId`, Google service account) in `eas.json`.
4. Secrets for builds: `eas env:create` (EAS environment variables) for anything not `EXPO_PUBLIC_*`; **never put secrets in `EXPO_PUBLIC_*`** (they are inlined into the bundle). A push-credentials upload (FCM, APNs) is needed for `SCHOOL_PUSH_PROVIDER=expo`.
5. Build: `eas build --profile staging --platform all`. OTA updates: `eas update --channel staging --message "..."`; a channel serves updates only to builds made with the same profile and a compatible runtime version. Do not publish an OTA update that depends on a new backend before that backend is deployed.
6. Physical-device uploads in staging work because R2 presigned URLs are public https URLs (no LAN workaround needed).

## 12. Incidents

1. **Triage (first 15 minutes):** is it down (uptime alert) or degraded? `./deploy.sh --status`; `docker compose ps`; `curl https://<api host>/actuator/health/readiness`. Check the last deploy (`/actuator/info`) and the status of Cloudflare, R2 and the host provider.
2. **Bad deploy:** `./deploy.sh --rollback`. **Database down:** restart the container, check disk (`df -h`), then logs; do not delete WAL by hand. **Redis down:** the API is unready by design; restart Redis (data is a cache).
3. **Sign-in storms or abuse:** tighten the Cloudflare rate limit, check `audit_logs` (sign-in failures, lockouts), revoke sessions if accounts were taken over.
4. **Suspected data exposure** (children's data): contain (rotate secrets, revoke sessions, block the path), preserve logs and the audit trail, tell the owner immediately; notification duties to the regulator and affected families are set by the data-protection law and counsel (Proclamation 1321/2024) and the school contracts: **have the contact list and the decision owner written down before launch**.
5. After: a short written post-mortem (timeline, cause, fix, what detection was missing), actions tracked as issues.

Keep an on-call sheet outside the system (who, how to reach them, where the password manager emergency access is). Admin access to the host: SSH keys only, no passwords, root login off.

## 13. Data-subject requests and retention (pointers)

- Spec: `docs/Education_Platform.md` (data protection and consent sections) and `docs/Spec_Review.md` (decisions D1-D7); schema in `docs/Database_Design.md`. Consent and policy records live in the `consent` module tables; guardian relationships in `guardians`; student personal data (date of birth, gender, username) is only shown at full data-access level.
- Access and correction: the platform's own screens first; for exports and deletion the procedure is **not built yet** (no self-service export/erasure endpoint). Until it is, handle requests as an operator task: identify the person through the school, query by user id, export the rows of the user across modules, delete or anonymise through a reviewed script, and write an `audit_logs` entry; never edit audit rows. Document each request (date, requester, decision) outside the application database.
- Media of students needs media-publication consent; deleting a media row also clears its R2 objects (`MediaDeleted`); backups still contain deleted data until they expire (14 days): say so in the privacy notice and honour deletions again after a restore.
- Import files and raw import rows (children's data) are purged automatically (30 days after creation, 7 after commit). Audit logs are partitioned monthly; the retention period for audit data is a counsel decision (open).
- Cross-border transfer and local-storage questions: Hosting_Options.md section 2 (open for counsel).

## 14. Verification status of this milestone

| Item | Status |
|---|---|
| Startup validator, migrate mode, validate-only start, probes, structured logs | unit and integration tests; booted from the Docker image against local Postgres/Redis (see the drill record and the report) |
| R2 smoke script | run against local SeaweedFS only; **never run against R2** |
| Backup and restore scripts | exercised against local Postgres 18 and SeaweedFS (drill record above); not against R2 |
| Deploy kit (`docker-compose.yml`, `deploy.sh`, Caddy) | rendered and exercised on this machine with the backend and web images; **not on a real VM, not behind Cloudflare or a tunnel** |
| GitHub workflows | linted with actionlint; **never run on GitHub** (no environments or secrets exist) |
| Cloudflare, R2 bucket settings, EAS | documentation only |
| Sentry | compiles, boots, scrubber unit-tested; **no event was ever sent to a Sentry server** |
