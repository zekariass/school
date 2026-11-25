# School — Monorepo

Multi-country, multi-tenant education platform (ClassDojo-style school & family
engagement, learning, and tutoring). npm workspaces + [Turborepo](https://turbo.build).

## Documentation

| Doc | What it is |
|---|---|
| [docs/Education_Platform.md](docs/Education_Platform.md) | Product specification |
| [docs/Spec_Review.md](docs/Spec_Review.md) | Spec inconsistencies, resolutions, confirmed decisions D1–D7 |
| [docs/Database_Design.md](docs/Database_Design.md) | Full PostgreSQL schema (tables, fields, relationships, phases) |
| [docs/Implementation_Plan.md](docs/Implementation_Plan.md) | Step-by-step delivery plan, Phase 0 → Phase 6 |
| [docs/i18n/REVIEWING.md](docs/i18n/REVIEWING.md) | Amharic native-speaker review pack (spreadsheet, guide, `scripts/i18n/*` to export and apply) |

## Layout

```
apps/
  backend    Spring Boot 4.2 API (Maven, Java 25)
  mobile     Expo SDK 58 / React Native app
  web        Next.js 16 app
packages/
  api-client      typed API client generated from the backend OpenAPI spec
  calendar        Gregorian <-> Ethiopian conversion and formatting (display only)
  config          shared tsconfig and ESLint presets
  design-tokens   colors, radii, typography; generates Tailwind 4 theme CSS
  i18n            UI message catalogs (en, am) and locale helpers
  validation      shared zod form schemas (errors are i18n keys)
infra/       local development infrastructure (Docker Compose)
docs/        specification, review, database design, plan
```

Shared packages are source-only TypeScript: both Next.js (Turbopack) and
Expo (Metro) compile them directly, so there is no package build step.
Generated files (API types, theme CSS) are committed; regenerate them with
`npm run codegen`. See [CLAUDE.md](CLAUDE.md) for repository conventions.

## Prerequisites

- **Node.js** ≥ 22.18 (26 recommended, see `.nvmrc`) and npm 10
- **Docker Desktop** (local Postgres, Redis, S3, mail)
- **JDK 25**, registered in Maven Toolchains:
  ```bash
  brew install openjdk@25
  ```
  then create `~/.m2/toolchains.xml` with an entry for it:
  ```xml
  <toolchains>
    <toolchain>
      <type>jdk</type>
      <provides><version>25</version></provides>
      <configuration>
        <jdkHome>/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home</jdkHome>
      </configuration>
    </toolchain>
  </toolchains>
  ```
  The pom selects JDK 25 through `maven-toolchains-plugin`, so Maven builds,
  tests and `spring-boot:run` use JDK 25 even when the `java` on your `PATH`
  is another version. Running the packaged jar directly (`java -jar`) does
  need a JDK 25 `java`.
- **Mobile (optional):** Xcode with an iOS simulator for `npm run ios -w school-mobile`
  (a development build); Android Studio with an emulator for Android. See
  `apps/mobile/README.md`. Keep the repository **outside iCloud-synced folders**
  (Desktop/Documents with iCloud Drive): iCloud adds extended attributes that make
  the iOS code-signing step fail.

## Local infrastructure

```bash
npm run infra:up      # start Postgres, Redis, S3, Mailpit
npm run infra:ps      # status
npm run infra:down    # stop (data volumes are kept)
```

| Service | Host port | Notes |
|---|---|---|
| PostgreSQL 18 | 5442 | DB `school`, user/password `school` |
| Redis | 6390 | |
| S3 (SeaweedFS, stand-in for Cloudflare R2) | 8343 | key `school-dev` / `school-dev-secret`; the backend creates bucket `school-media-dev` and its CORS rule for `http://localhost:3100` |
| Mailpit | 1035 (SMTP), [8035 (UI)](http://localhost:8035) | |

Ports are non-default on purpose so this stack runs alongside other local
projects. All credentials are for local development only.

## Commands

Run everything from the repo root:

```bash
npm install                 # install all JS workspaces

npm run dev                 # turbo: run all dev servers
npm run build               # turbo: build all packages
npm run lint                # turbo: lint all packages
npm run typecheck           # turbo: type-check all packages and apps
npm run test                # turbo: test all packages
npm run codegen             # regenerate API client types and theme CSS
```

Target a single app with `--filter`:

```bash
npm run dev -- --filter=school-web       # Next.js dev server (http://localhost:3100)
npm run dev -- --filter=school-mobile    # Expo dev server (Metro on :8081)
npm run dev -- --filter=school-backend   # Spring Boot, "local" profile (http://localhost:8180)
npm run build -- --filter=school-web
```

Or run workspace scripts directly:

```bash
npm run dev --workspace=school-web
```

## School workspace (web)

`/{locale}/s/{slug}` is the school workspace. Its menu comes from `GET /me` (`lib/school/access.ts`): administrators see
Dashboard, Classes, Students, Teachers, Academic year, Settings and Features; a teacher (a membership, no role) sees the
Dashboard and My classes; a student-only account is sent to `/student-app` ("students sign in with the School app").
Student login codes are generated on a class's *Login codes* tab and printed from
`/{locale}/s/{slug}/classes/{classId}/login-codes/print`. The plaintext codes live only in the React state of the issuing
tab (never in URLs, storage or query caches), so reloading the print page asks you to issue them again.

## Web configuration

- `API_ORIGIN` (server-only) is the School API origin. It defaults to
  `http://localhost:8180` outside production and is required in production
  (see `apps/web/.env.example`). Browsers never call the API directly; they go
  through the BFF at `/api/bff/api/v1/...`.
- `APP_ORIGIN` (server-only, optional) is the public origin of the web app
  (for example `https://app.example.com`). Mutating requests to `/api/auth/*`
  and `/api/bff/*` must come from this origin (`Origin` header, or
  `Sec-Fetch-Site: same-origin`); it defaults to the origin of the request URL,
  so set it only behind a proxy that rewrites the host.
- `NEXT_PUBLIC_UPLOAD_MODE` (build-time, `direct` by default): `direct` PUTs files from the
  browser straight to the presigned storage URL (the bucket needs a CORS rule for the web
  origin); `proxy` streams them through `PUT /api/bff/uploads/{mediaId}` instead, for buckets
  that cannot allow the origin. In proxy mode set `UPLOAD_PROXY_ORIGINS` (server-only,
  comma-separated storage origins; defaults to `http://localhost:8343` outside production).
- `NEXT_PUBLIC_REALTIME_URL` (build-time, default `ws://localhost:8180/ws`): the API's WebSocket
  gateway for live notifications. Browsers connect to it directly with a single-use ticket from
  `POST /api/bff/api/v1/realtime/tickets`; deployed it is `wss://<api host>/ws` and the web origin
  must be in the API's `CORS_ALLOWED_ORIGINS`.
- Sessions live in httpOnly cookies (`sch_at`, `sch_rt`). `proxy.ts` renews
  the access token before it expires; with several web instances use sticky
  routing (see the M1c note in `docs/Implementation_Plan.md`).
- Local pages: http://localhost:3100/en (landing), `/en/sign-in`,
  `/en/status` (live API check through the BFF), `/en/platform` (console for
  platform, country and organization administrators), `/en/schools` (school
  picker) and `/en/s/<school>` (workspace). Sign in locally as the dev admin
  `0900000001` (see below); the code appears in the backend log.

## Backend configuration

- Deployed environments supply `DB_URL`, `DB_USERNAME` and `DB_PASSWORD`.
  There are no built-in defaults, so a misconfigured deploy fails fast.
- Local development uses the `local` Spring profile
  (`apps/backend/src/main/resources/application-local.yaml`), which points
  at the Compose Postgres. `npm run dev` enables it automatically.
- Backend tests use Testcontainers (`postgres:18`), so Docker must be running
  for `npm run test`.
- Flyway owns the schema (`ddl-auto: validate`). Migrations go in
  `apps/backend/src/main/resources/db/migration`.

**Signing in locally.** The `local` profile creates a platform administrator
"Dev Admin" with phone `+251900000001` at startup, prints one-time codes in the
backend log (console SMS provider) and sends email codes to Mailpit. The JWT
signing key is generated per process, so tokens stop working when the backend
restarts.

```bash
curl -X POST localhost:8180/api/v1/auth/otp/request -H 'Content-Type: application/json' \
  -d '{"phone":"0900000001"}'
# log: [console SMS] to +2519****001: 123456 is your School sign-in code ...
curl -X POST localhost:8180/api/v1/auth/otp/verify -H 'Content-Type: application/json' \
  -d '{"phone":"+251900000001","code":"123456"}'      # -> accessToken, refreshToken
curl localhost:8180/api/v1/me -H "Authorization: Bearer <accessToken>"
```

On the web, open `/en/sign-in`, enter `0900000001` and the code from the backend
log. To try a school administrator: in the console create a school, open its
**Administrators** tab, look up a new phone number, create the user and assign
them, then sign in with that number (the code is in the backend log again).

Local endpoints (`npm run dev -- --filter=school-backend`):

| URL | What |
|---|---|
| http://localhost:8180/api/v1/meta | Public service metadata + server time |
| http://localhost:8180/swagger-ui/index.html | API explorer (local only) |
| http://localhost:8180/v3/api-docs | OpenAPI spec (local only) |
| http://localhost:8180/actuator/health | Health; `/readiness` (database + Redis) and `/liveness` probes, status only; `/actuator/info` returns version and commit |
| http://localhost:8180/actuator/prometheus | Metrics (local only; private network elsewhere) |

Deployed environments (`staging`, `prod` profiles) refuse to start unless the configuration is complete and safe:
the check lists **every** problem in one message (see [docs/ops/Runbook.md](docs/ops/Runbook.md#2-what-the-deployed-profiles-enforce)).
They configure, besides the database:

| Variable | Purpose |
|---|---|
| `REDIS_URL` | **Required** in every profile except `local` and `test`, e.g. `rediss://user:pass@host:6379`. Startup fails when it is unset (there is no localhost fallback). |
| `CORS_ALLOWED_ORIGINS` | Comma-separated browser origins allowed to call the API |
| `API_DOCS_ENABLED` | `true` to serve the OpenAPI spec / Swagger UI (off by default) |
| `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs` | JSON logs for log aggregation |
| `MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT` | Enables trace export (e.g. `http://collector:4318/v1/traces`) |
| `TRACING_SAMPLING_PROBABILITY` | Trace sampling ratio (default 0.1) |
| `OTLP_METRICS_EXPORT_ENABLED` | `true` to push metrics over OTLP (default: scrape Prometheus) |
| `SCHOOL_AUTH_JWT_KEYS` | **Required.** JWK Set (`{"keys":[...]}`) of EC P-256 keys with private parts. The first key signs access tokens, all keys verify (put a new key first to rotate; drop the old one after 15 minutes). Generate one with `npm run jwk:generate -w school-backend`. Secret. |
| `SCHOOL_AUTH_OTP_PEPPER` | **Required.** HMAC key (≥ 32 characters) of stored one-time-code hashes. Secret; changing it invalidates codes in flight only. |
| `SCHOOL_AUTH_JWT_ISSUER` / `SCHOOL_AUTH_JWT_AUDIENCE` | Token `iss` / `aud` (default `school-api` / `school-apps`) |
| `SCHOOL_SMS_PROVIDER` | **Required.** SMS provider; only `console` (log output) exists yet, which is refused unless `SCHOOL_SMS_ALLOW_CONSOLE=true` (staging only, never production) |
| `SCHOOL_PUSH_PROVIDER` | **Required.** Push provider: `expo` (Expo push service, `https://exp.host`) or `log` (writes a masked line per push; refused outside local/test unless `SCHOOL_PUSH_ALLOW_LOG=true`). |
| `SCHOOL_GUARDIANS_INVITE_BASE_URL` | **Required.** Web app origin used in guardian invitation links (`{origin}/{locale}/invite/{token}`), e.g. `https://app.example.org`; the local profile uses `http://localhost:3100`. |
| `EXPO_ACCESS_TOKEN` | Optional bearer token for the Expo push API (enhanced push security). Secret. |
| `SPRING_MAIL_HOST`, `SPRING_MAIL_PORT`, `SPRING_MAIL_USERNAME`, `SPRING_MAIL_PASSWORD` | SMTP for email sign-in codes; without a host, emails are dropped with a warning. `SCHOOL_MAIL_FROM` sets the sender |
| `SCHOOL_BOOTSTRAP_ADMIN_PHONE` / `SCHOOL_BOOTSTRAP_ADMIN_EMAIL` / `SCHOOL_BOOTSTRAP_ADMIN_NAME` | First platform administrator, created at startup only while no active platform administrator exists |
| `SCHOOL_STORAGE_ENDPOINT` | **Required.** S3 API endpoint the backend uses (R2: `https://<account>.r2.cloudflarestorage.com`). |
| `SCHOOL_STORAGE_PUBLIC_ENDPOINT` | Host that clients put in presigned URLs (default: `SCHOOL_STORAGE_ENDPOINT`). Set it when the backend reaches the store on a private address. For uploads from a physical phone locally, see [Media storage](#media-storage). |
| `SCHOOL_STORAGE_BUCKET`, `SCHOOL_STORAGE_ACCESS_KEY`, `SCHOOL_STORAGE_SECRET_KEY` | **Required.** Bucket name and its S3 credentials (secret). The bucket and its CORS rule (allow `PUT`/`GET` from the web origin) are provisioned with the platform; the application only creates them locally. |
| `SCHOOL_STORAGE_REGION` | Signing region (default `auto`, which is what R2 uses). |
| `SPRING_PROFILES_ACTIVE` | `staging` or `prod` in deployed environments: strict startup validation (below), JSON logs, graceful shutdown, Flyway validate-only. |
| `SCHOOL_MIGRATIONS_MODE` | `validate` (default in `staging`/`prod`: the app only checks the schema, migrations run with `--migrate`) or `migrate` (Flyway migrates at startup; default elsewhere). |
| `SCHOOL_MIGRATE_ONLY=true` / argument `--migrate` | One-shot migrate mode of the image: runs Flyway and exits (0 ok, 1 failed, 2 bad `DB_*`), no web server. |
| `SCHOOL_BUILD_COMMIT` | Set by the image build (`GIT_SHA`); returned by `/actuator/info` for deploy smoke tests. |
| `SENTRY_DSN` | Optional. Enables error tracking (Sentry or compatible); events carry no request data, users or personal data. Off when empty. |
| `SERVER_FORWARD_HEADERS_STRATEGY` | Set to `native`/`framework` only behind a trusted proxy; otherwise rate limits and audit see the proxy's address (`X-Forwarded-For` is ignored) |

### Media storage

Uploads go straight from the client to S3-compatible storage with a presigned `PUT`
(`POST /api/v1/media/uploads`, then `POST /api/v1/media/{id}/complete`); the backend
checks size, type and the file's first bytes, and processes images (JPEG renditions with
all metadata, including GPS, removed) and PDFs (first-page preview). Reads are signed
links that expire in 5 minutes. Locally the backend (`local` profile) creates the bucket
`school-media-dev` in SeaweedFS and a CORS rule for `http://localhost:3100`, so the web
app uploads directly from the browser (`NEXT_PUBLIC_UPLOAD_MODE=direct`, the default).

**Uploading from a phone or the Android emulator.** Presigned URLs carry the host from
`SCHOOL_STORAGE_PUBLIC_ENDPOINT`, so a phone on Wi-Fi cannot use `localhost`. Expose the
store on the LAN and name that address:

```bash
S3_BIND_ADDRESS=0.0.0.0 npm run infra:up          # SeaweedFS listens on all interfaces
SCHOOL_STORAGE_PUBLIC_ENDPOINT=http://192.168.1.20:8343 npm run dev -- --filter=school-backend
```

(The iOS Simulator reaches `localhost` itself, so it needs neither; the Android emulator
needs `http://10.0.2.2:8343`.) PDF previews render text with PDFBox, so a stripped-down
backend base image without `fontconfig` can make them fail; the default Temurin image has it.

### Notifications and real-time

`NotificationService.notify(...)` (Java API of the `notifications` module) writes one inbox row per
recipient in the recipient's language, then, after commit, pushes the item to their open apps over
the WebSocket gateway and, where the user's preference allows PUSH, to their push devices
(quiet hours are held back unless the notification is URGENT and its type may be urgent). Devices
are registered with `POST /api/v1/me/push-devices`; they stop receiving pushes when their session
is signed out. Locally `SCHOOL_PUSH_PROVIDER` defaults to `log` (pushes appear in the backend log);
set `SCHOOL_PUSH_PROVIDER=expo` to send real ones (needs an EAS project id in the mobile app).

The gateway is STOMP over a plain WebSocket at `ws://localhost:8180/ws` (`wss://<api host>/ws`
deployed; `CORS_ALLOWED_ORIGINS` is also its allowed-origin list). Apps connect with
`Authorization: Bearer <access token>` (mobile) or a single-use `ticket` from
`POST /api/v1/realtime/tickets` (browsers), then subscribe to `/user/queue/notifications`. Behind a
load balancer the WebSocket path needs upgrade support and idle timeouts above 30 seconds (the
heartbeat is 10 seconds); instances share events through Redis pub/sub (`rt:user-events`).

### Schools, classes and student sign-in

Under `/api/v1/schools/{schoolId}/...`: academic years and periods, grade levels, subjects, classes with
rosters, staff and students (see the OpenAPI document). Teachers and students have no role assignments; the
web and mobile apps read `GET /api/v1/me` -> `memberships` and `GET /api/v1/me/classes`. Young students sign in
with a QR or text code that a teacher issues per class (`POST .../classes/{classId}/login-codes`, shown once)
at `POST /api/v1/auth/login-code`; codes are stored only as an HMAC with `SCHOOL_AUTH_OTP_PEPPER`, so changing
the pepper invalidates every issued code. Optional settings (platform, country, organization or school level):
`classes.teachers_can_create`, `students.login_code.ttl_days`, `students.password_login.enabled`; the session
lifetime of code sign-ins is `school.auth.sessions.login-code-absolute-ttl` / `login-code-idle-ttl`.

### Roster import

`/api/v1/schools/{schoolId}/imports` (permission `school.import.manage`, school administrators and the higher
administrators): upload a CSV or XLSX through the media flow (purpose `IMPORT_FILE`, 10 MB), `POST /imports`
(preview and suggested mapping), `PUT .../mapping`, `POST .../dry-run`, review `GET .../rows`, decide possible
duplicates, `POST .../commit`; templates at `GET /imports/templates/{type}?format=csv|xlsx`. Types: grade levels,
subjects, classes, staff, students, enrollments (academic years are set up by hand). Limits (20,000 rows, 60
columns, 1,000 characters per cell, 30 s to read) and retention (raw rows and the file are purged 30 days after
creation, 7 after commit) are `school.imports.*` (`ImportProperties`). Roster files hold children's data: they are
only ever read by the import. The Ethiopian calendar conversion is a Java port of `packages/calendar`; after changing
that package run `node scripts/gen-calendar-parity-fixture.mjs` and the backend's `CalendarParityTest`.

**Changing the API:** after editing controllers or DTOs, run
`npm run openapi:export -w school-backend` (writes
`packages/api-client/openapi/openapi.json`) and then `npm run codegen`
(regenerates the TypeScript client). `OpenApiSpecTest` fails until both are done.

## Container images

Both deployable apps ship as Docker images, built by
[`.github/workflows/images.yml`](.github/workflows/images.yml). Pushes to
`main` and `v*.*.*` tags publish to GHCR as
`ghcr.io/<owner>/school-backend` and `ghcr.io/<owner>/school-web`
(tags: `sha-<short>`, `main`, `latest`, and `1.2.3` / `1.2` for release tags).
Pull requests only build the images (no push) to catch Dockerfile breakage.

```bash
# Backend: Spring Boot on Temurin JRE 25 (build context: apps/backend)
docker build -t school-backend:local apps/backend
docker run --rm -p 8080:8080 \
  -e DB_URL=jdbc:postgresql://host.docker.internal:5442/school \
  -e DB_USERNAME=school -e DB_PASSWORD=school \
  -e REDIS_URL=redis://host.docker.internal:6390 \
  -e SCHOOL_AUTH_JWT_KEYS="$(npm run -s jwk:generate -w school-backend)" \
  -e SCHOOL_AUTH_OTP_PEPPER=change-me-to-a-long-random-secret-0123456789 \
  -e SCHOOL_SMS_PROVIDER=console -e SCHOOL_SMS_ALLOW_CONSOLE=true \
  -e SCHOOL_PUSH_PROVIDER=log -e SCHOOL_PUSH_ALLOW_LOG=true \
  -e SCHOOL_GUARDIANS_INVITE_BASE_URL=http://localhost:3000 \
  school-backend:local

# Web: Next.js standalone server on Node 26 (build context: repo root)
docker build -f apps/web/Dockerfile -t school-web:local .
docker run --rm -p 3000:3000 \
  -e API_ORIGIN=http://host.docker.internal:8080 \
  school-web:local
```

| Image | Port | Required env | Optional env |
|---|---|---|---|
| `school-backend` | 8080 | `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `REDIS_URL`, `SCHOOL_AUTH_JWT_KEYS`, `SCHOOL_AUTH_OTP_PEPPER`, `SCHOOL_SMS_PROVIDER`, `SCHOOL_PUSH_PROVIDER`, `SCHOOL_GUARDIANS_INVITE_BASE_URL` | the variables in [Backend configuration](#backend-configuration), `JAVA_TOOL_OPTIONS` |
| `school-web` | 3000 | `API_ORIGIN` (read at request time) | `PORT`, `HOSTNAME` |

Both run as a non-root user. The Postgres started by
`npm run infra:up` is reachable from a container at `host.docker.internal:5442`.

## Deployment (staging and production)

Provider-agnostic kit in [`infra/deploy/`](infra/deploy): `docker-compose.yml` (backend, web, one-shot `migrate`,
Caddy proxy, optional Cloudflare tunnel and local Postgres/Redis; non-root, read-only filesystems, health checks,
resource limits), `.env.example` (every variable, secrets marked) and `deploy.sh`:

```bash
# on the server, in /opt/school (infra/deploy copied there; .env filled in from .env.example)
./deploy.sh --backend ghcr.io/<owner>/school-backend:sha-<short> --web ghcr.io/<owner>/school-web:sha-<short> --commit <full sha>
./deploy.sh --rollback        # previous images
./deploy.sh --status
```

It pulls pinned images (`sha-<commit>` tags or digests, never `latest`), runs the migrations once with the backend
image's `--migrate` mode, rolls the services, smoke-tests and rolls back by itself when that fails. The GitHub Actions
pipelines `deploy-staging.yml` (after `Images` on `main`) and `deploy-prod.yml` (tag `v*.*.*`, environment
`production` with required reviewers) use the same script over SSH; both skip with a notice until the owner has set
up the environments (Runbook, section 4). Operations manual, secrets, backups, Cloudflare and R2 setup, incident
basics: [docs/ops/Runbook.md](docs/ops/Runbook.md). Where to host and what it costs:
[docs/ops/Hosting_Options.md](docs/ops/Hosting_Options.md).

Operations scripts (`scripts/ops/`):

| Script | What |
|---|---|
| `backup-postgres.sh` | `pg_dump` (custom format) encrypted with `age`/`gpg`, uploaded to an S3-compatible bucket, retention; no plaintext on disk |
| `restore-drill.sh` | restores the newest backup into a scratch database, runs integrity checks, drops it |
| `r2-smoke.mjs` | the 9-step manual smoke test of [docs/research/R2_Compatibility.md](docs/research/R2_Compatibility.md) against any S3-compatible bucket |

Mobile builds: `apps/mobile/eas.json` has `development`, `preview`, `staging` and `production` profiles with OTA
channels (Runbook, section 11).

## Notes

- `apps/backend` is a Maven project. Its `package.json` only wraps `./mvnw`
  so Turborepo can schedule it.
- `apps/mobile` needs no Metro config changes. Expo SDK 52+ auto-detects
  the workspace root. `expo-env.d.ts` is generated by `expo start` and is
  git-ignored; run the dev server once before `npx tsc --noEmit`.
