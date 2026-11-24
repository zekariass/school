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
| S3 (SeaweedFS, stand-in for Cloudflare R2) | 8343 | key `school-dev` / `school-dev-secret` |
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
| http://localhost:8180/actuator/health | Health; `/readiness`, `/liveness` probes |
| http://localhost:8180/actuator/prometheus | Metrics (local only; private network elsewhere) |

Deployed environments configure, besides the database:

| Variable | Purpose |
|---|---|
| `REDIS_URL` | **Required** in deployed environments, e.g. `rediss://user:pass@host:6379` (configuration cache). If unset, Spring falls back to `localhost:6379`. |
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
| `SPRING_MAIL_HOST`, `SPRING_MAIL_PORT`, `SPRING_MAIL_USERNAME`, `SPRING_MAIL_PASSWORD` | SMTP for email sign-in codes; without a host, emails are dropped with a warning. `SCHOOL_MAIL_FROM` sets the sender |
| `SCHOOL_BOOTSTRAP_ADMIN_PHONE` / `SCHOOL_BOOTSTRAP_ADMIN_EMAIL` / `SCHOOL_BOOTSTRAP_ADMIN_NAME` | First platform administrator, created at startup only while no active platform administrator exists |
| `SERVER_FORWARD_HEADERS_STRATEGY` | Set to `native`/`framework` only behind a trusted proxy; otherwise rate limits and audit see the proxy's address (`X-Forwarded-For` is ignored) |

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
  -e SCHOOL_AUTH_JWT_KEYS="$(npm run -s jwk:generate -w school-backend)" \
  -e SCHOOL_AUTH_OTP_PEPPER=change-me-to-a-long-random-secret-0123456789 \
  -e SCHOOL_SMS_PROVIDER=console -e SCHOOL_SMS_ALLOW_CONSOLE=true \
  school-backend:local

# Web: Next.js standalone server on Node 26 (build context: repo root)
docker build -f apps/web/Dockerfile -t school-web:local .
docker run --rm -p 3000:3000 \
  -e API_ORIGIN=http://host.docker.internal:8080 \
  school-web:local
```

| Image | Port | Required env | Optional env |
|---|---|---|---|
| `school-backend` | 8080 | `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `SCHOOL_AUTH_JWT_KEYS`, `SCHOOL_AUTH_OTP_PEPPER`, `SCHOOL_SMS_PROVIDER` | the variables in [Backend configuration](#backend-configuration), `JAVA_TOOL_OPTIONS` |
| `school-web` | 3000 | `API_ORIGIN` (read at request time) | `PORT`, `HOSTNAME` |

Both run as a non-root user. The Postgres started by
`npm run infra:up` is reachable from a container at `host.docker.internal:5442`.

## Notes

- `apps/backend` is a Maven project. Its `package.json` only wraps `./mvnw`
  so Turborepo can schedule it.
- `apps/mobile` needs no Metro config changes. Expo SDK 52+ auto-detects
  the workspace root. `expo-env.d.ts` is generated by `expo start` and is
  git-ignored; run the dev server once before `npx tsc --noEmit`.
