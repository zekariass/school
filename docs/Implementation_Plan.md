# Implementation Plan

*Version 1, 24 November 2026.*

This plan builds on three documents:
- [Education_Platform.md](Education_Platform.md), the product specification
- [Spec_Review.md](Spec_Review.md), with decisions D1–D7 confirmed
- [Database_Design.md](Database_Design.md), the schema

Section references: § is the specification, R-xx is a spec-review finding, and DB §x is a section of the database design.

---

## 0. How to read this plan

- **Order:** Phase 0 (foundation), then Phases 1–6, following the spec roadmap (§112–§117) with D4 applied: the payment core and video rooms move to Phase 3.
- **Phases contain milestones (M), and milestones contain numbered steps.** Steps within a milestone run in order. Milestones marked *parallel* can overlap.
- **Every feature milestone is a vertical slice.** It includes:
  - migration
  - domain and service
  - authorization policy
  - API
  - generated client
  - web UI
  - mobile UI
  - tests
  - English and Amharic strings
  - audit and analytics events
- **Estimates are rough.** They assume about 5 engineers: 2 backend, 1 web, 1–2 mobile, plus a part-time designer and QA. Re-plan once the team is known.

### Definition of done for every slice
1. The Flyway migration matches Database_Design and applies cleanly to a fresh database and to the previous release.
2. The service enforces authorization on the server, through the `authorization` module.
3. Each endpoint has an **allow/deny test** for every relevant role: student, teacher, subject teacher, guardian, restricted guardian, unrelated guardian, tutor, school admin, and admin of another school.
4. The OpenAPI spec is updated, the TypeScript client is regenerated, and CI fails if it has drifted.
5. Web and mobile screens are done in English and Amharic, with loading, empty, error and offline states, and meet accessibility basics.
6. Mutations write `audit_logs`. Domain events go to `outbox_events`. Notifications use `notification_types`.
7. Analytics events for the §133 release gates are emitted.
8. The module README describes its rules and settings keys.

### UI benchmark rule (ClassDojo)
ClassDojo is a reference for **patterns and quality** only:
- avatar-grid class view and one-tap points
- story feeds
- parent home focused on the child
- translate-in-place messaging
- the teacher toolkit
- a friendly, rounded, high-contrast visual style

We don't copy its trademarks, monster characters, illustrations, sounds or copy text. The product gets **its own** avatar system and brand (step 0.5).

---

## Phase 0 — Foundation (≈ 3–4 weeks)

Goal: every app builds, runs and deploys an empty "hello" release through CI, on the final architecture.

### M0.1 Developer environment (D1)
1. Install **JDK 25**. Create `~/.m2/toolchains.xml` with a `jdk` entry for version 25 that points at its home. Verify with `./mvnw -v` and `./mvnw verify` in `apps/backend`.
2. Correct the README so the toolchain setup is documented, not described as "already configured".
3. Add `infra/docker-compose.yml` with:
   - **PostgreSQL 18** (extensions `citext`, `btree_gist`, `pg_trgm`)
   - **Redis**
   - **SeaweedFS** (S3 API), as a local stand-in for Cloudflare R2. MinIO no longer publishes community Docker images
   - **Mailpit**, for email
4. Add `.env.example` files for each app. `.env` stays git-ignored.
5. Run `npm install` at the root. Confirm that `npm run dev`, `build`, `lint` and `test` work through Turborepo.
6. Read the versioned docs before writing any framework code, as `apps/web/AGENTS.md` and `apps/mobile/AGENTS.md` require:
   - Next.js 16: `node_modules/next/dist/docs/`
   - Expo SDK 58: docs.expo.dev/versions/v58.0.0
   - Spring Boot 4.2: reference documentation

### M0.2 Monorepo structure
1. Create the shared packages:

   | Package | Purpose |
   |---|---|
   | `packages/api-client` | TypeScript types and client generated from the backend OpenAPI spec, plus TanStack Query hooks |
   | `packages/design-tokens` | Colors, radii, spacing, typography and elevation as JSON, emitted as a Tailwind theme (web) and TS constants (mobile) |
   | `packages/i18n` | Message catalogs (`en`, `am`, then `om`, `ti`, `so`) and ICU formatting helpers |
   | `packages/calendar` | Pure-TS Gregorian↔Ethiopian conversion and formatting (§64). Display only |
   | `packages/validation` | zod schemas shared by web and mobile forms |
   | `packages/config` | Shared tsconfig and eslint presets |

2. Add Turborepo tasks: `codegen` (fetch the OpenAPI spec and generate the client), `typecheck`, and `dependsOn` wiring so apps build after packages.
3. Add a root `CLAUDE.md` / `AGENTS.md` with the conventions from this plan.

### M0.3 Backend skeleton (`com.nemaz.school`)
1. **Module layout** (§96, §132). One top-level package per module, each with `api` (controllers and DTOs), `application` (services), `domain` (entities and policies) and `infrastructure` subpackages:
   - Platform: `identity`, `authorization`, `tenancy`, `reference`, `people`, `academic`
   - Classroom: `attendance`, `engagement`, `media`, `social`, `messaging`, `communications`, `notifications`, `calendar`
   - Operations: `sis`, `portfolio`, `moderation`, `consent`, `audit`, `subscriptions`
   - Added in later phases: `curriculum`, `assessment`, `library`, `courses`, `video`, `payments`, `marketplace`, `finance`, `ai`
2. **Enforce module boundaries** with Spring Modulith, or ArchUnit if Modulith has no Boot-4.2-compatible release yet. Modules talk only through public application services and domain events.
3. **Shared kernel** (`common`):
   - RFC 9457 problem-details error model with stable error codes
   - cursor pagination
   - `Idempotency-Key` handling (`idempotency_keys`)
   - request id and MDC logging
   - clock abstraction
   - UUIDv7 generation
   - `TenantContext` (current user, active school, roles)
   - i18n of server messages
4. **Persistence:**
   - Flyway migrations under `db/migration`, named `V{phase}_{nn}__{module}.sql` as in DB §6.
   - JPA with `@Version` and `EnumType.STRING`. Hibernate's `ddl-auto` stays `validate`, never `update`.
5. **Outbox and jobs:**
   - Write `outbox_events` in the same transaction as the change.
   - A relay publishes them to in-process listeners and to Redis for fan-out.
   - Use a Postgres-backed scheduler (JobRunr or db-scheduler, whichever has Boot 4 support) for retries and scheduled work.
6. **OpenAPI:** springdoc, or the Boot-4-compatible equivalent, at `/v3/api-docs`. CI exports the spec into `packages/api-client`.
7. **Observability:**
   - Actuator health and readiness
   - Micrometer metrics
   - OpenTelemetry tracing
   - structured JSON logs
   - Sentry or an equivalent error tracker
8. **Tests:**
   - Testcontainers with Postgres 18 for integration tests
   - an architecture test for module boundaries
   - a baseline `contextLoads` test

**As built (24 Nov 2026):**
- **Module boundaries:** Spring Modulith 2.2, verified by `ModularityTest`. Each direct subpackage of `com.nemaz.school` is a module. `common` is the open shared kernel and `system` holds the technical platform.
- **Outbox (step 5):** Spring Modulith's event publication registry (`event_publication`, migration V0_02) replaces a hand-written `outbox_events` table. Listeners use `@ApplicationModuleListener` and must be idempotent, because `EventPublicationMaintenance` redelivers failed events.
- **Scheduled jobs:** `@Scheduled` + ShedLock (`shedlock` table). A job framework (JobRunr or db-scheduler) is added only if a later milestone needs durable one-off jobs. Redis fan-out arrives with the WebSocket gateway in M2.
- **OpenAPI:** springdoc 3.2. `OpenApiSpecTest` fails when `packages/api-client/openapi/openapi.json` drifts. Refresh the snapshot with `npm run openapi:export -w school-backend`, then run `npm run codegen`.
- **Moved to M1,** because these need an authenticated user and their first tables:
  - `Idempotency-Key` handling
  - `TenantContext`
  - the JPA base entity (UUIDv7 via Hibernate `@UuidGenerator(style = VERSION_7)`, `@Version`, timestamps)
- **Error tracking:** Sentry or an equivalent is set up in M0.7 together with the hosting accounts.
- **Local ports:** API 8180, web 3100, chosen to avoid clashing with other local projects.

### M0.4 Web skeleton (`apps/web`, Next.js 16)
1. Replace the starter page. Set up the App Router route groups:
   - `(marketing)`
   - `(auth)`
   - `(app)/[school]`, for teacher and admin
   - `(platform)`, for platform admin
2. Install **shadcn/ui** (§97) on Tailwind 4 and wire in `design-tokens`. Load the Noto Sans Ethiopic and Latin fonts.
3. **BFF auth pattern:** Next route handlers keep the access and refresh tokens in `httpOnly`, `Secure`, `SameSite=Lax` cookies. The browser never sees the tokens.
4. Set up i18n routing with the `packages/i18n` catalogs (next-intl or an equivalent that supports Next 16), RTL-safe layout primitives, and an Ethiopian calendar display toggle.
5. Add TanStack Query with the generated client, an error boundary, and toast and empty-state components.

**As built (24 Nov 2026):**
- **Locale in the URL path** (`/en/…`, `/am/…`) under a single root layout `app/[locale]/layout.tsx`, using next-intl 4 with `next/root-params`. This is the only setup Next 16 can prerender under `cacheComponents`; a cookie-only locale would make every page dynamic. `proxy.ts` (Next 16's renamed middleware) negotiates the locale, then runs an optimistic auth gate for `/{locale}/s/**` and `/{locale}/platform/**`. The API remains the authority.
- **School workspace:** lives at `/{locale}/s/[school]`, so school slugs can never collide with pages such as `/sign-in`.
- **BFF:** `app/api/bff/[...path]` forwards only `api/v1/*`, with header allowlists in both directions and the bearer token taken from the httpOnly `sch_at` cookie. Token issuance and refresh arrive in M1, which should also add an Origin check on mutating BFF requests (defense in depth beyond `SameSite=Lax`); done in M1c.
- **UI kit:** shadcn/ui (Radix, new-york) styled by `@school/design-tokens`. The theme toggle is hand-rolled (next-themes has open `cacheComponents` bugs). A calendar-display toggle switches between Gregorian and Ethiopian dates.
- **Status page:** `/{locale}/status` calls the API from the browser through the BFF and shows clock skew.
- **Local setup:** the web app runs on port 3100 and reads the API origin from `API_ORIGIN` (default `http://localhost:8180` outside production).

### M0.5 Design system & brand (designer, parallel)
1. Build a token set inspired by the benchmark: bright primary, soft secondary palette, 12–20 px rounded radii, generous touch targets of 44 px or more, playful but legible typography in both Ethiopic and Latin scripts.
2. Create **original** student avatar characters and an avatar builder (`student_profiles.avatar_config`).
3. Design key screens in Figma first:
   - teacher class grid and award sheet
   - parent home with a child switcher
   - class story card
   - messaging with a Translate toggle
   - attendance roll
   - school-admin dashboard
4. Build a component library in web (shadcn-based) and mobile with matching names: Avatar, PointBubble, SkillTile, StoryCard, ChildSwitcher, EmptyState and RoleTabBar.

### M0.6 Mobile skeleton (`apps/mobile`, Expo SDK 58)
1. Remove the template screens. Create Expo Router groups `(auth)`, `(teacher)`, `(guardian)`, `(student)` and later `(tutor)`. A role switcher handles users with several roles (§6).
2. Install the needed packages with `npx expo install`:
   - secure token storage (`expo-secure-store`)
   - local database (`expo-sqlite`, for the offline store, M1.7)
   - `expo-notifications`
   - `expo-image-picker`
   - `expo-camera` (QR login)
   - `expo-localization`
3. Wire up the generated client, TanStack Query (with persistence for low-data), design tokens and i18n.
4. Set up the EAS project with `development`, `preview` and `production` profiles. Native modules need a development build.

**As built (24 Nov 2026):**
- **Platforms:** iOS and Android only. The Expo web target is removed; the Next.js app is the web client.
- **Routing:** Expo Router with `(auth)`, `(teacher)`, `(guardian)` and `(student)` groups, using `NativeTabs`. Access is guarded with `Stack.Protected`, and a role switcher handles users with several roles. A dev-only role previewer (`/dev`, `__DEV__` only) shows the role shells before M1 sign-in exists.
- **Session:** the refresh token is stored in `expo-secure-store` and the access token is held in memory only.
- **i18n:** `use-intl`, the core of next-intl, so web and mobile share the ICU engine and the `@school/i18n` catalogs. The initial locale comes from the saved preference, then `expo-localization`.
- **Look and feel:** a design-token theme with light/dark/system modes. Nunito and Noto Sans Ethiopic are embedded with the expo-font plugin. React Native can't stack font families, so Amharic UI uses Noto Sans Ethiopic.
- **Data:** TanStack Query persisted to `expo-sqlite/kv-store` with offline-first defaults.
- **Offline store foundation:** an `expo-sqlite` migration runner, a `sync_outbox` table mirroring the backend `sync_operations` ledger, and a per-install device id.
- **API address:** taken from `EXPO_PUBLIC_API_URL`. In development it is derived from the Metro host, so phones on the same Wi-Fi reach the local backend.
- **Builds:** `eas.json` defines development, preview and production profiles. EAS is not linked to an account yet.
- **Verification:**
  - checked in the iOS simulator against the live backend (screenshots reviewed)
  - Android bundles but has not been run, because there is no Android SDK on the dev machine
  - camera QR scanning is untested, because the simulator has no camera
- **Known local issue:** a checkout under an iCloud-synced folder (Desktop or Documents) breaks the iOS code signing step, because iCloud adds `com.apple.FinderInfo` extended attributes to files. Keep the repo outside iCloud folders.

### M0.7 CI & container images
1. Set up GitHub Actions:
   - **backend:** JDK 25, `mvnw verify`, Testcontainers
   - **web:** lint, typecheck, build
   - **mobile:** lint, `tsc --noEmit`, `expo-doctor`
   - **contract:** an OpenAPI drift check
   - **migrations:** apply to an empty database, then to the previous release
2. Build container images for the backend and web, and publish them to GHCR from `main` and version tags.
3. Protect `main`: merging requires a PR and green `js`, `backend` and `migrations` checks.

**As built (24 Nov 2026):**
- **`ci.yml` jobs:**
  - `js`: turbo lint/typecheck/test/build, `codegen:check` and `expo-doctor`
  - `backend`: `./mvnw verify` with Testcontainers; it also covers the OpenAPI drift check through `OpenApiSpecTest`
  - `migrations`: applied migrations stay immutable and new ones are in order, enforced by `scripts/ci/check-migrations-immutable.sh`. Migrations are also applied to an empty database in every backend test run.
- **`images.yml`:** builds both images on PRs and publishes them to public GHCR packages on `main` and on `v*.*.*` tags.
- **Dependabot** covers npm, maven, actions and docker.
- **Deployment environments moved to M1.5** (decided 24 Nov 2026): hosting isn't needed until there is something to show and test outside a laptop.

**Phase 0 exit:** CI is green on `main`. Web and mobile apps call the API through the generated client. Images are published. The staging deployment follows in M1.5.

---

## Phase 1 — School & family MVP (≈ 7–9 months)

Scope: §112. Milestones M1 and M2 are the platform core and come first. From M3 onward, backend, web and mobile work on each slice in parallel.

### M1 Platform core (≈ 4 weeks)
Tables: DB §4.1–4.3, §4.19, settings and features in §4.2, plans in §4.20.

1. **Reference data migration and seed:**
   - currencies, languages and calendar systems
   - Ethiopia (ET) enabled and Kenya (KE) as a draft
   - Ethiopian administrative levels 1–2
   - notification types, roles and permissions
   - setting and feature definitions
   - subscription plans
2. **Tenancy:** organizations, schools, campuses and school languages. Platform-admin APIs to create a school and assign a plan manually.
3. **Settings and feature-flag resolver:**
   - Resolution order: school → organization → country → platform → default.
   - Values are validated against `value_schema`.
   - Entitlement check: a feature is on only if the plan includes it and the flag enables it (R-33).
   - Results are cached in Redis and invalidated on change.
4. **Identity:**
   - users and password credentials (argon2id)
   - phone OTP via an SMS provider abstraction, with a console provider in dev
   - email verification
   - federated sign-in (Google and Apple) behind a flag
   - lockout and rate limits (Redis)
5. **Tokens:**
   - access JWT valid for about 15 minutes
   - rotating refresh tokens stored as hashes in `auth_sessions`, with reuse detection by token family
   - device sign-out and sign-out everywhere
6. **Authorization module:**
   - a `can(user, action, resource)` API, plus SQL predicate builders for list queries
   - relationship resolvers: class membership, guardianship, role scope (DB §5)
   - deny by default
   - `@PreAuthorize`-style annotations that delegate to policies
   - a reusable **authorization test harness** that builds a fixture school with every persona
7. **Audit:** an `audit_logs` partitioned table with monthly partition creation, a write API, and logging of sensitive reads (e.g. `student.view`, `export`).
8. **Web:** sign-in (phone OTP or password), school picker, platform-admin console (countries, schools, plan assignment, feature flags).
9. **Mobile:** sign-in screens, role routing, session persistence.

**Exit:** a platform admin creates a school, and a school admin signs in on web and mobile. The authorization harness runs in CI.

**As built: M1a platform data & configuration (24 Nov 2026).** Steps 1-3 and the JPA base entity; identity, tokens, authorization, audit and the web/mobile screens (steps 4-9) follow in M1b.
- **Migrations:** `V1_01__reference` (DB §4.1: currencies, languages, calendar systems, countries, country languages/calendars, administrative areas, addresses), `V1_02__tenancy` (organizations, schools, school languages, campuses, plus `holidays`, which references schools), `V1_03__configuration` (`system_settings`, setting and feature definitions, settings, feature overrides), `V1_04__subscriptions` (plans, prices, entitlements, subscriptions). Seed data is repeatable: `R__10_reference_data` (ON CONFLICT DO NOTHING: operator edits survive), `R__20_definitions` and `R__30_subscription_plans` (definitions and plan entitlements are code-owned, ON CONFLICT DO UPDATE; plans and prices DO NOTHING).
- **Seed content:** ET enabled and KE draft; 14 Ethiopian level-1 regions (zones and woredas are left to a data import); 17 feature definitions (the 15 features plus two capacity entitlements, `max_students` and `storage_bytes`, so that `plan_entitlements.limit_value` has a feature key); 20 setting definitions; five school plans with placeholder ETB prices of 0.
- **Deferred columns and FKs (omitted or plain `uuid` for now):** `updated_by`, `assigned_by` (`settings`, `feature_overrides`, `system_settings`, `subscriptions`): plain uuid, FK to `users` in M1b. `organizations.logo_media_id`, `schools.logo_media_id`, `schools.cover_media_id`: added with the media tables in M2. `schools.education_system_id`: P2 (M16). `subscriptions.tutor_id` and the TUTOR arm of its scope CHECK: P4 (M25).
- **Modules:** `reference`, `tenancy`, `subscriptions`, each with `api`/`application`/`domain`/`infrastructure`. Cross-module use goes through top-level interfaces and events only: `reference.ReferenceData`, `subscriptions.SubscriptionService`, `tenancy.SettingsService` / `FeatureService`. `common.persistence` holds `IdentifiedEntity` (UUIDv7 id, identity equals/hashCode) and `BaseEntity` (`@Version`, `createdAt`/`updatedAt` through Spring Data auditing with the injected `Clock`). Entities without `std` columns (settings, overrides, plans) extend `IdentifiedEntity`; code-keyed reference tables extend neither.
- **jsonb:** Hibernate 7.4 selects its Jackson 3 `FormatMapper` automatically (only `tools.jackson` is on the classpath), so `@JdbcTypeCode(SqlTypes.JSON)` with `Map<String,String>` or `JsonNode` stores real jsonb values, including JSON `null`. Proved by `JsonbMappingTest`; no custom mapper. `char(n)`, `citext`, `jsonb` and `text[]` columns declare a `columnDefinition` so `ddl-auto: validate` passes.
- **Resolution:** `SchoolConfigurationLoader` is the single implementation of R-33 (school, organization, country, platform, definition default; feature = flag AND plan entitlement; SCHOOL_FREE when no live subscription, where live means TRIALING or ACTIVE and the period covers now). `ValueSchemaValidator` implements the JSON Schema subset the definitions use (type with unions, enum, minimum/maximum, minLength/maxLength/pattern, minItems/maxItems/items, properties/required/additionalProperties:false); unknown keywords are rejected rather than ignored.
- **Cache:** Spring Cache on Redis, cache `school-configuration` keyed by school id, stored as JSON, TTL 5 minutes. Writes publish `ConfigurationChanged` / `SubscriptionAssigned`; a `@TransactionalEventListener` (after commit) evicts the school (school scope), the organization's schools (organization scope) or the whole cache by SCAN (country and platform scope). Redis errors are logged and fall through to the database. **Spring Data Redis 4.2 completes cache writes after the call returns unless `immediateWrites()` is set** (a read right after an eviction returned the old value about 40% of the time); `CacheConfig` sets it and `RedisCacheWritesTest` guards it. `REDIS_URL` is the deployment variable (default localhost:6379; local profile uses :6390).
- **API:** `/api/v1/reference/*` for any signed-in user; `/api/v1/platform/*` (organizations, schools, campuses, subscription assignment, feature overrides, settings, resolved school configuration) guarded by `@PreAuthorize` with permission authorities from `common.security.Permissions` (`platform.organizations.manage`, `platform.schools.manage`, `platform.subscriptions.manage`, `platform.features.manage`, `platform.settings.manage`). Roles, country/organization-scoped admins and the real principal arrive in M1b; `CurrentActor` uses the authentication name as `updated_by` when it is a UUID. Bounded lists (countries, languages, override lists per scope) are plain arrays; unbounded lists use keyset pagination (`items`, `nextCursor`) over the UUIDv7 id. Validation messages are `validation.*` keys that still need entries in `packages/i18n` (`slug`, `tooLong`, `invalid`, `color`; `required`, `email`, `phone` exist).
- **Deviations from the design doc:** see the inline notes in DB §4.1, §4.2 and §4.20 (campus status values, capacity feature keys, partial unique indexes for live subscriptions, the subscription scope CHECK).

**As built: M1b identity, tokens, authorization, audit (24 Nov 2026, backend only).** Steps 4-7; web and mobile screens (steps 8-9) follow in M1c.
- **Migrations:** `V1_05__identity` (users, password_credentials, federated_identities, otp_challenges, auth_sessions), `V1_06__authorization` (roles, permissions, role_permissions, role_assignments with the scope CHECK and partial unique index of DB §4.3, plus the `users(id)` FKs deferred by M1a), `V1_07__audit` (`audit_logs` partitioned by month on `occurred_at`, current month + 3 created by a DO block, a default partition as safety net), `V1_08__idempotency`. `R__40_roles_permissions` is code-owned (ON CONFLICT DO UPDATE; system roles hold exactly the declared permissions). `student_login_codes` waits for students and classes (M3); `users.avatar_media_id` for media (M2).
- **Modules:** `identity` (sign-in, tokens, sessions, `/me`, bootstrap admin), `authorization` (roles, assignments, the policy, `@authz`), `audit` (`AuditLog` API, query endpoint, partition job). `common.security` holds the vocabulary every module uses: `CurrentUser` (replaces M1a's `CurrentActor`), `AccessScope`, `RoleGrant`, `Permissions`, and the `AccessControl` interface that authorization implements. Authorization depends on tenancy's public `TenantDirectory` to resolve coverage (country → organizations and schools, organization → schools, school → campuses); tenancy only uses the kernel interface (and `@authz` in SpEL), so there is no cycle. `system` adds `RateLimiter` (Redis fixed windows), `SmsSender`/`EmailSender` and Idempotency-Key handling (`@Idempotent`, filter + interceptor).
- **Sign-in:** phone-first OTP (6 digits, HMAC-SHA256 with a pepper, 5 minutes, 5 attempts, single use, a new code ends older ones, same 202 whether or not an account exists, delivery after commit on another thread) and password (argon2id, 5 failures lock for 15 minutes, uniform `auth.invalid_credentials`, dummy hash for unknown users). Rate limits: OTP request 5/h per destination and 30/h per IP, verify 10/15 min per destination, password 10/15 min per identifier and 50/15 min per IP. Client IP is `getRemoteAddr()`; forwarded headers only via `server.forward-headers-strategy` behind a trusted proxy. Added `PUT /api/v1/me/password` (change needs the current password; a first password needs a sign-in in the last 10 minutes; other sessions are signed out) so password sign-in is reachable.
- **Tokens:** ES256 access JWTs (type `at+jwt`, 15 min, `sub`/`sid`/`iat`/`exp`/`iss`/`aud`/`jti`, no roles), issued with `NimbusJwtEncoder` and validated by `NimbusJwtDecoder` from the same JWK set (`SCHOOL_AUTH_JWT_KEYS`, a JWK Set document: the first key signs; ephemeral key only in local/test). Refresh tokens: 256 random bits, SHA-256 in `auth_sessions`, 30-day absolute and 14-day idle expiry, rotation under a row lock; a rotated token presented again revokes the family (REUSE_DETECTED) and deny-lists it in Redis, so its access tokens stop at once. Logout / logout-all deny-list too. Inactive accounts are rejected per request (status cached ≤ 60 s, evicted on change).
- **Authorization:** grants are cached per user in Redis and evicted after every assignment change. M1a endpoints are scope-aware: country admins manage their country's organizations, schools and settings; organization admins their schools; school admins read their school, manage its campuses and school settings; campus admins read their school; feature overrides, plans and the audit log stay platform-only. School and organization lists are filtered in SQL. Granting a role needs `platform.roles.manage` and every permission of the role at a scope covering the target (no escalation); the last platform admin cannot be revoked. Added permission `school.read` (read-mostly campus admins, support agents).
- **Audit:** sign-in (requested, verified, failed, lockout), refresh reuse, logout(-all), bootstrap admin, role grant/revoke, profile and password changes, and every M1a mutation (organization, school, campus, subscription, feature override, setting). Phones and emails are masked; codes, passwords and tokens are never written. One database role means UPDATE/DELETE cannot be revoked from the application yet: no code path updates or deletes audit rows; a separate owner role is a staging (M1.5) task.
- **Tests:** `support.Personas` (fixture ET org with schools A/B, KE school, campus A; platform, country ET/KE, organization, school A/B, campus A admins, plain and suspended users; real tokens) and the data-driven `AuthorizationMatrixTest` (every platform endpoint × persona × anonymous).
- **Deferred:** federated sign-in (Google/Apple, needs client ids; table exists), a real SMS provider (startup requires `SCHOOL_SMS_PROVIDER`), email verification flows, user invitation/provisioning (minimal `POST /platform/users` arrived with M1c; full invitations M3/M5), the web BFF Origin check (done in M1c).

**As built: M1c web sign-in and platform console (24 Nov 2026).** Step 8 (the mobile half of M1c is recorded separately); small backend additions for it.
- **Backend additions:** `POST /api/v1/platform/users` (`@Idempotent`; ACTIVE STANDARD user whose phone or email counts as verified, 409 `user.phone_taken` / `user.email_taken`; audit `user.create`), `GET /api/v1/platform/users/lookup?phone=|email=` (exact match only, 30 lookups per administrator per 15 minutes, masked destination) and `GET /api/v1/platform/users/{id}`; all need `platform.roles.manage` at any scope (what the user can then do is still decided by scope-checked role assignments). `GET /api/v1/schools/by-slug/{slug}` (`school.read` on the school; unknown and forbidden are the same 404). `GET /me` grants carry `schoolSlug` and `schoolName` for SCHOOL and CAMPUS scopes. Console catalogs: `GET /api/v1/platform/definitions` (feature and setting definitions with value schemas, for the override editors) and `GET /api/v1/platform/plans`. No migrations. `AuthorizationMatrixTest` gained a per-case "denied status" (404 for by-slug).
- **Sessions (web):** tokens only ever live in httpOnly cookies (`sch_at` 15 min, `sch_rt` 14 days, `Secure` in production, `SameSite=Lax`). Sign-in goes through Route Handlers, not Server Actions: `POST /api/auth/otp/request`, `/api/auth/otp/verify` (sets the cookies, answers `{ok: true}`), `/api/auth/logout` (always 204) and `GET /api/auth/expired` (Server Components can't clear cookies, so an invalid session redirects here). Every mutating request to `/api/auth/*` and `/api/bff/*` passes `lib/origin.ts`: `Origin` must equal the app's own origin (`APP_ORIGIN` overrides the request URL's origin behind a proxy), without `Origin` the request needs `Sec-Fetch-Site: same-origin`, otherwise 403 `bff_forbidden_origin`.
- **Silent refresh:** `proxy.ts` decides with `decideRefresh` (refresh token present and the access token missing, unreadable or within 60 s of its `exp`, read without verifying) and calls `/auth/refresh` before any Server Component runs. It updates the request's cookies (so the same render sees the new pair) and sets them on the response. A 4xx from the API clears both cookies and redirects to sign-in with `?next=`; 5xx and network errors let the request through. The BFF route handler refreshes proactively when `sch_at` is gone and once more after an upstream 401, then retries the request. `lib/server/refresh.ts` shares one in-flight refresh per refresh token and replays a finished result for 5 s, because the API's rotation treats a second use of an old token as theft. **Caveat:** the store is per Node process (kept on `globalThis` so the proxy and handler bundles share it); with several web instances two requests of one browser can still race and revoke the session. Use sticky routing, or give the API a short grace window for the previous token, before scaling out. The replay window also means a rotated token presented within 5 s gets the new pair instead of tripping reuse detection.
- **Enforcement:** `proxy.ts` stays an optimistic cookie gate. The `(app)` and `(platform)` layouts call `requireSession()` (`lib/server/dal.ts`, `GET /me`, memoized with `cache`, behind `<Suspense>` and `connection()` so per-user data never enters a prerender); a 401 redirects through `/api/auth/expired`, an unreachable API throws to the error boundary. `/s/{slug}` resolves the school through by-slug and renders the not-found page for unknown or forbidden schools; `/platform` is a 404 for users without a platform, country or organization role. Because the shell has already streamed, those not-found pages are served with status 200.
- **Screens:** sign-in (phone, then a 6-digit code with `one-time-code`, a 30 s resend cooldown, a countdown from `expiresIn`, `Retry-After` handling), then `resolvePostSignInPath`: a safe localized `next`, else the console for platform, country or organization roles, else the single school, else `/schools` (picker), else `/no-access`. Platform console `/{locale}/platform`: Schools (filters, load more, create with country-derived defaults and a live slug hint, detail tabs Overview with status changes, Campuses, Subscription, Configuration (read-only, value and source), Administrators (look up by phone, create the user if missing, assign SCHOOL_ADMIN, revoke)), Organizations, Feature flags (inherit / on / off per scope), Settings (editors chosen from the definition's schema: switch, number, enum select, text, JSON), Audit log (filters, load more, details). Sections appear according to the permissions in `/me`; the API still authorizes every call. Data comes from TanStack Query through the BFF; creates send an `Idempotency-Key` that stays the same while a failed submission is retried.
- **Not done / follow-ups:** school and country pickers in the console load the first 100 rows (no search yet); the admin list shows names through one `GET /platform/users/{id}` per row; Amharic text needs native review; password sign-in and email codes have no web screens yet.

**As built: M1c mobile sign-in and session (24 Nov 2026).** Step 9.
- **Sign-in:** phone → `(auth)/verify` (6-digit `oneTimeCode` input, auto-submit, expiry countdown, 30 s resend, `Retry-After` countdown for `auth.rate_limited`) → tokens stored (refresh in `expo-secure-store`, access in memory) → `GET /me` → signed in.
- **Roles:** `resolveSessionFromMe` (pure, tested) maps `/me` grants to mobile roles. `MOBILE_ROLE_BY_ROLE_CODE` is empty until teacher/guardian/student memberships exist (M3/M5), so administrators land on `(account)/no-app-role` ("your tools are on the web"). The dev role previewer still works in development builds.
- **Session restore:** at launch, refresh → `/me` before the splash hides. A rejected refresh clears tokens and shows a notice. A network failure keeps the token and shows an offline "try again" screen.
- **Refresh:** single-flight (`lib/auth/single-flight.ts`), so concurrent 401s or near-expiry calls trigger exactly one rotation; this matters because a second use of a rotated token revokes the whole session. Proactive refresh within 60 s of expiry; on a 401 refresh once and retry once; terminal codes (`auth.session_revoked`, `auth.account_inactive`) sign out locally with a banner. The refresh call uses a token-less client so it can't recurse.
- **Verified** in the iOS simulator against the real backend (sign-in, wrong code, restore after relaunch, forced sign-out, offline retry, rate limit, sign-out). Proactive refresh and 401 retry are unit-tested only (tokens live 15 min); verify once on a device, ideally with a shortened token lifetime. Android is bundled but not run.
- **Known edge:** if a refresh response is lost in transit after the server rotated the token, the next refresh reports `auth.session_revoked` and the user signs in again; inherent to rotation without a grace window (same consideration as the web caveat above).

### M1.5 Staging environment (≈ 1–2 weeks, moved from M0.7)
Do this once sign-in works (end of M1), so there is something worth showing and real-environment issues surface early: HTTPS, `Secure` cookies, CORS and domains.

Prerequisites (owner decisions):
- hosting provider
- region, checked with counsel against Ethiopia's Personal Data Protection Proclamation No. 1321/2024 before any real personal data is stored
- domain name

Steps:
1. Create the `staging` environment, then repeat it for `prod` before the pilot (M15):
   - managed Postgres 18
   - container hosting for the backend and web images from GHCR
   - Redis
   - Cloudflare in front (DNS, TLS, CDN, WAF)
   - Cloudflare R2 for media
   - secrets in the platform's secret store or GitHub environments
2. Deploy pipeline: `main` deploys to staging automatically; a `v*.*.*` tag deploys to prod, gated by GitHub environment approval. Migrations run once per deploy, before the new version takes traffic.
3. Mobile: EAS build profiles and OTA update channels pointing at staging.
4. Error tracking (Sentry or an equivalent), uptime checks, log shipping, plus the OTLP endpoints for traces and metrics.
5. A deployed Spring profile (`staging`/`prod`) that fails fast at startup when required environment variables are missing: `DB_*`, `REDIS_URL`, `CORS_ALLOWED_ORIGINS`. Today a missing `REDIS_URL` silently falls back to `localhost:6379`.
6. Nightly backups, a restore drill and a runbook.

**Exit:** staging URL is live, deploys are automatic from `main`, and a restore drill has been documented.

**As built: deployment readiness (25 Nov 2026, no hosting account, domain or credentials yet).** Everything that does not need an account is done and tested; going live is the owner's checklist in `docs/ops/Runbook.md` and `docs/ops/Hosting_Options.md`.
- **Fail-fast profiles:** `staging` and `prod` (profile group `deployed`) run `system.startup.DeploymentConfigValidator` as an `EnvironmentPostProcessor` before any bean exists and stop with one message listing every problem (database, `REDIS_URL` (no localhost fallback in any profile but local/test), https-only CORS, JWK keys, pepper, no console SMS in prod / `log` push in prod, https invitation URL, storage endpoints and credentials, no development credentials, dev admin bootstrap refused). Secrets are never printed. Unit tests per rule plus `DeployedProfileStartupTest` (boots the real application). Consequence: **`prod` cannot start until a real SMS provider exists** (only `console` is implemented).
- **Migrations once per deploy:** the image has a `--migrate` mode (`system.DatabaseMigrator`: Flyway only, exit 0/1/2, safe to run concurrently thanks to Flyway's lock); `staging`/`prod` start with `school.migrations.mode=validate` (the app only verifies the schema and refuses to start otherwise). Migrations must stay backward-compatible with the previous release because rollback re-starts old images on the new schema.
- **Health and version:** `/actuator/health/liveness` and `/readiness` (database and Redis), status only; `/actuator/info` with build version and `app.commit` (image build argument `GIT_SHA`, passed by `images.yml`). **The web app has no health route** (the kit probes `/en/sign-in`).
- **R2:** `chunkedEncodingEnabled(false)` on the server-side S3 client; `scripts/ops/r2-smoke.mjs` implements the nine-step smoke test (run against SeaweedFS only). Finding: the lifecycle rule for stale `PENDING_UPLOAD` objects in R2_Compatibility.md is **unsafe with the current key layout** (pending and ready originals share a key shape); `MediaMaintenance` already deletes stale uploads, so skip the rule until pending uploads get their own prefix.
- **Deploy kit and pipelines:** `infra/deploy/` (compose, Caddy, `.env.example`, `deploy.sh` with pinned images, migrate-first, smoke test, automatic rollback), `deploy.yml` (reusable), `deploy-staging.yml`, `deploy-prod.yml` (tag, reviewers, digests), `backup.yml`; all skip with a notice until `DEPLOY_*_ENABLED` variables and environment secrets exist.
- **Backups:** `scripts/ops/backup-postgres.sh` (age/gpg encrypted stream, upload, retention) and `restore-drill.sh` (scratch database, integrity checks); drill result in the Runbook.
- **Observability:** ECS JSON logs in deployed profiles; OTLP stays opt-in; Sentry (`sentry-spring-boot-4-starter`) is off unless `SENTRY_DSN` is set and sends no request data, users, breadcrumbs or personal data. Mobile: `eas.json` gained `staging` and channels; the owner still has to run `eas init`.

### M2 Media, notifications & real-time infrastructure (≈ 3 weeks, parallel with late M1)
Tables: DB §4.9 and §4.13.

1. **Media:**
   - signed upload URL (`PENDING_UPLOAD`), then client upload straight to R2 (SeaweedFS locally), then a completion callback
   - the job checks MIME type and size, then makes variants: image thumbnails, compressed images, video poster plus a low-bitrate rendition, PDF preview
   - object keys come from `purpose`
2. **Signed read URLs:** issued only through the owning object's authorization (R-30), and short-lived.
3. **Notification service:**
   - one `notify(type, recipients, payload)` API
   - writes the in-app `notifications` row, then fans out to channels by preference
   - quiet hours, with URGENT bypass only for permitted types
   - delivery tracking in `notification_deliveries`
4. **Push:** `push_devices` registration from mobile, and FCM HTTP v1 for Android and iOS (via APNs). A provider abstraction allows swapping later.
5. **Real-time gateway:**
   - WebSocket endpoint (STOMP), authenticated with the access token
   - per-user queues
   - Redis pub/sub so it works across several instances
   - used for messages, typing and live notification badges (§92)
6. **Web and mobile:** notification inbox, unread badges, preferences screen, push permission flow.

**As built: M2a media pipeline (24 Nov 2026).** Steps 1-2 (media and signed reads); notifications, push and the real-time gateway (steps 3-6) are M2b, below.
- **Migration:** `V2_01__media` (`media`, `media_variants`, plus `users.avatar_media_id`, `schools.logo_media_id`, `schools.cover_media_id`, `organizations.logo_media_id`, all `ON DELETE SET NULL`). Deviations from DB §4.9: `media.context_type`/`context_id` (what the media belongs to: USER, SCHOOL or ORGANIZATION so far), `failure_reason`, and `storage_provider` defaults to `S3`.
- **Module `media`:** `ObjectStorage` (domain port) with an AWS SDK v2 S3 implementation (path-style, custom endpoint; `school.storage.*`, deployed values have no defaults; `public-endpoint` is the host baked into presigned URLs). Presigned PUT: 10 minutes, `Content-Type` and `Content-Length` signed (SeaweedFS refuses a different type or size, which the tests prove); presigned GET: 5 minutes with `response-content-disposition`. Request checksum calculation is `WHEN_REQUIRED` (R2 and SeaweedFS reject the SDK's default CRC trailers). The local profile (and tests) create the bucket and its CORS rule at startup (`school.storage.create-bucket`); SeaweedFS honours `PutBucketCors` including preflight, so browsers upload directly.
- **Flow:** `POST /media/uploads` (`@Idempotent`; 403 `media.purpose_not_supported` until a purpose has a policy, then type/size/context rules from the `PurposeRules` table; 120 requests per user per hour) creates `PENDING_UPLOAD` with the key `users/{id}/avatar/{mediaId}/original` or `schools|organizations/{id}/branding/{mediaId}/original`; the client PUTs; `POST /media/{id}/complete` (uploader only, idempotent) compares HEAD size and content type with the declaration and sniffs the first 4 KB (JPEG, PNG, WebP, GIF, PDF, MP4, MOV, WebM, ZIP/XLSX, plain text as CSV). Any mismatch deletes the object, sets `QUARANTINED` and answers 400 `media.type_mismatch` / `media.size_mismatch`. Otherwise `UPLOADED` and a `MediaUploaded` event; `@ApplicationModuleListener` processing is idempotent (claims UPLOADED or a stuck PROCESSING, short transactions around the work, variants written under fixed keys). Permanent content problems end in `FAILED` with a reason; storage errors propagate so the outbox redelivers (there is no attempt counter yet: a poison event is retried every minute until it is purged). A declared SHA-256 is verified while processing (images and PDFs); videos are not read in full. `MediaMaintenance` (ShedLock, hourly) removes `PENDING_UPLOAD` rows older than 24 h with their objects.
- **Processing:** images are decoded with ImageIO (JDK plus TwelveMonkeys for robust JPEG and WebP reading; the decode is sub-sampled to at most 2x the largest rendition and a header check rejects images over 64 MP or 20000 px before decoding), turned upright by their EXIF orientation (own 40-line parser), flattened onto white, and re-encoded as JPEG (quality 0.82) THUMB 256 / SMALL 640 / MEDIUM 1280 px, never enlarged. JPEG, not WebP: there is no pure-Java WebP writer (TwelveMonkeys reads only). Because renditions are written from pixels, no EXIF, GPS, XMP or comment survives; the tests build a JPEG with GPS and camera make and assert none of it remains in any rendition. PDFs get a page count and a JPEG preview of page 1 (PDFBox 3.0.8, longest side 1280 px). Videos are READY as uploaded: no transcoding or poster until the ffmpeg worker of M8. Scaling is plain Java2D (progressive halving, bicubic last step): Thumbnailator was not needed.
- **Reads:** `GET /media/{id}` (uploader and managers in any state, others only READY media their policy lets them read, else 404), `GET /media/{id}/url?variant=` (409 `media.not_ready`; images need THUMB/SMALL/MEDIUM, ORIGINAL or no variant is 400, so no signed link to an image original is ever issued; PDFs and videos default to the original; `Cache-Control: private, max-age=240`, a minute under the link's life instead of the planned 300), `DELETE /media/{id}` (uploader or manager of the context; soft delete, `MediaDeleted` clears references and removes objects), `GET /media/policies` (purposes with a policy and their limits).
- **R-30 in code:** `MediaPurposePolicy` (`canCreate`, `canRead`, `canManage`, `auditReads`) is registered by the owning module; `media` depends on no business module. `AVATAR` (identity): created only by the user for themself; read by the user, any platform-scope role holder, anyone with a role in a school where the user also has one, and anyone who can `school.read` such a school. Memberships (M3/M5) will widen "same school" to classmates and families. `BRANDING` (tenancy): created with `school.manage` on the school or `platform.organizations.manage` on the organization (and the target must exist, so unknown ids look like 403), read by any signed-in user. The remaining purposes of the DB list are in the enum and in `PurposeRules` (limits: images 10 MB, PDFs 25 MB, videos 100 MB) without policies.
- **First uses:** `PUT/DELETE /me/avatar`, `PUT/DELETE /platform/schools/{id}/logo|cover`, `PUT/DELETE /platform/organizations/{id}/logo`; they need a READY upload of the right purpose and context (`requireAttachable`), delete the replaced file and write `identity.avatar.*`, `school.logo|cover.*`, `organization.logo.*` audit rows. `/me`, school and organization views and the by-slug lookup embed a `MediaSummary` (`avatar`, `logo`, `cover`) while the media is READY.
- **Web:** `lib/media/*` (limits from `GET /media/policies`, upload state machine, XHR upload with progress, 4-minute signed-URL cache keyed per media and variant), `<MediaImage>` and `<ImageUploader>`, `/{locale}/account` (name and avatar, linked from the user menu), logo and cover uploads in the school Overview tab. Direct browser PUT is the default (`NEXT_PUBLIC_UPLOAD_MODE=direct`); `proxy` streams through `PUT /api/bff/uploads/{mediaId}`, which only forwards to an allowed storage origin (`UPLOAD_PROXY_ORIGINS`) and only for that media's own object key.
- **Mobile:** Settings gains a profile photo section (camera or library through `expo-image-picker`, converted to JPEG and shrunk to 2048 px with `expo-image-manipulator`, uploaded with `expo-file-system`'s `File.upload`), `<Avatar>` and `useMediaUrl` (signed links are excluded from the persisted query cache). New native modules: the dev client has to be rebuilt. Physical-device uploads need `SCHOOL_STORAGE_PUBLIC_ENDPOINT` set to the LAN address and the store exposed on it (README, Media storage).
- **Not done / follow-ups:** an attempt counter for failing media events; ClamAV or another scanner (the `QUARANTINED` status and `moderation_status` are ready for it); poster frames and transcoding (M8); an "upload from an expired ticket" refresh in the clients (they ask for a new ticket); a media storage quota against `storage_bytes` entitlements; the Idempotency replay of an upload request returns the original presigned URL, which is dead after 10 minutes (clients use a new key per attempt).

**As built: M2b notifications and real-time (24 Nov 2026).** Steps 3-6.
- **Migration:** `V2_10__notifications` (the six DB §4.13 tables; deviations in the DB doc: `push_devices.session_family_id` instead of `auth_session_id`, `notification_deliveries.last_attempt_at`, partial unique indexes for idempotent fan-out; `notifications` stays unpartitioned for now) and the code-owned seed `R__50_notification_types` (15 types incl. `ROLE_GRANTED`, `TEST_NOTIFICATION`; `CONSENT_REQUESTED` is the locked one). Texts live in `messages*.properties` (`notification.<type>.label|title|body`; `NotificationTypesSeedTest` checks every type in both languages). The Amharic texts need native-speaker review.
- **`notifications` module:** `NotificationService.notify(NotificationRequest)` (type, recipients, message key + args where a `LocalizedText` argument is resolved per recipient, `data.path` validated as a relative path, school, source, NORMAL|URGENT) renders title and body in each recipient's language, inserts the rows and publishes `NotificationsCreated` in the caller's transaction. After commit (`@ApplicationModuleListener`, idempotent): (a) the item goes to the recipient's open apps through `RealtimePublisher`, (b) push fan-out: preference (stored choice, else the type default; locked types ignore opt-outs) -> `SUPPRESSED(PREFERENCE)` when pushed by default and declined; no active device -> `SUPPRESSED(NO_DEVICE)`; per device quiet hours in the user's timezone (`users.timezone`, default Africa/Addis_Ababa, window may cross midnight, start inclusive, end exclusive) -> `SUPPRESSED(QUIET_HOURS)` unless the notification is URGENT and its type `can_be_urgent` (a URGENT request for another type is stored as NORMAL); otherwise QUEUED and sent. EMAIL and SMS are not delivered and record nothing. A notification that is only in-app (type default `IN_APP`) with no opt-in writes no delivery rows.
- **Push:** `PushSender` SPI; `ExpoPushSender` (`POST /--/api/v2/push/send`, batches of 100, optional `EXPO_ACCESS_TOKEN`, 10 s timeout; per-ticket mapping: `DeviceNotRegistered` revokes that device, other errors FAILED with `last_error`; a failed batch fails only its own messages) and `LoggingPushSender` (masked token + title). `school.push.provider=expo|log` has no default in deployed profiles (startup fails; `log` is refused outside local/test unless `school.push.allow-log`). `DeliveryRetryJob` (ShedLock, every minute): FAILED retried after 1 minute, then 5, at most 3 attempts; QUEUED older than 2 minutes (interrupted fan-out) is picked up; notifications older than 24 h are not pushed any more. Not done: receipt polling (`/getReceipts`, so SENT means "accepted by Expo"), FCM/APNs/WebPush senders (devices of those providers can be registered but are FAILED `provider_unsupported` until a sender exists), digests, email/SMS channels.
- **API:** inbox `GET /notifications` (keyset on `(created_at, id)`, `unreadOnly`), `GET /notifications/unread-count`, `POST /notifications/{id}/read|archive`, `POST /notifications/read-all`, `POST /notifications/test` (platform administrators: `platform.settings.manage` at platform scope); `GET|PUT /me/notification-preferences` (types by category with localized labels; IN_APP and locked types cannot be switched off: 400 `notification.preference_locked`), `GET|PUT /me/notification-settings` (quiet hours HH:mm, both or neither; digest OFF only); `POST /me/push-devices` (upsert by token, moves a token to the current user and blocks the previous owner's pending pushes; token format validated per provider; bound to the access token's `sid`), `DELETE /me/push-devices/{id}`. Everything is the caller's own data (other ids are 404). Triggers: `RoleAssignmentGranted` (new event of `authorization`; system and self grants are silent) -> `ROLE_GRANTED`; sign-out and revocation -> `SessionsRevoked` (new event of `identity`, published by `TokenService` for logout, logout-all, password change, reuse detection and inactive accounts) revokes the push devices of those session families.
- **Module `realtime`:** STOMP over a plain WebSocket at `/ws`, in-memory broker per instance, heartbeats 10 s/10 s, frames <= 64 KB, 10 s to send CONNECT; handshake open at HTTP level, allowed origins = `school.cors.allowed-origins`. CONNECT (`StompAuthInterceptor`): exactly one of `Authorization: Bearer <access token>` (validated by `identity.SessionAuthenticator`, which reuses `PrincipalConverter`'s deny-list and account rules and the JWT decoder) or `ticket` (`POST /api/v1/realtime/tickets`: 256-bit random, only its SHA-256 in Redis `rt:ticket:<hash>` -> user and `sid`, 60 s, single use by `GETDEL`, 30 per user per minute). A connection without an `Origin` header is not a browser, so it may authenticate with a bearer token but never with a ticket (a ticket stands in for browser trust); at most 20 connections per user. Failures end in an ERROR frame carrying only a reason code, then a close. SUBSCRIBE only `/user/queue/<name>`, any SEND is refused. The principal name is the user id. Revocation: `SessionsRevoked` is re-broadcast on Redis (`rt:session-events`) so every instance closes its own sockets of those families; a local (not ShedLocked) check every 60 s closes sockets whose family is deny-listed or whose account is not ACTIVE. Fan-out: `RealtimePublisher.toUser` publishes to `rt:user-events`, every instance delivers to its local sessions with `convertAndSendToUser`; payloads `notification.created` (item + unread count), `notification.read` (ids or all, + count) and `notification.archived`. Not done: inbound messaging (M9), SockJS fallback, per-IP limits on CONNECT attempts, presence/typing.
- **Config/ops:** `SCHOOL_PUSH_PROVIDER` (required), `SCHOOL_PUSH_ALLOW_LOG`, `EXPO_ACCESS_TOKEN`; `NEXT_PUBLIC_REALTIME_URL` on the web; the load balancer must allow WebSocket upgrades and idle timeouts above 30 s. The STOMP configuration registers its own executors, which disables Boot's `applicationTaskExecutor`, so `system.scheduling.AsyncConfig` declares it again. `@IntegrationTest` now runs the server on a random port (one shared context; MockMvc is unchanged).
- **Web:** bell with unread badge in the shared header controls (so both `(app)` and the console), dropdown of the latest 10, `/notifications` (infinite list, unread filter, mark read, archive), `/account/notifications` (types by category x IN_APP/PUSH, locked types, quiet hours; linked from `/account`), the console's "Send me a test notification", and `useRealtime()` (fresh ticket per connection, `@stomp/stompjs`, backoff with jitter, refetch after a reconnect, toast on a new notification, drops the socket when the tab stays hidden for 5 minutes). Pure logic (backoff, cache reducers, deep links, relative time, realtime URL) is in `lib/notifications` with vitest tests.
- **Mobile:** notifications screen (pull to refresh, infinite scroll, tap marks read and follows `data.path` only for known routes), bell with badge on the tab screens and the no-app-role screen, Settings -> Notifications (OS permission status, push toggles per type, quiet hours), `expo-notifications` (config plugin, Android channel `default`, foreground banners, tap routing, an explanation card shown after sign-in before the OS prompt; the device is registered on sign-in/token change and deleted on sign-out before the tokens are cleared) and a STOMP client over React Native's WebSocket using the existing single-flight access-token path (reconnect on foreground and after a refresh). EAS is not linked yet, so there is no `projectId` and registration is skipped (dev-only log). New native module: the dev client must be rebuilt. Notification queries are not persisted.

### M3 School setup, people & classes (≈ 3 weeks)
Tables: DB §4.4 (without tutor) and §4.5.

1. Academic years and periods, grade levels, subjects, campuses: admin CRUD with validation (no overlapping periods; one current year).
2. **Staff:** invite a teacher by phone or email (`invitations`), accept, create `teacher_profiles` and `school_staff`, assign admin roles through `role_assignments` (CAMPUS_ADMIN and SCHOOL_STAFF included).
3. **Students:**
   - create managed accounts (`account_type = MANAGED`, R-31) and `school_students`
   - optional username and password for older students
   - **login codes:** generate, print and revoke QR and text codes per class sheet (D6)
4. **Classes:**
   - a teacher or admin creates a class (ClassDojo-style quick create: name, grade, icon)
   - add students from the school roster or create them inline
   - co-teachers and subject teachers through `class_teachers.role` and `subject_id` (R-07)
   - move students between classes, which ends one membership and starts another (§12)
5. **School-admin web:**
   - Dashboard
   - Students
   - Teachers
   - Classes
   - Academic Year
   - Settings
   - Feature Configuration (§78)
6. **Teacher mobile and web:** "My classes" list, class roster, add-students flow, student profile showing their avatar.
7. **Student mobile:** QR or text-code sign-in, home screen with their classes.

**Exit:** a pilot school's structure can be set up by hand, and students can sign in with codes.

**As built: M3a school structure, people and classes (25 Nov 2026, backend only).** Steps 1-4 and the API contract for steps 5-7 (web and mobile screens follow in M3b/M3c from `openapi.json`).
- **Migrations:** `V3_01__academic` (years, periods, grade levels, subjects), `V3_02__people` (student and teacher profiles, `school_staff`, `school_students`), `V3_03__classes` (classes, `class_subjects`, `class_teachers`, `class_students`, groups, `student_login_codes`). Deliberate omissions, each noted in the SQL: `grade_levels.academic_level_id` and `subjects.curriculum_subject_id` (P2), `guardian_profiles`/`student_guardians`/`invitations` (M5), `tutor_profiles` (P4). Additions: CHECKs on every enumeration, an exclusion constraint (`btree_gist`) that forbids overlapping periods in a year, `left_at` set exactly when a membership is no longer live. `auth_sessions.auth_method` already allowed `LOGIN_CODE`. Repeatables: four permissions (`school.academic.manage`, `school.staff.manage`, `school.students.manage`, `class.manage`), three settings (`classes.teachers_can_create`, `students.login_code.ttl_days` 1-730, `students.password_login.enabled`), notification type `STAFF_ADDED`.
- **Permissions by role:** PLATFORM_ADMIN, COUNTRY_ADMIN, ORGANIZATION_ADMIN and SCHOOL_ADMIN hold all four (granting SCHOOL_ADMIN needs every permission of the role, the no-escalation rule, so the higher administrators could not appoint school administrators without them). CAMPUS_ADMIN: `school.read` + `class.manage`, i.e. the classes, rosters and login codes of its own campus (a class without campus needs a school-wide grant); students, staff and academic years stay school-wide. SCHOOL_STAFF: `school.read`. Teachers and students hold no role assignment (R-02): they act through memberships.
- **Modules:** `academic` (structure, classes, rosters, groups, `/me/classes`) and `people` (profiles, staff, students, login codes). `people` depends on `academic`, never the reverse: academic reaches people only through its `SchoolPeople` port (implemented by people), people uses `ClassMemberships` and `ClassAccess`. `identity` stays free of both through its own ports: `UserAccounts` (create or find accounts), `AccountMemberships` (`/me`), `LoginCodeRedeemer`, `ManagedAccountPolicy`, `CodeHasher`. The shared kernel gained `SchoolMemberships` and `SchoolGate` (the 404-or-403 rule).
- **`ClassAccess` (reusable):** `canView`, `canTeach`, `canLead`, `canManage`, `membershipOf`, `accessibleClassIds` and `visibleClasses(user, alias)`, a SQL predicate that list queries embed so lists and single checks cannot disagree. View = ACTIVE student, ACTIVE teacher (any role), `class.manage` over the class's school/campus/organization/country, or a contributed `ClassAccessRule` (M5 adds the guardian rule as a bean returning a SQL fragment; rules only widen view). Deny by default; FORMER/REMOVED members have no access (history access is the content module's setting). Archived classes stay viewable.
- **Academic API (`/schools/{id}/...`):** years (CRUD, `make-current`: exactly one current, a CLOSED year cannot be current, the current year cannot be closed or deleted; closing archives nothing), periods (within the year, no overlap, unique sequence; the database enforces overlap too), grade levels, subjects (409 while classes use them, deactivate instead). Reads: any school member (staff, students, `school.read`); writes: `school.academic.manage`.
- **Classes API:** create (`class.manage`, or an ACTIVE teacher while `classes.teachers_can_create`; a teacher becomes LEAD), list (filtered in SQL to what the caller may see; keyset by name; filters year, grade, campus, status, type, `mine`, `q`), get, patch (LEAD or manager; campus and year manager only), archive/restore (roster frozen while archived, nothing ended), delete (only a class that never had students and no teacher besides its first). Roster: add students (all-or-none, must be ACTIVE in the school), remove (REMOVED, or FORMER/PROMOTED), `move` (one transaction: FORMER/TRANSFERRED_CLASS then ACTIVE in the target, history kept), teachers (LEAD, CO_TEACHER, SUBJECT_TEACHER with subject, ASSISTANT; the last LEAD cannot be removed: 409 `class.last_lead`; non-leads may only leave themselves), saved groups, `GET /me/classes`. Events `ClassCreated`, `ClassMembershipChanged`.
- **404 vs 403:** a caller who cannot see the school (no `school.read`, no ACTIVE membership) gets 404 `school.not_found`, and a class or student they cannot see gets 404, exactly like an unknown id; a member who lacks the permission gets 403.
- **Staff (instead of invitations):** `POST /schools/{id}/staff` is direct provisioning with the same vouching decision as `POST /platform/users`: the person is found by normalized phone or email or created ACTIVE with that destination verified, never duplicated; `teacher_profiles` for TEACHER; optional `adminRole` (SCHOOL_ADMIN, CAMPUS_ADMIN, SCHOOL_STAFF) through `RoleAssignments.grantWithinScope` (no escalation); `STAFF_ADDED` notification plus an SMS or email for newly created accounts. Deactivation (FORMER) ends class teaching and leaves roles alone. A user may be staff in several schools; 409 `staff.already_member`. The planned `invitations` table arrives with guardian linking (M5).
- **Students:** `POST /schools/{id}/students` creates a MANAGED account (no phone or email, the school's default language) with profile and enrollment, optionally straight into a class (`school.students.manage`, or a teacher of that class); plan limit `max_students`; `studentNumber` unique per school, `username` globally unique. Withdrawal ends memberships (LEFT_SCHOOL), revokes login codes and signs the account out when no other enrollment is live. **Privacy split:** `StudentView`/`RosterStudent` carry `accessLevel` BASIC (id, name, avatar, avatar configuration: classmates, campus administrators) or FULL (adds date of birth, gender, username, number, classes: `school.students.manage`, the student, teachers of one of their classes); the list endpoint is for managers and teachers (their own students). The student may change only their `avatarConfig`.
- **Login codes:** QR token = 128 random bits (base64url, 22 characters, case-sensitive); TEXT = 10 characters from a 31-character alphabet without 0 O 1 I L (about 49.5 bits), shown `XXXXX-XXXXX`, accepted in any case with or without the hyphen. Stored as `HMAC-SHA256(pepper, "login-code:" + canonical code)` with the OTP pepper. Issuing (`POST /classes/{id}/login-codes`, class teachers or managers, `@Idempotent`, `Cache-Control: no-store`) revokes every earlier live code of those students and returns the plaintext once; default validity from `students.login_code.ttl_days` (365), at most 730. `POST /auth/login-code`: every failure is the same 400 `auth.invalid_code`; failures are rate limited (20 per client address and 10 per code prefix, the first 5 letters or digits, per 15 minutes, then 429; successes do not count towards those, plus a 300-attempt cap per address) so a classroom behind one address can sign in together; the session is shorter (`school.auth.sessions.login-code-absolute-ttl` 7 days, `login-code-idle-ttl` 2 days). Optional username and password for older students only while `students.password_login.enabled`; password sign-in refuses a managed student unless every school they are enrolled in allows it, with the same error as a wrong password.
- **`GET /me`:** `memberships` (type TEACHER|STUDENT|STAFF, school id/slug/name, `staffId` or `studentId`, campus) for ACTIVE memberships. Avatar reads (`AVATAR` policy) now extend to school members: staff see every member, students and teachers see the students they share a class with.
- **Not done / follow-ups:** a `students.login_code` feature flag (no feature definition exists; the settings and permissions gate the flow instead); `must_change` is stored but not yet enforced or exposed on `/me`; the `@Idempotent` replay of a code sheet keeps the plaintext codes in `idempotency_keys` for 24 hours; invitations and guardians (M5); bulk issuing for several classes at once; promoting a class to the next year (M4 roster import).

**As built: M3b school setup, people and classes on the web (25 Nov 2026).** Steps 5 and 6 (web half).
- **Navigation and access:** `lib/school/access.ts` turns `GET /me` into menu items: Dashboard always; Classes with `class.manage` (teachers without it get "My classes"); Students `school.students.manage`; Teachers `school.staff.manage`; Academic year `school.academic.manage`; Settings `school.settings.manage`; Features (read-only) `school.read`. Teachers and other members have no `school.read`, so `getWorkspaceSchool()` takes their school from `memberships`; `/s/{slug}` stays 404 for people with neither a grant nor a membership. A STUDENT-only account has no workspace: `resolvePostSignInPath` sends it to `/student-app`, teachers/staff memberships count as workspaces (several: the picker). Pages show a "not available to you" notice for typed URLs (`RequirePermission`).
- **Pages:** dashboard (counts + setup checklist for administrators, My classes for teachers), academic year (years with current badge and make-current, periods with the API's overlap problem inline, grade levels with the sequence field, subjects with color), teachers (filters, Load more, add staff with 409 `staff.already_member`, detail/edit/deactivate), students (filters, add, detail with FULL/BASIC split, edit, withdraw, set password only while `students.password_login.enabled`, per-student login codes: issue once / revoke), classes (cards with lead teacher, quick create with emoji icon and color presets, subjects and first students; detail tabs Students, Teachers, Groups, Login codes, Settings; move, remove, last-lead guard, archive/restore/delete), school settings (`PUT/DELETE /platform/settings` at SCHOOL scope, definition-driven editors grouped by module, source badge and "reset to inherited") and Features (flag x plan, read-only). Class `icon` is an emoji string and `color` a `#rrggbb`.
- **Printed login codes:** "Generate codes for all students" (QR, text or both; TTL defaults from `students.login_code.ttl_days`) calls `POST .../login-codes` without an `Idempotency-Key`; the response goes to `IssuedCodesProvider` (React state in the workspace layout) and the app navigates (client side) to `.../login-codes/print`: A4 sheet, 8 cards per page (2x4), cut guides, QR drawn locally with `qrcode` (the QR encodes only the bare token), text code `XXXXX-XXXXX`, instructions in a chosen language. After a reload or leaving the workspace the codes are gone and the page offers to issue again. The BFF forwards the API's `Cache-Control: no-store`.
- **Backend addition:** `GET /api/v1/schools/{id}/summary` (`school.read`; 404/403 as the other school endpoints) with live counts for the dashboard (`SchoolSummaryApiTest`, `AuthorizationMatrixTest`). School settings and the effective configuration needed no new endpoints: `/platform/definitions`, `/platform/settings` and `/platform/schools/{id}/configuration` already accept `school.settings.manage` / `school.read` at school scope.
- **i18n:** new `school.*` namespace (en + am draft) and `nav.academic|myClasses|features`; the Amharic text needs native-speaker review.
- **Not done:** teacher names on class cards beyond the lead (one lazy `teachers` call per card), random group maker (M6), student avatars from the avatar builder (M0.5), promoting classes to the next year (M4).

**As built: M3b mobile, teacher and student apps (25 Nov 2026).** Steps 6 and 7 (mobile half).
- **Roles from memberships:** `resolveSessionFromMe` maps `GET /me` memberships: any TEACHER gives `teacher`, any STUDENT `student`; STAFF and administrator grants alone stay `admin` (web-only, the existing no-app-role screen); `guardian` stays unmapped until M5. The role order is stable (teacher, guardian, student), several roles get the Settings role switcher, and the last active role is remembered in the kv store (`school.activeRole`). The dev role previewer is unchanged (the two tab roots keep their placeholders in preview, which has no account).
- **Student sign-in (`(auth)/student-code`):** `expo-camera` QR scan (QR only, explanation card before the permission prompt, frame colour and haptics for found / not a class code, 2.5 s cooldown against re-reading the same code) plus a text field that formats as typed (`ahr4pa4ws9` becomes `AHR4P-A4WS9`, any case, spaces and hyphen ignored). Typed `POST /auth/login-code` (the `untypedApi` cast is gone), tokens through `storeTokens`, then `/me`. Every wrong, expired or revoked code is one friendly message (the server's uniform 400), 429 shows a `Retry-After` countdown, network errors have their own text. **Expiry routing:** the sign-in method (`otp` or `loginCode`) is remembered (`school.signInMethod`, no secret) and survives sign-out in the session state; a signed-out user whose last session was a class-code session lands on `student-code` (with a "time to sign in again" notice when the server ended it), everybody else on the phone screen; "I'm a teacher or parent" leads back. Verified by expiring the session in the database and relaunching.
- **Student app:** tabs Home (greeting, "My classes" cards with class colour/icon, teacher names from the class teachers endpoint, pull to refresh, empty state), Portfolio (placeholder, M12), Profile (avatar, name, school, Settings, sign out). Only BASIC data is read for classmates (there is no classmate list yet).
- **Teacher app:** tabs Classes, Messages (placeholder), Calendar (placeholder), Profile. The class opens in a stack above the tabs (`app/class/[classId]/...`; the floating tab bar would cover the action buttons): header, roster as a 3-4 column avatar wall (first names), student screen (FULL fields only when the roster row says FULL, loaded on demand), add students (search through `GET /students?q=`, multi-select, one `POST .../students`; teachers see only the students of their own classes, so the list is "students of my other classes", managers see all), "Add a new student" (first name, father's name as `middleName`, optional date of birth; `Idempotency-Key` per form; lands on the new student), remove (confirmation), move (only the teacher's other active classes of the same school), 403 shown as "only teachers of this class can change it". "Show login code": confirmation ("replaces the old code"), `POST .../login-codes` for that student with `type: BOTH`, full-screen card with a QR (`react-native-qrcode-svg`, black on white, bare token) and `XXXXX-XXXXX`, screen kept awake while shown.
- **Plaintext codes on the device:** the code lives only in the state of that one screen. It is not a query or a mutation (nothing in the TanStack caches), not stored, not logged, and the screen drops it and closes when the app is no longer active or the screen unmounts. Persisted offline cache: `me`, my classes, class details, teachers and rosters reduced to name and avatar (`toRoster`); student records (date of birth, gender, username) and school-wide student searches are never persisted (`isPersistablePeopleQuery`); sign-out clears the cache.
- **Avatars:** `<Avatar>` shows the signed-link photo, otherwise a deterministic colour (hash of the id, or `avatarConfig.color`) with initials; class badges use the class `color` and an emoji `icon` (first letter when missing or not short).
- **Hermes has no `Intl.PluralRules`:** ICU `plural` in a mobile message throws FORMATTING_ERROR on a device, so `mobile.*` messages use no plurals (a test enforces it); counts read "Students: 6".
- **New packages:** `react-native-svg` 15.16.0, `react-native-qrcode-svg` ^6.3.54, `expo-haptics` ~58.0.8, `expo-keep-awake` ~58.0.4 (the first run needs a dev-client rebuild).
- **Not verified:** the camera scan (simulators have none; scan handling is unit-tested), Android (bundled, not run), dark/Amharic screens beyond the ones captured, Amharic wording (drafts).

### M4 Roster import (≈ 2 weeks, parallel with M5)
Tables: DB §4.15.

1. Upload a CSV or XLSX file (stored as `media`, purpose IMPORT_FILE). The backend parses it with Apache POI for XLSX and a CSV parser for CSV.
2. Column mapping UI with saved `import_mapping_profiles`, and downloadable templates for each import type.
3. **Dry run:**
   - normalize data (phone numbers to E.164, dates, names)
   - validate each row
   - detect duplicates through `external_entity_mappings`, then student number, then name and date of birth
   - preview the create/update/skip counts
   - produce a downloadable error report
4. **Commit:**
   - replay a validated dry run idempotently inside a job
   - record counts and changes
   - guardian contacts create **invitations only**, never active links (§125)
5. Import order: academic years → subjects → classes → staff → students → enrollments → guardian contacts.
6. Run with real data from the pilot schools before launch.

**As built: M4a roster import, backend (25 Nov 2026).** Steps 1-5 (the web wizard is M4b, built from `openapi.json`).
- **Migration:** `V4_01__imports` (`import_mapping_profiles`, `import_jobs`, `import_job_rows`, `external_systems`, `external_entity_mappings`, new `guardian_contact_candidates`, `student_profiles.name_fold` with a GIN trigram index). Deviations from DB §4.15 are listed there. Permission `school.import.manage` (SCHOOL_ADMIN and the higher administrators; CAMPUS_ADMIN none).
- **Module `imports`:** `ImportService` (workflow), `ImportDryRun` / `ImportCommit` (background work, Modulith events `ImportDryRunRequested` / `ImportCommitRequested`), one `RowHandler` per type (`GRADE_LEVELS`, `SUBJECTS`, `CLASSES`, `STAFF`, `STUDENTS`, `ENROLLMENTS`), `ImportReports` (CSV error report), `ImportTemplates` (CSV and XLSX), `MappingProfileService`, `ImportMaintenance` (retention, stuck jobs), `ImportMediaPolicy` (`IMPORT_FILE`: `school.import.manage`, reads audited). People and academic data are written through two new ports without permission checks (the import checked it): `people.PeopleImports` and `academic.StructureImports` (class rosters through `ClassMemberships`); `StudentService` and `StaffService` were refactored so the manual and the import path share one provisioning code path, events and audit included. `TenantDirectory.campusesOf` and `MediaService.content` were added; `MediaReads.url` is no longer read-only (reads of audited purposes such as `IMPORT_FILE` write an audit row, which failed before).
- **State machine:** UPLOADED, PREVIEWED (mapping saved), VALIDATING, VALIDATED, COMMITTING, COMPLETED; FAILED (a dry run again, or the same commit call resumes); CANCELLED. The job row holds all state; workers take a lease first; a watchdog queues jobs whose worker died; at most 2 checks or commits run per school; creating a job is limited to 20 per user and hour.
- **Files:** `commons-csv` 1.14.1 and POI `poi-ooxml` 6.0.0 (verified on Maven Central; they run on Java 25 and Boot 4.2). XLSX is read with POI's SAX API directly, not through `excel-streaming-reader`: the import needs hidden rows, merged ranges, raw numbers with their style (dates, 1904, `000123` formats) and formulas flagged, which the wrapper hides. Zip limits (ratio 0.01, 100 MB entry, 250 MB total, 1,000 entries, 10M characters), macro parts and OLE2 files are refused. CSV: BOM, UTF-16 heuristic, strict UTF-8, windows-1252; `sep=` line; delimiter scoring outside quotes.
- **Normalisation:** phones (Excel dropped zero, scientific notation, stray zero, Arabic digits, plausible unrecognised numbers are warnings), names (`common.text.PersonNames`, fold key with Ethiopic homophones), dates (ISO, `dd/MM/yyyy`, `d.M.yyyy`, Excel serials, Ethiopian text dates through `EthiopianCalendar`, a Java port of `packages/calendar`, parity-tested for every day of 1900-2100 against `apps/backend/src/test/resources/imports/calendar-parity.csv`, generated by `scripts/gen-calendar-parity-fixture.mjs`), gender, ids.
- **Duplicates (students):** external id mapping, student number, then name key (trigram >= 0.6) plus birth date within a year; >= 0.9 with the same birth date and a shared class or guardian phone is matched automatically, the rest is a possible duplicate the administrator resolves (LINK, CREATE_NEW, SKIP). Never across schools. The dry run also marks rows beyond the plan's `max_students` as invalid (`student_limit_reached`). Within the file, repeated student numbers, external ids, usernames, phones are errors.
- **Not done / follow-ups:** academic years and periods are not imported (set up by hand); guardian contacts are only staged (`guardian_contact_candidates`, M5 turns them into invitations); the XLSX error report (CSV only); `external_systems` has only the "CSV import" system; username availability is not pre-checked (a taken one fails the commit at that row); audit rows of the entities created by a commit have no actor (the `import.*` rows do); staged guardian contacts have no retention yet.

**As built: M4b roster import, web wizard (25 Nov 2026).** School workspace, permission `school.import.manage` (menu item "Import" only with it; `SP.importManage` in `lib/school/access.ts`).
- **Routes** (`app/[locale]/(app)/s/[school]/import/`): `/import` (history: status filter, keyset "Load more", cancel and delete behind confirmations, polled every 3 s while a job runs), `/import/new` (type, template download as CSV or XLSX, upload, create) and `/import/{jobId}` (the path notifications link to; every state resumes at its own step).
- **Steps:** File, Columns, Check, Review, Import (`ImportSteps`). `stageOf(job)` in `lib/school/import/state.ts` maps the status to a screen: UPLOADED and PREVIEWED to the columns, VALIDATING to the progress bar, VALIDATED to the review, COMMITTING to the progress bar, COMPLETED to the summary, CANCELLED to a notice, FAILED to the review (failed commit, the same call resumes) or to the "check stopped" screen. From the review the person can go back to the columns (the job becomes PREVIEWED once the mapping is saved). Polling (`pollDelayMs`) runs only while VALIDATING or COMMITTING (1 s, slower for long runs) and stops on a 4xx.
- **Upload:** `IMPORT_FILE` through `lib/media/upload.ts` with the school as context. Browsers disagree on the CSV type (empty, `text/plain`, the Excel type), so `lib/school/import/files.ts` decides the type from the name (`.csv` to text/csv, `.xlsx` to the workbook type; `.xls` and `.xlsm` are refused before upload) and re-wraps the `File`. The create call carries an `Idempotency-Key` that is renewed per file and type.
- **Columns step:** encoding, separator and sheet dropdowns re-read the file through the new preview parameters (below); one select per field with a sample value, required and "one of" markers, the missing list; date system; saved mappings (apply, delete, save under a name; the API's auto-applied profile is shown). "Check the file" saves the mapping and starts the dry run in one go.
- **Review step:** counts, filter chips (all, problems, possible duplicates, ready, left out), keyset-paged rows with localized problem messages, "show the row as in your file", possible duplicates with the candidates and LINK / CREATE_NEW / SKIP, the error report (fetched through the BFF, saved as a Blob, never cached), "leave out the invalid rows" and the commit confirmation naming the counts that will be created, updated and left out. A refused commit reloads the job (out of date: back to the columns or to a new check).
- **Privacy:** rows, previews and candidate lists exist only in TanStack memory (`gcTime` 30 s); nothing goes to URLs, storage or logs. The error report and templates are downloaded with `cache: "no-store"`.
- **Backend changes (small, made for this UI):** `GET /imports/{id}/preview` accepts `encoding`, `delimiter` and `sheet` (any of them, empty meaning detect) and returns the preview of the file read that way with a fresh suggested mapping, saving nothing (without it the reading override could not be tried before a complete valid mapping exists); `CommitResult.guardianContacts` counts the parent contacts the job staged ("N parent contacts saved for invitation"; only in the single-job read). OpenAPI and generated types regenerated; `StudentImportApiTest` covers both.
- **Not done / follow-ups:** no component tests (the web test setup is node-only: the pure logic is tested); the Amharic text needs a native speaker; a pre-commit count of staged parent contacts; XLSX error report; guardian UI (M5 web); a "with warnings" row filter (warnings are shown on the rows, the filter is server side by status only).

### M5 Families: guardian linking, permissions & consent (≈ 3 weeks)
Tables: `student_guardians` and `invitations` from DB §4.4, plus DB §4.18 (policy documents, versions and consent records).

1. **Invitation flows:**
   - teacher or admin sends a parent invite by SMS or email from the roster
   - printed parent codes per student (ClassDojo-style home connection)
   - accepting creates a PENDING link
2. **Verification:**
   - a school admin queue to approve or reject links, recording `verification_method`
   - optional auto-verification via the setting `guardians.auto_verify_invite`
3. **Permissions UI:**
   - per-guardian flags
   - `custody_restricted` with a note, visible to admins only
   - multiple guardians per child
4. **Consent:**
   - policy documents and versions for ACCOUNT_PARTICIPATION and MEDIA_PUBLICATION, in each school language
   - consent prompt during guardian onboarding
   - admins can record paper consent
   - a "consent status" column on rosters
   - an API `hasConsent(student, type)` used by M8
5. **Guardian mobile onboarding:**
   - accept invite
   - verify phone with OTP
   - choose language (§111)
   - give consent
   - see the child switcher
6. Notifications: GUARDIAN_LINK_APPROVED and CONSENT_REQUESTED.

**Exit:** a parent can link to a child, give consent, and see that child's classes, and only those. Allow/deny tests pass for unrelated and restricted guardians.

**As built: M5a guardian linking, guardian access and consent (25 Nov 2026, backend only).** Steps 1-4 and 6 as an API contract (`openapi.json`); the web and mobile screens (steps 3 UI, 5) follow from it.
- **Migrations:** `V5_01__guardians` (`guardian_profiles`, `invitations`, `student_guardians`), `V5_02__consent` (`policy_documents`, `policy_versions`, `consent_records` with an append-only UPDATE trigger), code-owned seed `R__60_policies` (ET defaults for ACCOUNT_PARTICIPATION, MEDIA_PUBLICATION, TERMS, PRIVACY, version `2026-11-draft` in en + am, **DRAFT PLACEHOLDER texts: legal review before any pilot**). Repeatables: permissions `school.guardians.manage` and `school.policies.manage` (PLATFORM/COUNTRY/ORGANIZATION/SCHOOL_ADMIN), settings `guardians.teachers_can_verify` (true), `guardians.teachers_can_invite` (true), `guardians.parent_code.ttl_days` (30, 1-90), `guardians.parent_code.max_uses` (4, 1-6) next to `guardians.auto_verify_invite` (false), notification types `GUARDIAN_LINK_REQUESTED`, `GUARDIAN_LINK_REJECTED` (`GUARDIAN_LINK_APPROVED`, `CONSENT_REQUESTED` existed). Deviations are in DB §4.4 and §4.18.
- **Modules:** `guardians` (invitations, links, verification, `/me/children`; public `GuardianAccess`, `GuardianPermission`, `RelationshipType`, event `GuardianLinkChanged`) and `consent` (policies, decisions; public `ConsentService`, `ConsentType`, `ConsentStatus`, event `ConsentChanged`). Dependencies: people -> guardians -> academic/identity/tenancy/notifications; consent -> guardians/academic. `guardians` implements academic's `ClassAccessRule` (view only), identity's `AccountMemberships` (GUARDIAN entries in `/me`) and identity's new `InvitationRedeemer` port: identity hosts `POST /invitations/otp` and `/invitations/accept` because they send codes and sign people in (`InvitationSignInService`, OTP purpose `ACCEPT_INVITE`, which `otp_challenges` already allowed). Identity also gained `Contacts` (phone/email normalization) and `UserDirectory.verifiedContacts`.
- **Roster privacy split:** `ClassAccess.canViewRoster` / `rosterVisibleClasses` (ACTIVE students, ACTIVE teachers, `class.manage`; contributed rules never count). `GET /classes/{id}/students` uses it (`ClassLookups.rosterViewable`); `people.StudentAccess` uses the roster predicate for BASIC and gives FULL to the student's ACTIVE unrestricted guardians; `StudentService.get` checks the level before the school gate (guardians are not school members). Guardians therefore get class metadata and teachers (200), 404 for rosters and other children; teach/manage endpoints answer them 403 (they can see the class). Avatars: a guardian sees their own children's avatars (`PeopleMembershipPorts.mayViewAvatar`), nothing else.
- **Invitations:** inviters = `school.guardians.manage` or (setting) LEAD/CO/SUBJECT teachers of a class the student is ACTIVE in; at most 10 live per student. SMS/email: single use, 14 days (`school.guardians.invite-ttl`), 256-bit token (SHA-256 stored), link `{SCHOOL_GUARDIANS_INVITE_BASE_URL}/{school language}/invite/{token}` (required in deployed environments; local/test `http://localhost:3100`), the message names the school, not the child. Printed parent codes: login-code format in the `parent-code:` HMAC namespace, multi-use (setting), TTL (setting), reissue revokes, plaintext once with `no-store`, not `@Idempotent`. Preview (public): school, child's first name, relationship, channel. Unknown/expired/revoked/used-up and inactive school or student: uniform 404 `invitation.not_found`. Rate limits: invitation-key failures 20/15 min per address and 10 per code prefix, 300 attempts per address; invitation codes 3/h per destination and 20/h per address; code checks 10/15 min per destination.
- **Accepting adult:** a new destination gets a new STANDARD account (destination verified); an existing holder of the phone or email is used only if it is an ACTIVE STANDARD account with that contact already verified (as OTP sign-in requires). An unverified copy, a school-managed or an inactive holder is refused with 409 `invitation.contact_unavailable` (no session, nothing verified or linked), so an invitation can never take over an account. Both redemption paths also refuse the child themself and non-STANDARD accounts (`guardian.account_not_eligible`).
- **Link status:** PENDING unless channel SMS/EMAIL, `guardians.auto_verify_invite` on and the accepting adult proved the invited destination (then ACTIVE, `SCHOOL_INVITE`, verified_by = inviter). Printed codes always PENDING; the same adult and child are idempotent (existing link, no use consumed). Verifiers: `school.guardians.manage` or (setting `guardians.teachers_can_verify`) the child's LEAD teacher; approval = `SCHOOL_VERIFIED`. Admin-created links (`POST /students/{id}/guardians`) are ACTIVE `SCHOOL_VERIFIED`. Custody restriction, REVOKED, REJECTED and PENDING grant nothing; access is read from the database per request.
- **Consent:** effective document = the school's own (once a version is published) else the country default; current version = latest label in effect, reader's language -> en -> any. Status = most restrictive of the latest decision per current consent-giving link (in-app or paper) and per named paper guardian: any DENIED/WITHDRAWN -> DENIED; else a GRANTED not outdated by a `requires_reconsent` version -> GRANTED; else UNKNOWN (`RECONSENT_REQUIRED` / `NO_DECISION`). `mediaPublicationAllowed` is true only for GRANTED. CONSENT_REQUESTED when a link becomes ACTIVE and when a school publishes a `requires_reconsent` version (idempotent listeners).
- **Tests:** guardian personas in `support.Personas` (`GUARDIAN_A1`, `GUARDIAN_PENDING_A1`, `GUARDIAN_RESTRICTED_A1`, `GUARDIAN_REVOKED_A1`, `GUARDIAN_OF_A2`, `UNRELATED_ADULT`), every new endpoint and the roster/student endpoints in `AuthorizationMatrixTest` (cases may now expect public endpoints for anonymous callers), flow tests `GuardianInvitationFlowTest`, `ParentCodeTest`, `GuardianAccessTest`, `ConsentTest`.
- **Not done / follow-ups:** scanned paper-consent evidence (no `CONSENT_EVIDENCE` media purpose yet; `evidence_media_id` exists), an expiry job for invitations (EXPIRED is derived), invitations of staff/class joins, guardian self-unlink, the M4 import wiring (`invitations.import_job_id`), legal review of the policy texts and native review of the Amharic.

**As built: M5b guardian and consent screens on web and mobile (25 Nov 2026).** No backend change; the screens use the M5a contract as generated.
- **Guardian entry (web):** `/{locale}/invite/{token}` (public, outside the protected routes, own `(invite)` layout with the header inside Suspense; `noindex`, `no-referrer`) runs preview, then phone or email, then the one-time code with optional name and language, then accept. Accept goes through our Route Handlers `/api/invitations/{preview,otp,accept}` (`guardOrigin`, input whitelist in `lib/server/invitation-input.ts`, no session attached); only `accept` sets the httpOnly cookies and answers `{ok:true}`. A dead invitation of any kind is one "can't be used" page; 409 `invitation.contact_unavailable` and 429 (with the retry countdown) have their own copy; the API's localized `problem.detail` is shown for the rest. A visitor who is already signed in is offered "connect to my account" (`POST /me/guardian-links`). **A new parent holding only a printed code cannot sign in (an unknown number gets no OTP), so the public `/{locale}/connect` page ("Have a parent code?", linked from sign-in) runs the same flow with `code` instead of `token`;** the code stays in component state (never URL, storage or query key). The BFF now refuses the token-issuing endpoints (`auth/otp/verify`, `auth/password`, `auth/login-code`, `auth/refresh`, `invitations/accept`): they used to pass tokens straight to the browser.
- **Guardian home and consent (web `/family`, `/family/consent`; mobile `(guardian)/home`, `consent`):** "My children" (picture, name, relationship, classes and teachers when the link allows, "waiting for the school" cards with the first name only, open consent decisions, connect with a code) and consent per child: effective text in the user's language, grant, refuse or withdraw after a confirmation, "recorded for this child only", "if any guardian says no, photos are not shown", 409 `consent.version_outdated` reloads the new text. Sign-in lands guardians (and any account that holds nothing yet, e.g. a parent whose request still waits) on My children; on mobile `resolveSessionFromMe` maps a GUARDIAN membership, or an account with no membership and no role, to the `guardian` role. Notification paths `/children` and `/consents` open these screens on both clients.
- **School side (web):** menu items Families (administrators and every teacher) and Policies (`school.policies.manage`) from `familyAbilities` (`lib/school/families.ts`). Student page: guardians panel (list, invite by SMS or email, add directly, edit flags/relationship/main contact/custody restriction with note, end link, approve/reject, invitations with cancel, print a parent code) and, for administrators, consent records and "record paper consent". Class page: Consent tab (roster column) and a "Parent codes" card for the whole class. Families page: the verification queue with invited-versus-proved contact, status filter for administrators, "lead teachers only" note when the API answers 403. Policies page: text in effect, own versions, publish a version (creates the school's document on first use, then draft, then publish) with the requires-reconsent option. The `guardians.*` settings appear in the existing settings page.
- **Parent codes** follow the login-code rules: `IssuedCodesProvider` holds the sheet in memory (`putParent`), the print page `classes/{id}/parent-codes/print` renders from it, the single-code dialog shows it once, the issue call is not idempotent (`useIssueParentCodes`, `gcTime: 0`), and nothing reaches URLs, storage or the query cache (checked in the browser smoke test).
- **Mobile:** `invite/[token]` and `parent-code` are open to anyone (signed in or not); `+native-intent.ts` maps `https://{host}/{language}/invite/{token}` and `school://invite/{token}` to the invite screen; `app.config.ts` adds associated domains and Android intent filters only when `SCHOOL_UNIVERSAL_LINK_HOST` is set (needs a paid Apple team and the `apple-app-site-association` and `assetlinks.json` files on that host: not part of this change). Children and consent queries are never persisted (`isPersistableFamilyQuery`); tokens go through `completeSignIn` into `expo-secure-store` as before.
- **Shared pure logic:** `@school/validation/guardians` (parent-code format, destination parsing, invitation failure classification, relationship types) and `@school/validation/family` (consent decision state and available actions, open-decision count, teacher names), tested once for both clients.
- **Gaps and follow-ups:** `POST /invitations/accept` has no `relationshipType`, so a new parent who connects with a printed code is recorded as OTHER (the school can edit it; signed-in redeem does send it); the child's full name is shown to guardians in consent (`childName`) but only first names for waiting requests; no UI yet for guardians to see or leave their own link; the family screens have no push-to-open for `GUARDIAN_LINK_REQUESTED` on mobile (administrator notification, web only); web universal-link files are not served; Amharic strings need native review.

### M6 Classroom engagement: points, badges & toolkit (≈ 3 weeks)
Tables: DB §4.7, §4.8, and `student_groups` in §4.5.

1. **Point categories:**
   - school templates are seeded, and classes can add their own categories
   - POSITIVE and NEEDS_WORK polarity, with needs-work on by default and a school toggle (D2)
   - HOME categories for guardians
2. **Award API:**
   - one student, several students, or the whole class (`award_batch_id`)
   - offline-safe through `client_op_id`
   - the teacher must be ACTIVE in the class; a guardian must have `can_award_points` and can award only in HOME context (DB CHECK)
3. **Reversal and undo:** creates a REVERSAL row. There is a short "undo" window in the UI.
4. **Balances projection:**
   - updated from outbox events
   - a nightly rebuild job checks it against the ledger
   - **home points are excluded from class and school aggregates** (D2)
5. **Badges:** a rule engine on point and attendance events, evaluating CLASS and SCHOOL context only. Manual badges. Notifications.
6. **Teacher class view (mobile and web), the signature screen:**
   - avatar grid with point bubbles
   - tap opens the award sheet with skill tiles
   - multi-select and whole-class award
   - feedback sound and animation (original assets)
7. **Toolkit (client-side):**
   - timer and stopwatch
   - random student picker
   - group maker, with an option to save to `student_groups`
   - routines and checklists (`classroom_activity_templates`)
8. **Student and parent views:**
   - the child's points timeline and donut chart by category
   - the parent sees school points and home points as separate sections
9. **Reports:** points by student, category and date range for teachers and admins, with CSV export (audited).

**As built: M6a engagement backend (25 Nov 2026, backend only).** Steps 1-5, 8, 9 and the API contract for steps 6-7 (the award screen and toolkit are client-side, built from `openapi.json`). New module `engagement` (`api`, `application`; public: `PointsApi`, `PointTransactionRecorded`, `BadgeAwarded`, the `PointContext`/`PointPolarity`/`PointTransactionType` enums); migration `V6_01__engagement.sql`, seeds `R__70_engagement_templates.sql`.
- **Categories:** each school gets its own copy of the platform templates (8 positive, 4 needs-work, 4 HOME; names in English and Amharic, the Amharic is a draft) the first time points are used. School level (`school.points.manage`): create, rename, reorder, change default points, deactivate (`PATCH {active:false}`; never deleted). Class level: the class's LEAD or CO_TEACHER (and class managers, points managers). Teachers list the palette with `GET .../point-categories?classId=`; NEEDS_WORK ones are left out while the school switched them off.
- **Awards** (`POST .../classes/{c}/points/awards`): one student, several or `wholeClass: true`; the caller is an ACTIVE teacher or manager of the class, the students ACTIVE in it; points default to the category's default (1-10 override), the sign comes from the polarity. A multi-student call shares `batchId`. `clientOpId` (a UUID per user action, scoped to the awarder) makes it safe to repeat, also in parallel: the first call is 201, a repeat is 200 with `replayed: true` and the original rows (advisory lock plus unique index). The `Idempotency-Key` header works on top (HTTP replay for 24 hours). School-wide awards (SCHOOL context, `school.points.manage`): `POST .../points/awards`. Guardians: `POST .../students/{s}/home-points` (HOME category, ACTIVE unrestricted link with `can_award_points`, at most 60 a day; 404 for anybody not linked, 403 without the flag), categories from `GET .../students/{s}/home-point-categories`. The `points` feature flag gates awards (403 `points.feature_disabled`).
- **Undo:** `POST .../points/transactions/{id}/reverse` and `.../points/batches/{batchId}/reverse` write REVERSAL rows (once per transaction, enforced by a unique index; 409 `points.already_reversed`). The awarder may undo for `engagement.points.undo_window_seconds` (default 120; the award response says `undoableUntil`), after that 403 `points.undo_window_expired`; the class's lead teacher, class managers and points managers any time. HOME points: only the awarding guardian inside the window; the school never reaches into them (404).
- **Balances:** `point_balances` is recomputed from the ledger by an idempotent `@ApplicationModuleListener` per (student, year, context, class) under an advisory lock (so redelivery and parallel awards cannot drift); `BalanceMaintenance` (`engagement.points-balance-verify`, 02:40 UTC) finds and repairs differences, counting them in the `school.points.balance.repaired` metric. HOME has its own balance rows and is in no class or school aggregate.
- **Badges:** the default set is copied per school (First steps 10 points, Rising star 50, 100 points, Helpful friend, Reading champion, Team player, Excellent participation, Perfect attendance, Teacher's pick). After a positive CLASS or SCHOOL award the engine grants POINTS_TOTAL and CATEGORY_TOTAL badges over the ledger (never HOME); grants are unique per student and badge; the student and their notification-enabled guardians get `BADGE_AWARDED`. `ATTENDANCE_STREAK` is a hook: M7 calls `PointsApi.grantBadge(schoolId, studentId, "perfect_attendance", source)`. Manual badges: teachers of the student and points managers (`POST .../students/{s}/badges`). `POINT_AWARDED` goes to the student and the guardians with `can_receive_notifications` for positive awards only (home awards: the child only); paths `/points` (student) and `/children` (guardians) are placeholders for M6b.
- **Reads:** class overview (`.../classes/{c}/points`, teachers and managers only, with the settings the award screen needs), class leaderboard (403 `points.leaderboard_disabled` unless the school turned it on; top 10 positive totals; roster members only), a student's points (`.../students/{s}/points`: a `school` section and, for the student and their guardians only, a separate `home` section, plus badges) and the keyset-paged timeline (`.../points/transactions?section=SCHOOL|HOME`). Callers: the student, guardians with `can_view_academics`, teachers (CLASS points of the classes they teach), `school.points.read`. Student payloads carry `accessLevel: BASIC` and no date of birth, gender or username. A class or student the caller cannot see is 404, one they can see but may not use is 403 (`class.access_denied`, `points.access_denied`).
- **Reports** (`.../points/report`, `.../points/report.csv`): points per student and category over school-local dates, `classId` for teachers of the class, no `classId` needs `school.points.read`; HOME is never included, reversals are netted. CSV: UTF-8 with BOM, CRLF, headers in the caller's language, text cells starting with `= + - @ TAB CR` get a leading apostrophe, audited (`points.report.export`, counts and filters only), 10 per 10 minutes (429 `points.export_rate_limited`), at most 50000 rows.
- **Toolkit:** `classroom_activity_templates` CRUD (`.../activity-templates`, PRIVATE, CLASS or SCHOOL visibility, teachers and points managers); saved groups gained `POST .../classes/{c}/group-sets` (a whole set, all or nothing) and `PATCH .../groups/{id}`.
- **Not done:** the public (school-wide) ranking endpoint behind `engagement.public_rankings.enabled`, per-category balances in the projection, revoking a badge, an admin view of HOME points (by design), web and mobile screens (M6b), realtime push of a new award to open apps.

**As built: M6c mobile classroom engagement (25 Nov 2026, Expo app).** Steps 6, 7 and 8 on mobile, on top of the M6a API and `@school/classroom-toolkit`.
- **Teacher class screen** (`class/[classId]/points`, opened from "Award points" on the class): avatar wall with point bubbles (BASIC data only), tap for the skill sheet (positive first, needs-work only when the API returns it, quick points choice 1/2/3/5/default), long-press or "Select" for several students, "Whole class", undo snackbar until `undoableUntil` (batch reverse for several students). Totals are derived: last overview + awards still in the queue + confirmed awards a later overview may not contain yet (`totals-model.ts`, `applyDelta`, a 5 s settle window, overview refetched after it). Feedback: original synthesised WAVs (`apps/mobile/scripts/generate-sounds.mjs`, outputs in `assets/sounds`, played with `expo-audio`), a haptic tap, a spring pop and a floating "+n" (RN `Animated`); a mute toggle (plain flag in the kv store) silences sound only, OS reduce motion turns the animation off.
- **Offline-safe awards (superseded by the generic outbox of M7b: `lib/sync`; the notes below describe M6c as shipped):** `award-queue.ts` (pure: injected storage, transport, clock, owner) + `award-queue-sql.ts` (SQLite table `points_award_queue`, local migration 2: ids, category, points, `clientOpId`, timestamps, owner id; no notes, names or tokens) + `award-runtime.ts` (flush at start, on reconnect, on foreground, every 15 s with backoff). Same `clientOpId` until 201 or 200 + `replayed`; 5xx, network, 401/408/425/429 keep the item; other 4xx are final and shown with the student/skill and the API's detail; the queue stops at the first network failure (order kept), caps at 200 items and 24 h, never sends under another user's id, and is wiped on sign-out and when the session ends. "N waiting to send" pill (tap = send now). Awards carry no notes (a note would need a direct online call; none is offered).
- **Toolkit** (`class/[classId]/toolkit`): timer and stopwatch from timestamps (correct after backgrounding), full-screen big clock, screen kept awake while running, chime when done; random picker (no repeats until all picked, short shuffle unless reduce motion); group maker by count or size with "save to class" through `group-sets` (online); routines and checklists from `activity-templates` as a tick list (own, class-shared and school-wide ones: do NOT pass `classId`, it filters to class-only templates).
- **Student and parent views:** student tab "Points" (school points, home points as their own block, donut by category with a text alternative, timeline, badges); guardian child detail `child/[studentId]` from the child card: **School points and Home points are separate blocks and never summed**, a plain note that home points are the family's, and for `canAwardPoints` a home award sheet (HOME skills, online, `clientOpId` kept across retries of the same choice, the API's 60/day message shown). Notification path `/points` opens the student's Points tab; `/children` already opens the guardian home (child cards).
- **Privacy:** child points data (class overview, student points, timelines, badges, home categories) is never written to the persisted query cache (`isPersistablePointsQuery`); skill palettes and routine templates are, so a teacher can award offline.
- **API change:** the engagement `CategoryView` collided by name with the notification preferences `CategoryView` in the OpenAPI document (the generated type was the wrong one); it is now `@Schema(name = "PointCategoryView")` (spec and generated types regenerated).
- **Verified:** vitest (queue retry, duplicate op id, partial failure, ordering, expiry, sign-out clearing, SQL storage on real SQLite via `node:sqlite`, totals model, donut, feedback plan, cache rules), `npm run lint typecheck test` (turbo), `expo-doctor`, `expo export` for iOS and Android, an iOS development build that boots to the sign-in screen with the new bundle, and the HTTP flows the screens use (award 201, replay 200, parallel replays, whole class, needs-work, final 4xx, undo, expired undo for a co-teacher, home award and replay, separate school/home sections, routines, group sets: 35 checks). **Not verified on a device:** every new screen's rendering and interaction (the simulator cannot be tapped or typed into from the build environment, and `simctl openurl` hangs on the custom-scheme prompt), sounds and haptics, the queue on a real network drop, Android.
- **Not done:** leaderboard view (API behind a school setting), notes on awards, offline undo of an already-sent award, realtime update of the grid when another teacher awards, reading routine ticks across devices, Amharic review of the `mobile.points.*` / `mobile.toolkit.*` drafts.
**As built: M6b web UI (25 Nov 2026).** Steps 6-9 on the web (mobile is separate). Code: `apps/web/components/points/**` (screens), `apps/web/lib/points/**` (pure logic with vitest tests, the API calls in `queries.ts`), `@school/classroom-toolkit` for timer, picker, groups and `applyDelta`. Messages: `school.points.*`, `school.toolkit.*`, `school.badges.*`, `family.points.*`, `nav.points`, `school.classDetail.tabs.{points,toolkit}` (Amharic is a draft). `family.*` is shared with the mobile guardian screens, so it stays plural-free (a mobile test enforces it); the web-only `school.*` messages use ICU plurals, which mobile must not reuse.
- **Routes:** class page `/s/{slug}/classes/{id}` gets **Points** (the landing tab for people who teach or manage the class) and **Toolkit** tabs; `/s/{slug}/points` ("Points & badges" in the menu for teachers, `school.points.read`, `school.points.manage`) with Report, Skills and Badges tabs (the last two for managers only); `/student-app` and `/points` show a student's own points; `/family/children/{studentId}` is a guardian's child page (a "points" button on each My children card); `/children` redirects to `/family`, and a points notification with `data.studentId` opens the child's page (`lib/notifications/deep-link.ts`). `proxy.ts` protects `/points` and `/children`.
- **Award screen:** avatar grid with point bubbles (BASIC data only), tap for the award sheet (a bottom sheet on phones), "Select students" for several, "Whole class". Skill tiles come from `GET point-categories?classId=`, positive first, needs-work only while the class overview's `settings.needsWorkEnabled` is true (the API is the authority, the client filters a second time). One `clientOpId` per user action: `lib/points/award-op.ts` keeps it while the same action is retried after a failure and makes a new one after a success (smoke test: response lost after the API processed it, retry answers 200 `replayed`, one row in the ledger). Totals are updated at once from the response with `applyDelta` (`lib/points/optimistic.ts`); `overviewRefresher` asks the API again 3 and 9 seconds after the LAST action of the class (an early stale answer must not overwrite a later action). Undo: `PointsProvider` (in the school layout, so countdowns survive navigation) holds the entries in memory only; a single award is undone by its row (the API sends no `batchId` for one student), several by the batch.
- **Feedback:** original sounds synthesized with WebAudio (`lib/points/sound.ts`: award, needs-work, undo, timer alarm, picker), no audio files; a short CSS pop on the bubble (`.points-pop`, off under `prefers-reduced-motion`); `aria-live` announcements; the mute switch is the only thing stored in the browser (`school.points.muted`, localStorage in try/catch via `useLocalStorageValue`).
- **Toolkit:** timer and stopwatch with injected `Date.now()` and a projector "big clock", random picker (no repeats until the round is done), group maker (count or size, **Save** posts the whole set to `group-sets`), routines (create, edit, delete, "use" as a tick-list; own, this class and school-wide templates; the list is asked without `classId` because the API filter returns only that class's own templates, and narrowed in `templatesForClass`).
- **Setup:** class skills (add, edit, deactivate) in a dialog on the Points tab for lead, co-teacher, class managers and points managers; school skills and badges (create, edit, deactivate; manual badges are given from the award sheet) for `school.points.manage`; the four `engagement.*` settings keep the generic settings page but show a localized title and description (`lib/points/settings.ts`). Skills and badges store icon **names** from a shared vocabulary (`lib/points/icons.ts`, same as the seeded defaults); the web draws them with Lucide, shows an emoji as it is and falls back for unknown names.
- **Student and guardian:** School points and Home points are two separate cards (never summed) with a donut and a table with the same numbers (the chart is decoration), a keyset-paged timeline and the badges earned; guardians with `canAwardPoints` get a home award sheet that says plainly that home points are the family's and not counted in class or school results (also uses `clientOpId`, and a toast with Undo).
- **Reports:** class, skill and date filters, keyset paging, CSV download as a Blob through the BFF with `cache: "no-store"`; 403/404/429 problems are shown inline (the 429 message is the API's). Teachers must choose a class; the school-wide report needs `school.points.read`.
- **API findings:** (1) `CategoryView` of the engagement module clashed with the notification preferences' `CategoryView` in OpenAPI, so the generated client typed point categories wrongly: now `@Schema(name = "PointCategoryView")` (spec and generated types regenerated, committed on their own). (2) Single-student awards carry no `batchId`. (3) The class's lead teacher may undo at any time, so the expired-window error needs a non-lead teacher. (4) `GET activity-templates?classId=` returns only that class's templates. (5) The generated types mark primitive fields such as `defaultPoints` and `active` as optional.
- **Not done:** a student's own web sign-in (students sign in with a class code or password on mobile; the web page works for a student who has a session, tested with a password session), realtime refresh of the grid when another teacher awards, drag-and-drop ordering of skills (up/down only for routine steps; skills keep `sortOrder` from the API), the public ranking.

### M7 Attendance with offline sync (≈ 3 weeks)
Tables: DB §4.6.

1. **Attendance API:** take or update a roll (`attendance_sessions` plus `attendance_records`). Slots are configured per school. Sessions lock after a configurable window.
2. **Sync endpoint:**
   - a batch of operations, with `(device_id, client_operation_id)` for idempotency
   - `base_version` for conflict detection
   - returns the result for each operation (APPLIED, DUPLICATE, CONFLICT, REJECTED) and the server state
3. **Mobile offline store (SQLite):**
   - caches the roster, today's attendance, recent points, recent messages metadata, and calendar and course metadata (§72)
   - a sync queue with retry and backoff
   - conflict UI for the rare case where two teachers mark the same student
4. **Quick attendance UI:** everyone present by default, tap to cycle status, late minutes, notes.
5. **Absence reports:** a parent reports an absence ahead of time, it shows on the roll, and the teacher acknowledges it.
6. **Notifications:** ATTENDANCE_MARKED to guardians, absent and late only by default, configurable per school.
7. **Admin:** daily attendance dashboard and summary reports.
8. **Tests:** offline replay, duplicate submits, two-device conflicts, timezone and date boundaries.

**Exit:** a teacher in airplane mode marks attendance and awards points. Everything syncs correctly when the device reconnects.

**As built: M7a attendance, absence reports and sync backend (25 Nov 2026, backend only).** Steps 1, 2, 5, 6, 7 and 8 (the backend half); the offline store and the quick-attendance UI (steps 3 and 4) are M7b/M7c and build on `openapi.json`. New modules `attendance` and `sync`; migrations `V7_01__attendance.sql`, `V7_02__sync.sql`; settings, permissions and the notification type are repeatable seeds. Deviations are in DB §4.6.
- **Rolls** (`PUT/GET /schools/{s}/classes/{c}/attendance`): a roll is (class, school-local date, slot). Slots per school (`attendance.slots`, default `["DAY"]`, or AM/PM/P1..P8). The date is the school-local calendar date (the class campus's time zone when it has one, else the school's); a roll for a date that has not started is refused (409 `attendance.date_in_future`) unless it is within one hour of local midnight (device clock skew). Semantics: students left out of `records` are untouched (an unmarked student has no record); `markAllPresent` marks every student of the class on that date who has no record and is not listed (PRESENT, or EXCUSED when an acknowledged absence report covers the date) and never touches an existing record. The roster of a date is the students whose class membership overlapped that day (`ClassMemberships.studentIdsDuring`), so a student who joined later or left earlier is not on it (409 `attendance.student_not_in_class`, nothing written). Everything is validated before anything is written.
- **Who:** ACTIVE teachers of the class (assistants while `attendance.assistants_can_take`, default on), class managers, `school.attendance.manage`. A roll **locks** `attendance.lock_after_hours` (default 24) after the end of its date in school time (DST-safe: the day ends at the next local midnight); afterwards only the LEAD teacher, class managers and attendance managers may write, with an `overrideReason` (400 `attendance.override_reason_required`), audited as `attendance.roll.override`; others get 409 `attendance.session_locked`. The lock is derived (status stays SUBMITTED). 404 for callers who cannot see the class, 403 for members who may not take it (students and guardians of the class included).
- **Versions and conflicts:** each record has a `version` (0 on creation, +1 per change); the roll has a `sessionVersion`. A mark identical to the stored record is UNCHANGED (two teachers who agree do not clash). A mark that differs is written only when the writer saw that record: its `expectedVersion` equals the stored one, or the request's `baseVersion` equals the current `sessionVersion` (nobody changed the roll since), or it is the same sync device's own earlier edit (same user and device, device time not before the stored one: a queue edits one record twice). Otherwise it is a CONFLICT that carries the stored record and the submitted mark, and nothing is overwritten; the other marks of the same request are still written. Online: 200 when no mark conflicted, 409 with the same body when some did. **The first write in the server's state stays; the device time (`recorded_at`) is kept and clamped but never decides** (this replaces the "latest recorded_at wins" rule of DB §4.6: a later device clock cannot overwrite a colleague's mark silently).
- **Sync protocol** (`POST /api/v1/sync`, module `sync`): the body is `{operations: [...]}` (at most 100 operations and 1 MiB; 413 `sync.too_many_operations` / `sync.payload_too_large`; 429 `sync.rate_limited` at 120 requests a minute per user; a malformed operation, such as a missing id, is a 400 for the whole request). Each operation has `deviceId` (a UUID the app makes once at install; **not** a `push_devices` id, so a device syncs without push), `clientOperationId` (a UUID per queued operation), `operationType`, `entityType`, optional `baseVersion`, `payload` (at most 64 KiB) and `clientCreatedAt`. The answer is always 200 with `results`, one per operation in the same order: `APPLIED` (with `entityId`, `version`, `state`), `DUPLICATE` (the operation was seen before: the first outcome, with `originalStatus` and the stored `state`; replaying a whole batch is safe, also in parallel), `CONFLICT` (`state` says per student what is stored and what was sent; nothing was overwritten), `REJECTED` (`problem.code/status/detail`, stored: it will never succeed as sent, so do not resend; make a new operation). Operations run in order, each in its own transaction, and a failing one never blocks the next. Authorization is the online endpoint's, at sync time: a removed teacher gets REJECTED `class.not_found`, an archived class `class.archived`, a student who had left `attendance.student_not_in_class` / `points.student_not_in_class`, a locked roll `attendance.session_locked` (or `attendance.override_reason_required` for the lead). Transient failures (429, 5xx, unexpected) are not stored: the result says `retryable: true` and the same operation may be sent again. Time: `clientCreatedAt` is stored as sent; the effective time (`recordedAt`) is the same unless it is more than 5 minutes ahead of the server (then the receipt time, `timeAdjusted: true`); an operation older than 30 days is REJECTED `sync.operation_too_old`. Other codes: `sync.unknown_operation`, `sync.entity_type_mismatch`, `sync.invalid_payload`. Retention: after 30 days (`school.sync.payload-retention`) the payload and the result's `state` (children's data) are removed by the nightly job `sync.operations-retention` (replays are still recognized, without state), after 365 days the rows go. Handlers implement `sync.SyncHandler` (`operationType()`, `entityTypes()`, `apply`); `ATTENDANCE_MARK` lives in `attendance` (payload `{schoolId, classId, date, slot?, subjectId?, markAllPresent?, overrideReason?, records[]}`, `baseVersion` = the roll's `sessionVersion`) and `POINT_AWARD` in `engagement` (payload `{schoolId, classId?, categoryId, studentIds?, wholeClass?, points?, note?}`; it calls `AwardService` with the operation id as `clientOpId`, so the online and the sync path can never award twice: the second is DUPLICATE/`replayed`). M8+ adds an operation type by adding a handler bean.
- **Absence reports:** a guardian with an ACTIVE, not custody-restricted link files (`POST .../students/{s}/absence-reports`; reason ILLNESS/FAMILY/APPOINTMENT/OTHER, optional note; dates at most 7 days back, 90 ahead, 61 days long; 20 a day). The roll annotates the dates (SUBMITTED and ACKNOWLEDGED reports). **An acknowledgement pre-fills EXCUSED only as `suggestedStatus` on the roll and in `markAllPresent`; it never writes a mark by itself**, and an explicit mark of the teacher wins. LEAD, CO and SUBJECT teachers of a class the student is ACTIVE in, and attendance managers, acknowledge or decline once (audited); the reporter may cancel. **Reason and note (health data) are read by the reporter, those teachers and attendance managers only:** not by assistants, the student, the child's other guardians, or class managers who are not attendance managers; never in events, notifications, logs or audit metadata. The teachers of the student (not assistants) get `ABSENCE_REPORTED` (names the child and the dates).
- **Notifications:** `ATTENDANCE_MARKED` to the child's ACTIVE, unrestricted guardians with `can_receive_notifications`, for the statuses in `attendance.notify_guardians.statuses` (default `["ABSENT","LATE"]`, empty = off), once per change of a record (source = record and version), skipped when the record changed again meanwhile; text: first name, status, date; path `/children`. A correction from ABSENT to PRESENT is not announced (PRESENT is not in the default set).
- **Streak badge:** a listener on `AttendanceMarked` (PRESENT) asks `PointsApi.attendanceStreakBadges` for the school's active ATTENDANCE_STREAK badges the student lacks and grants those whose `days` the student's current streak reaches through `PointsApi.grantBadge`. A day is perfect when every mark of the student that date is PRESENT; days nobody took a roll on are not days; the streak is counted back from the student's latest marked date, so a roll filled in late counts.
- **Reads:** a student's history (`GET .../students/{s}/attendance`: the child and guardians with `can_view_academics` see status, lateness, date and class, no teacher note; teachers the classes they teach, with notes; `school.attendance.read` everything), the daily dashboard (`GET .../attendance/dashboard`: per class taken/not taken and counts, totals, classes not yet taken; readers see all classes, teachers theirs: `ClassAccess.teachableClasses`), summary report (`.../attendance/report`) and its CSV (`report.csv`: UTF-8 BOM, CRLF, localized headers, formulas neutralized with `common.text.CsvCells.safe`, audited, 10 per 10 minutes, 50000 rows).
- **Permissions and settings:** `school.attendance.manage` and `school.attendance.read` (PLATFORM/COUNTRY/ORGANIZATION/SCHOOL admins; not CAMPUS_ADMIN: campus administrators manage rolls of their campus's classes through `class.manage`); settings `attendance.slots`, `attendance.assistants_can_take`, `attendance.lock_after_hours`, `attendance.notify_guardians.statuses`; feature flag `attendance` (403 `attendance.feature_disabled`).
- **Not done:** realtime push of a roll to open apps, a decision notification back to the reporting guardian, an expiry for stale SUBMITTED absence reports, per-period (P1..P8) subject rosters, campus-admin attendance reports, attendance import, web and mobile screens (M7b/M7c).

**As built: M7c web UI for attendance (25 Nov 2026).** Steps 4 (web), 5 and 7 on the web (mobile is M7b). Code: `apps/web/components/attendance/**` (screens), `apps/web/lib/attendance/**` (pure logic with vitest tests; API calls in `queries.ts`). Messages: `school.attendance.*`, `school.absence.*`, `family.attendance.*`, `family.absence.*`, `nav.attendance`, `school.classDetail.tabs.{attendance,absences}` (Amharic is a draft; `family.*` is plural-free for mobile).
- **Routes:** class page `/s/{slug}/classes/{id}` gets **Attendance** and **Absences** tabs (`?tab=`, `&date=&slot=` select them; the roll stays mounted, hidden, after first opening so unsaved marks survive a tab switch); `/s/{slug}/attendance` ("Attendance" in the menu for teachers, `school.attendance.read|manage`) with the daily dashboard and Reports tabs; `/family/children/{studentId}` shows the child's attendance history (`can_view_academics`) and the absence-report form and list (any active guardian); `/student-app` shows the student's own history.
- **Roll:** the date is the school's (the roll answers with `timeZone`; "today" is never the browser's; the picker is capped at the school's today). Everyone shows present (or the suggested EXCUSED) as "not saved yet"; tap cycles, Shift reverses, Details opens a direct picker, late minutes (LATE only) and a note. **Mark all present** sends `markAllPresent` together with pending edits; **Save changes** sends only the edits. Each edit carries `expectedVersion` of the record it was made from and the request carries the roll's `baseVersion`; `class-attendance-tab.tsx` keeps the roll it measures edits against (`base`) and only follows the server while nothing is unsaved, so a background refresh can never turn an edit into a silent overwrite. A 409 with outcomes is treated as a result (`putRoll`): conflicting students get a panel with the stored mark and the teacher's, "Keep theirs" or "Use mine" (resend with the stored version). Locked roll: banner with `locksAt`; `mayEditLocked` users must give a reason (required client-side too); others read only. Unsaved edits live in memory only (a `beforeunload` guard warns).
- **Inbox:** acknowledge/decline with a confirmation; a second decision shows the API's `absence.already_decided` inline; roll and inbox refresh after a decision. Reason and note appear only when the API sends them.
- **Dashboard/report:** keyset paged, "not taken yet" filter (client side over loaded pages), links into the roll of the chosen day; report filters class/student/range, CSV through the BFF as a Blob (`no-store`), 429 shown inline.
- **Notifications:** `ATTENDANCE_MARKED` opens `/family/children/{id}#attendance`; `ABSENCE_REPORTED` (path `/classes/{id}/attendance`, no school in it) opens `/s/{slug}/classes/{id}?tab=absences`, the slug resolved from `/me` (`deepLinkNeedsSchool`, `schoolSlugOf`).
- **Backend changes:** (1) `AttendanceDashboard.slots` (additive) so the dashboard can offer a roll-call switcher; (2) **bug fix:** withdrawing an ACKNOWLEDGED absence report was a 500 (the `decided_by/decided_at` check); `cancel` now clears them (the decision stays in the audit log), with a test.
- **Gaps:** `ChildSchool` has no time zone, so the guardian form's default dates and window hints use `DISPLAY_TIME_ZONE` (the API stays the authority); period (`subjectId`) rolls are not selectable; the "not taken yet" filter cannot ask the API for only those classes; the rolls do not refresh by themselves (Refresh button) since there is no realtime push yet.
- **Verified:** vitest (dates incl. day boundaries and zones, status cycling, roll diff/payload/conflicts, report query, absence form, access, deep links), lint, typecheck, `next build`, and a Playwright run on the real UI (roll, conflicts, lock and override, absence report and inbox, dashboard, report and CSV with rate limit, guardian and student history, settings, Amharic, 390px, notification links).
**As built: M7b mobile offline store, sync engine and quick attendance (25 Nov 2026, Expo app only; no API change).** Steps 3, 4, 5 (mobile) and 6 (notifications) of M7, plus the exit criterion.
- **Generic outbox (`apps/mobile/src/lib/sync`):** one SQLite table `outbox_operations` for every operation type over `POST /api/v1/sync` (`ATTENDANCE_MARK`, `POINT_AWARD`). `SyncEngine` is pure (injected storage, transport, clock, owner). Rules: one `clientOperationId` per user action, kept until the server answered; strict order, batches up to 100 operations and about 900 kB; APPLIED, and DUPLICATE of an applied operation, remove the row; `retryable` results, 429 (Retry-After or 30 s), 5xx, no answer, 401 keep the row and repeat the SAME operation with exponential backoff (2 s to 5 min), and a transient failure stops the pass so the order stays intact; a 400/413 for the whole request is retried one operation at a time to find the culprit; an operation is **never edited again once it was handed to the network** (the attempt is persisted before the call, so also not after a crash), and only untouched attendance edits of the same roll (class, date, slot, teacher) merge into one operation (a teacher tapping through a class makes one request); what the server decides stays as a row with a status for the teacher: `CONFLICT` (per-student `stored` and `submitted` marks, nothing overwritten), `REJECTED` (the API's localized `problem.detail`, never resent) and `EXPIRED` (older than 7 days for attendance, 24 h for awards, inside the server's 30 days), forgotten after 7 days; cancelling (award undo) is only possible for an operation that was never handed to the network, otherwise the award is undone after it is confirmed; another user's rows are discarded unsent; `wipeOfflineData()` runs at sign-out and when the session ends. Device wiring (`runtime.ts`): flush at start, reconnect, foreground, every 15 s while something waits, and 0.7 s after an edit; with the device offline the transport answers `offline` without sending, which leaves the operations editable.
- **M6c migration:** local migration 3 copies `points_award_queue` rows (id, owner, class, skill, students, points, age, attempts) into `outbox_operations` as `POINT_AWARD` and drops the old tables (also the unused `sync_outbox` placeholder of migration 1). The M6c operation id was already the award's `clientOpId`, which the sync handler uses as the operation id, so a queued award that the old build had already got through is answered DUPLICATE and never counts twice (tested against a real SQLite and against the running API). The M6c UI is kept (pill, rejection banner), but rejections now survive an app restart because they are rows.
- **Privacy of the outbox:** rows hold ids, statuses, marks, numbers and times only (a payload is rewritten from its known fields before it is written, so a stray field cannot reach the disk). **Attendance notes are not stored:** a short note (200 characters) lives in the memory of the running app next to its operation (`volatile`), is merged into the request at send time, and is lost if the app closes before it is sent (the mark itself is not); the details sheet says so. Point notes cannot be queued at all (as in M6c). No tokens, no names (names are looked up from the offline cache when shown).
- **Offline read cache (`lib/offline`, table `offline_cache`, local migration 4):** keyed by user, kinds `classes`, `roster`, `roll`, `rollmeta` (zone and slots), `categories`, `totals`, `templates`; written through by every successful fetch (`useCachedQuery`, `loadRoll`), read first for an instant first paint and as the fallback when the server cannot be asked (no answer, 5xx, 429, 401); 404 on a class deletes everything cached for it (403 does not: it is also what a disabled feature says). Content: roster as id, display name, avatar media id and a placeholder colour (no date of birth, gender, username or phone), the roll as ids, marks, versions and the absence reason CODE (never the teacher's note, the parent's note or any signed media link), points totals per student id. Maximum age per kind (roll 14 days, totals 7, the others 30), checked at read and at start; rolls older than 3 days (a roll built from the class list: 7 days) are shown with their age but not edited; at most 8 rolls per class; classes the teacher no longer teaches are purged on the next successful `/me/classes`; everything is wiped at sign-out and when the session ends, and other users' rows at start. TanStack's persisted cache no longer holds rosters, points data (not even skills and templates any more) or anything about attendance; its buster changed so old copies are dropped.
- **Offline start:** a teacher whose refresh token is still in secure storage no longer lands on "We can't reach School" when the app opens with no connection: the last profile the server confirmed (display name and role codes, 7 days, `offline-identity.ts`) opens the teacher shell from the cache, and the session is renewed at the first reconnect (a revoked session ends then and the offline data is wiped). `getValidAccessToken` now renews when there is no access token in memory but a refresh token on the phone. Other roles still see the offline screen.
- **Quick roll (`app/class/[classId]/attendance.tsx`):** students are unmarked until the teacher taps "Mark all present (N)" and confirms (it says how many become EXCUSED because a parent's report was acknowledged); a tap on a student cycles PRESENT, ABSENT, LATE, EXCUSED, LEFT_EARLY (an unmarked student starts at the parent's hint when there is one, else PRESENT); long press or "..." opens the details (status, minutes late, short note, TalkBack/VoiceOver actions for every status); each tap is an optimistic edit shown at once (roll plus waiting operations) and an operation whose `baseVersion` is the roll's `sessionVersion` and whose marks carry the record `expectedVersion` the teacher saw; haptics; a persistent line says "waiting to send / everything is sent", "offline" and how old the saved copy is; locked rolls show `locksAt` (in the school's zone) and are read-only unless `mayEditLocked`, which asks for a reason once; the date switcher stops at the school's today (computed from the roll's `timeZone`, never the phone's) and 14 days back, slots come from the roll. The server's answer (also a CONFLICT, where it keeps the colleague's mark) is written into the shown roll and the cache before the waiting operation disappears, so nothing flickers back. Offline, today's roll comes from the cache or is built from the class list (nobody marked; clashes are settled by the server later).
- **Conflicts and refusals (`attendance-issues.tsx`):** "{teacher} marked {student}: Absent. You marked: Present." with Keep theirs / Use mine per student or for all; "Use mine" queues a NEW operation (new id) with `expectedVersion` = the stored version and `baseVersion` = the roll's current version. A REJECTED operation shows the API's detail with the class and date named, "Open the roll" and "Dismiss"; "redo" means marking again on the roll (the marks are not replayed on their own).
- **Absence reports and history (mobile):** guardian page `child/[studentId]/attendance` (report sheet with day steppers in the user's calendar, reason chips, 500-character note, one `Idempotency-Key` per report; own reports with cancel; attendance history without notes, paged); teacher inbox `class/[classId]/absences` (acknowledge, decline with a 200-character note; assistants see no buttons) and the hint on the roll ("Parent reported: illness"). Reports are online-only and never cached (health data).
- **Notifications:** `ABSENCE_REPORTED` (path `/classes/{classId}/attendance`) opens the class inbox, `ATTENDANCE_MARKED` (path `/children` plus `data.studentId` and `data.date`) opens the child's attendance (`routeForPath` now takes the notification data; only teachers follow the first, only guardians the second).
- **Verified:** vitest (engine, protocol, outbox SQL against Node's SQLite incl. the M6c migration, cache, cached fetch, roll loading, roll model, conflicts, dates, absence form, offline identity, routes); the engine and wire format against a running API (`SYNC_E2E_FIXTURE=<json> npx vitest run src/lib/sync/e2e.test.ts`, skipped otherwise): airplane mode with marks and awards, restart while offline, reconnect, replay of the whole batch (all DUPLICATE, totals unchanged), a colleague marking the same students meanwhile (per-student conflict, keep theirs, use mine), refusals (date in the future, student not in the class) next to an applied operation, an award first sent through the direct endpoint then through sync (DUPLICATE), and 101 operations (413 then one by one). `expo export` for iOS and Android, a Metro dev bundle, `expo-doctor` and a full `expo run:ios` build (iPhone 17e simulator) pass. **Not verified on a device:** no tap or typing was possible in the simulator (screenshots and `simctl openurl` are blocked or hang on the custom-scheme prompt), so the screens, the SQLite migrations on the Hermes/expo-sqlite runtime and the offline start were exercised only through tests, the type checker and the bundler, not by hand.
- **Not done / for M7c:** realtime refresh of a roll another teacher is editing, per-student attendance notes that survive an app restart (deliberately not stored), an attendance summary for the teacher, offline absence-report inbox, a way to build a roll for a class whose roll was never opened online (the zone is only known from the first roll answer: `MyClass` has no time zone), the guardian's notification-preference screen for attendance, Amharic review.

### M8 Class Story, School Story & feed (≈ 3 weeks)
Tables: DB §4.10.

1. **Posts:**
   - CLASS and SCHOOL scope
   - text plus several media items
   - student tagging (`post_student_tags`)
   - scheduling and pinning
2. **Consent gate (R-09):** publishing fails, with a clear message, if a tagged student lacks MEDIA_PUBLICATION consent. Untagged media needs the teacher to confirm that no unconsented students appear.
3. **Contributions (§18):**
   - parent and student submissions, controlled by school settings: on/off, approval required, allowed media, scope
   - an approval queue for teachers and admins
4. **Stories:** 24-hour stories for a class or school, view tracking, an expiry job.
5. **Interactions:**
   - comments (toggle per post)
   - one reaction per user
   - a "seen by" count
   - report content (M13)
6. **Translation (D5):**
   - a "See translation" action calls a translation provider adapter and caches the result in `content_translations`, labelled "Machine translated"
   - the **adapter interface and cache ship now**; the provider is switched on with a feature flag once it's chosen and its quality is checked for am, om, ti and so
7. **Feeds:**
   - teacher: class feed
   - parent home: a combined feed across their children's classes and schools, filtered by child
   - student: class feed
   - all paginated with cursors, server-filtered (§100), and using low-data image variants

**As built: M8a feed backend (26 Nov 2026, backend only).** Steps 1-7 as an API contract (`openapi.json`); the web and mobile screens are M8b/M8c. New module `feed` (`api`, `application`; public: the events `PostPublished`, `StoryPublished`, `ContributionSubmitted`, `ContributionDecided`, `PostPublishBlocked`, `ContentReported` and the `TranslationProvider` adapter); migration `V8_01__feed.sql`; settings, permission and notification types are repeatable seeds. Deviations are in DB §4.10. **Privacy is the core of this milestone; where a choice was open the most restrictive was taken and is listed under "Decisions" and "Open questions".**
- **Posts** (`POST /schools/{s}/classes/{c}/posts`, `POST /schools/{s}/posts`): CLASS scope (ACTIVE LEAD, CO_TEACHER and SUBJECT_TEACHER teachers of the class, ASSISTANTs while `feed.assistants_can_post` (default on), and class managers) and SCHOOL scope (`school.feed.manage`). `mode` is `DRAFT`, `PUBLISH` (default) or `SCHEDULE` (`scheduledFor` at most 90 days ahead). Text up to 5000 characters, up to 10 photos (media uploaded first with purpose `POST` and context `CLASS`/`SCHOOL`, then attached by id; a photo belongs to one post or story), tagged students (ACTIVE in the class, or in the school for a school post; at most 50). Statuses `DRAFT`, `SCHEDULED`, `PUBLISHED`, `ARCHIVED` (author took it down, `publish` brings it back), `HIDDEN` (moderator), `PENDING_APPROVAL`/`REJECTED` (contributions); soft delete (`deleted_at`; the photos are deleted too). Edit (`PATCH`) is for the author only and sets `editedAt`; a contribution is editable only while pending. Pinning (`PUT/DELETE .../pin`): the teacher author or a moderator, published posts only, at most `feed.pin_limit` (default 3) per class (and for the school feed), counted under an advisory lock. **Moderators** are the class's LEAD teacher, class managers (`class.manage`) and `school.feed.manage`: they approve contributions, hide (with a reason the author sees) and delete anyone's content, pin anyone's post, and see every status; all audited (`feed.post.*`, deletes flag `moderation`).
- **Consent gate (R-09):** whatever makes something with photos visible runs `ConsentWithholding.check`: publish, schedule (early warning), the scheduler, approval of a contribution, `unhide`/republish, editing the photos or tags of a published post, publishing a story. Rule: every tagged student needs MEDIA_PUBLICATION `GRANTED` (`ConsentService`, UNKNOWN counts as no), else **422 `feed.media_consent_missing`** with the problem member `students` = `[{studentId, displayName?}]`; names are included only when the caller may see the class roster (`ClassAccess.canViewRoster`; for school posts a school feed manager), ids otherwise. With photos and **no tagged student** the author must send `confirmNoUnconsentedStudents: true`, else **422 `feed.media_confirmation_required`**; the confirmation is stored (`media_confirmed_by/_at`, dropped when photos or tags change) and audited (`feed.post.media_confirmed` / `feed.story.media_confirmed`). Text-only content never needs consent. A request that fails the gate creates nothing.
- **Scheduler** (`FeedMaintenance.publishScheduledPosts`, every minute, ShedLock `feed.publish-scheduled-posts`, returns `Integer`): publishes due posts one transaction each, re-running the gate with the stored confirmation; a blocked post returns to `DRAFT` with `publishErrorCode`, `PostPublishBlocked` notifies its author (`POST_PUBLISH_BLOCKED`). Idempotent: a post that is not `SCHEDULED` or not due is skipped; repeated runs publish once and notify once.
- **Withdrawal:** consent is **re-read on every request** (no cache): the moment a tagged student's MEDIA_PUBLICATION is not GRANTED (refused, withdrawn, a refusing guardian linked, a granting link revoked or restricted, a re-consent requirement), every viewer except the post's author and its moderators gets `media: []` with `mediaWithheld: true` and **404 from the signed-URL endpoint** for those photos; text, comments and reactions stay. The author and moderators keep the photos (flag `mediaWithheld: true` as a warning). Stories with withheld photos disappear from everybody's lists but the author's and moderators'. When consent is granted again the photos return by themselves. The `ConsentChanged` listener (`ConsentWithdrawalEvents`, idempotent per (content, consent record)) tells each author of published, photo-bearing posts and live stories that tag the child (`POST_MEDIA_WITHHELD`, not switchable off) and writes `feed.post.media_withheld` to the audit log. It is the notification, not the hiding, that is asynchronous.
- **Media access (R-30):** purposes `POST` and `STORY` (existing enum values; images only, contexts `CLASS` and `SCHOOL`, `PurposeRules`). `PostMediaPolicy`/`StoryMediaPolicy`: an upload not yet attached is readable by its uploader alone; attached media is readable by the post's moderators, and by anyone who sees the PUBLISHED post (the same SQL predicate as the feeds) while no tagged student lacks consent; `canManage` (view in any state, delete) is the uploader and the moderators of the attached post. Unpublished (draft, scheduled, pending, hidden, archived) posts' photos: the author and moderators only. Creating uploads: posters of the class, contributors (guardians and students, when the school accepts contributions) and, for the school context, feed managers. Story photos are readable only while `expires_at > now` (checked at read time), then by the author and moderators until deleted (seven days after expiry). Images are served only as re-encoded variants (media module); payloads contain media ids and variant names (`THUMB`/`SMALL`/`MEDIUM`), never URLs.
- **Contributions** (`POST /schools/{s}/classes/{c}/contributions`, approval queue `GET /schools/{s}/contributions/pending[?classId=]`, `POST .../contributions/{id}/approve|reject`): who: a guardian whose ACTIVE, not custody-restricted link to a child ACTIVE in the class has **both `can_view_academics` and `can_message_staff`** (when `feed.parent_contributions.enabled`, default off), a student ACTIVE in the class (when `feed.student_contributions.enabled`, default off). Class scope only (school-scope contributions are not built). A guardian tags only their own children, a student nobody, and **a contributor can never give the "no unconsented child appears" confirmation**: that is the approver's. Guardian contributions wait for approval unless `feed.parent_contributions.approval_required` is off, and then a photo must tag the contributor's own consented child (400 `feed.contribution_needs_tag`, 422 consent errors); student contributions are always approved first. `allowed_media` (default photos) restricts guardians to the kinds the feed supports now. Approval is by the class's LEAD teacher, class managers or feed managers (a CO/SUBJECT teacher or assistant gets 404/403), runs the consent gate, may replace the tags (`studentIds`), is audited and notifies the contributor (`POST_APPROVED`); a rejection needs a reason (shown to the contributor, not in the push), deletes the photos, is audited (`POST_REJECTED`). Contributions are posts with `contribution: true`; the contributor follows them in `GET /schools/{s}/my-posts`; contributions to stories are not built (stories are written by staff). 20 contributions per user per day.
- **Stories:** `POST /schools/{s}/classes/{c}/stories` (posters), `POST /schools/{s}/stories` (feed managers): a `STORY` photo and/or a caption up to 500 characters, tags and the same gate; visible 24 hours (`expiresAt`); lists `GET .../classes/{c}/stories`, `GET .../stories`, `GET /me/stories`; `POST .../stories/{id}/view` records one view per viewer (the author's own is not counted; staff and the author see `seenCount`); `DELETE` by the author or a moderator. **Expiry job** `FeedMaintenance.expireStoriesJob` (every five minutes, ShedLock `feed.expire-stories`, `Integer`): marks stories `EXPIRED` and, after seven days, deletes photo and caption; idempotent; **reads never depend on it** (`expires_at` is compared with the clock).
- **Interactions:** comments (`GET/POST .../posts/{p}/comments`, 1-1000 characters, 60 an hour): on a post with `commentsEnabled` (per post, default `feed.comments.default_enabled`), by teachers and managers, guardians (same two link flags as contributions, `feed.comments.guardians_can_comment` default on) and students (`feed.comments.students_can_comment`, **default off**); the author is always the caller; the LEAD, class managers and feed managers hide/unhide (`POST .../comments/{c}/hide|unhide`) and delete (author or moderator) comments, audited; hidden comments stay visible to their author and moderators with `status: HIDDEN`. Reactions (`PUT/DELETE .../posts/{p}/reaction`, LIKE/HEART/CLAP/STAR): one per user and post by primary key, upsert, idempotent removal, safe under concurrency. Seen-by (`POST .../posts/{p}/view`): a row per viewer, **only the count** (`seenCount`) is returned, to the author, the class's staff and moderators; names are never exposed. Reports (`POST .../posts/{p}/report`, reason INAPPROPRIATE/PRIVACY/SPAM/OTHER, 20 a day): a stub for M13: audit row `feed.post.report` and the event `ContentReported`; no moderation is built.
- **Translation (D5):** `POST .../posts/{p}/translation?language=` and `.../comments/{c}/translation`: **off by default** (`feed.translation.enabled`, 403 `feed.translation_disabled`); without a `TranslationProvider` bean that is `available()` it answers 503 `feed.translation_unavailable` (no provider ships; tests use `RecordingTranslationProvider`). It serves the cache (`content_translations`, keyed by a hash of the text so an edit translates again) or calls the adapter, 60 provider calls an hour per user, and always labels the result `machineTranslated: true`. **What may leave the platform:** the text of a PUBLISHED post (or a visible comment) the caller may see, with every name token of the post's tagged students, e-mail addresses and phone-like digit runs replaced by placeholders (restored locally in the result); never ids, tags, media, drafts, pending contributions or the caller's identity; the adapter receives `(text, sourceLanguage, targetLanguage)` only. Children who are not tagged cannot be recognized in free text: that is why the school decides whether to switch it on.
- **Feeds** (all SQL-filtered, keyset paged with `CursorPage`, newest first): `GET /schools/{s}/classes/{c}/feed` (pinned first), `GET /schools/{s}/feed` (school-wide posts, pinned first), `GET /me/feed[?studentId=]` (the parent home feed: posts of the classes the caller's children are ACTIVE in and school-wide posts of those children's schools, across schools, optionally one child; a student sees their own; links that are restricted, revoked, pending or without `can_view_academics` and schools with the feed off contribute nothing), `GET /schools/{s}/my-posts` (own posts in every status), `GET /schools/{s}/posts/{id}`, `GET /schools/{s}/feed/capabilities[?classId=]`. A cursor is `(pinned, published_at, id)` (the home feed `(published_at, id)`). 404 for callers who cannot see the school, class or post, 403 for members who lack the permission, 403 `feed.feature_disabled` when the school switched the `feed` flag off.
- **Notifications:** `NEW_POST` to the ACTIVE, unrestricted guardians with `can_receive_notifications` (and `can_view_academics`) of the **tagged** students; with `feed.notify_class_members` (default off) also the class's students and their guardians (school-wide posts: every active student's guardians). Text: class or school name and the author's name for staff authors; a family contribution is announced as "A family contribution was shared" without naming anyone; **never the post text, a reason or a child's name**. `NEW_STORY` likewise; `CONTRIBUTION_PENDING` to the class's LEAD teachers; `POST_APPROVED` / `POST_REJECTED` to the contributor; `POST_PUBLISH_BLOCKED` and `POST_MEDIA_WITHHELD` to the author (not switchable off). All idempotent (`alreadyNotified`); `data.path` is `/feed/posts/{id}`, `/feed/stories/{id}` or `/feed/approvals` (the mobile screens are M8c).
- **Settings, permission, flag:** `feed.parent_contributions.enabled|approval_required|allowed_media` (existing), `feed.student_contributions.enabled`, `feed.comments.default_enabled|guardians_can_comment|students_can_comment`, `feed.pin_limit`, `feed.assistants_can_post`, `feed.notify_class_members`, `feed.translation.enabled`; permission **`school.feed.manage`** (school-wide posts and stories, moderation of any class; PLATFORM/COUNTRY/ORGANIZATION/SCHOOL admins; not CAMPUS_ADMIN, who moderate their campus's classes through `class.manage`); flag `feed` (the plans already carry it).
- **Decisions (restrictive defaults):** guardian and student authors and commenters appear as a role label only (no name, no id) to everybody except the author themself, the class's staff, and, for a student, their classmates and own guardians; tagged students are named only to those who may know them (themself, their guardians, the class roster for a class post; for school posts feed managers, their teachers and classmates), a guardian therefore sees only their own children among the tags; seen-by is a count; only photos for now (video is Phase 3; the model does not block it); FORMER class members get no history access (`classes.former_member_history_access` is not applied yet); a rejected contribution's photos and an expired story's photos are deleted; scheduled posts that fail the gate go back to draft rather than retrying.
- **Error codes:** `feed.feature_disabled`, `feed.access_denied`, `feed.post_not_found`, `feed.story_not_found`, `feed.comment_not_found`, `feed.post_empty`, `feed.story_empty`, `feed.comment_empty`, `feed.too_many_media`, `feed.media_kind_not_allowed`, `feed.media_already_attached`, `feed.tag_invalid`, `feed.too_many_tags`, `feed.media_consent_missing` (422), `feed.media_confirmation_required` (422), `feed.invalid_state`, `feed.schedule_in_past`, `feed.schedule_too_far`, `feed.pin_limit_reached`, `feed.pin_not_published`, `feed.comments_disabled`, `feed.comment_not_allowed`, `feed.reaction_invalid`, `feed.rate_limited`, `feed.contributions_disabled`, `feed.contribution_not_allowed`, `feed.contribution_media_not_allowed`, `feed.contribution_needs_tag`, `feed.contribution_tag_not_own`, `feed.not_pending`, `feed.translation_disabled`, `feed.translation_unavailable` (503), `feed.translation_language_invalid`, `feed.translation_empty`, `feed.translation_rate_limited`, `feed.report_rate_limited`, plus `media.*`, `class.not_found`, `class.archived`, `school.not_found`, `student.not_found`, `invalid_cursor`.
- **Open questions (product/legal):** (1) Should the confirmation also be required when students *are* tagged (others may appear untagged)? Today it is required only with no tag, as specified. (2) Are guardian and student names to be shown to other families at all (ClassDojo shows first names)? Today they are not. (3) Is the "seen by" list of names wanted for teachers? Today only the count. (4) May a school switch on translation before a provider contract with a data-processing agreement exists, and which providers are acceptable for children's text? (5) Retention of photos after a post is deleted, a contribution is rejected or a story expires (deleted now/at once/after seven days) versus safeguarding evidence needs (M13). (6) School-scope contributions and contributions to stories. (7) Whether a guardian whose link was revoked should lose the notifications already delivered. (8) Consent withdrawn by a guardian link change (not by a new decision) hides photos at once but does not notify authors (only `ConsentChanged` does).
- **Tests:** 56 feed tests (`apps/backend/src/test/java/com/nemaz/school/feed`: consent-gate matrix, scheduler idempotency, withdrawal hiding and its redelivery, media access per persona, contributions, stories and their expiry, comments, reactions under concurrency, seen-by, reports, translation with the fake adapter, keyset paging, the home feed) and 34 endpoint cases in `AuthorizationMatrixTest`; the whole backend suite is 929 tests. Smoke-tested over HTTP on a separate database and port with real presigned uploads (class post, gate failures, untagged confirmation, scheduled post blocked after a withdrawal, approved contribution, story, comment, reaction, home feed, signed-URL access of a guardian, a stranger and after withdrawal, translation off).
- **Not done / follow-ups:** web and mobile screens (M8b/M8c), video and poster frames (Phase 3), realtime push of new posts to open apps, report moderation (M13), school-scope contributions, translation provider, history access for former class members, an index-backed "posts about my child" filter, an author notification when consent changes through a guardian-link change.

**As built: M8c mobile feed (26 Nov 2026).** The Expo app shows Class Story, School Story and the feed for teachers, parents and students; only the API change below touches the backend. Code: `apps/mobile/src/lib/feed` (pure, tested), `components/feed`, routes `app/feed/*`, the `feed` tab of `(guardian)` and `(student)`.
- **Screens:** `feed/class/[classId]` (story strip, new post, new story, "share with the class", my posts, approvals when the API says `canModerate`, then the post list), `feed/school/[schoolId]` (school-wide stories and posts; the school-wide composer only when `canPostSchoolWide`), `feed/post/[postId]` (the post in full, comments, translation, report), `feed/story/[storyId]` (a story opened from a notification), `feed/compose` (post, edit, story, family contribution), `feed/my-posts` (all statuses with the rejection reason and the publish error), `feed/approvals` (queue plus a review sheet). Entry points: the class screen ("Class feed"), the Classes tab ("School news"), the parent's **Feed** tab (home feed across children with a child filter and the stories of `/me/stories`; a "Share with the class" button where `canContribute`) and the student's **Feed** tab (class chips plus the school feed).
- **Composer:** text, up to 10 photos from the camera or the library (the existing helper converts to JPEG and shrinks on the device; per-photo progress, retry, a caption used as alt text, reorder, remove; an upload that is never attached is deleted), tagging from the class roster (a school post searches the school, a parent tags only the children `contributionStudentIds` allows, a student nobody), comments switch, **publish now / schedule / draft**. A schedule is picked as a school-local date and time with steppers (a native picker would speak the phone's time zone) and converted with `lib/feed/schedule.ts` using `FeedCapabilities.timeZone`. **Consent gate:** `feed.media_consent_missing` shows the student names and offers "remove those tags" (the text and photos stay in the screen), `feed.media_confirmation_required` and every photo-with-no-tag state show the explicit "no student without media consent appears" statement, which resets whenever the photos or tags change. Create calls send an `Idempotency-Key` that is reused only for a retry of identical content after a failure without an answer. Leaving with unsent content asks first; there is no offline queue (an offline message says so).
- **Stories:** a strip of round thumbnails (bold ring until seen), a full-screen viewer: tap right/left, press and hold to pause, swipe down to close, progress bars, six seconds per photo (eight for a caption only), visible previous/next/pause/close buttons, no auto-advance with "reduce motion" or a screen reader, one view call per story, expired stories dropped by `expiresAt` (never by the server job), delete for `canDelete`, the MEDIUM variant only here.
- **Feeds:** FlatList, 15 posts a page, keyset cursors, **at most 8 pages kept** (then "pull down to refresh"), pull to refresh, ids de-duplicated across pages (a re-pinned post can sit on two), "seen" reported for cards that stay 60% visible for 0.8 s, optimistic reactions across every list with rollback, an overflow menu from the post's own `rights` (`overflowActions`), photos in a grid with THUMB/SMALL (never MEDIUM in lists), `mediaWithheld` as a placeholder (and a warning for the author), tags shown as the payload sends them, author name only when sent else the role label, translation hidden unless the school switched it on (403/503 remove the control).
- **Privacy:** every feed query lives under the `feed` key which `isPersistableFeedQuery` keeps out of the persisted query cache (SQLite); pages are dropped five minutes after their screen closes; photos are shown with `expo-image` `cachePolicy="memory"` (no disk cache) and signed links are never persisted; the picker's and the converted copies on the device are deleted after upload and when the screen closes; no post, comment, story, name or link is logged or written to AsyncStorage. No student avatar images are fetched in the feed (initials only).
- **Notifications:** `/feed/posts/{id}` opens the post, `/feed/stories/{id}` the story, `/feed/approvals` the queue for a teacher (anyone else lands on their home); `NEW_POST`, `POST_APPROVED`, `POST_REJECTED`, `POST_PUBLISH_BLOCKED` and `POST_MEDIA_WITHHELD` all point at a post. A push carries no school id, so the post/story/queue screens find it by asking the caller's schools in turn (`/me` memberships and children's schools; 404 means "next"); a tap in the inbox passes `schoolId` of the inbox row.
- **API change:** `FeedCapabilities.timeZone` (the class's campus or the school's IANA zone), because a teacher has no `school.read` and no other payload tells the phone the school's zone; openapi and generated types regenerated.
- **Tests:** about 125 new unit tests (`lib/feed/*.test.ts`, the notification routes): real API payloads (`contract.test.ts`), page merge/dedupe, optimistic reactions, consent-error parsing, composer state and payloads, idempotency keys, schedule time-zone maths (daylight saving, past, 90 days), story timing and expiry, media layout and variants, post model and menu rights, persistence exclusion; the mobile suite is 546 tests. The `mobile.feed.*` catalog (247 keys, en and am) is plural-free.
- **Smoke test (own database, API on :8193) and what was not seen on a device:** the API flows of every screen were driven over HTTP with real presigned uploads and the 422 payloads fed to the app's own parsers: class post, both gate failures with names, recovery with the same uploaded photos, untagged confirmation, consent grant then tagged post, parent home feed and child filter, THUMB/SMALL/MEDIUM signed links (an original is refused), reactions, comments, seen counts, a story with view tracking and a 24 hour expiry, a family contribution approved and another rejected with a reason, scheduling/unscheduling/editing/pinning, translation off, report, keyset paging, a student signed in by class code, a consent withdrawal hiding photos (404 on the signed link, text kept, author still sees them) and the notification paths. **No screen was tapped on a device or simulator:** the Xcode build for the simulator stalled in the asset-catalog compile (`actool` waiting on the simulator agent, an iCloud-Desktop/CoreSimulator problem seen before), so the screens were only bundled (`expo export` for iOS and Android), type-checked, linted and tested; run the first checks on a phone (camera permission copy, the story viewer gestures, the schedule steppers, nested sheets) before release.
- **Not done:** video, comment hiding by moderators (the API exists, no button), school-scope contributions, translating comments, hiding a story's viewer list (only counts exist anyway), realtime new-post push into open apps (the feed refreshes on pull and when a screen opens).
**As built: M8b feed web UI (26 Nov 2026).** The screens for steps 1-7 on the web; the API is M8a's, with one addition: `FeedCapabilities.timeZone` (the school's IANA zone, the class's campus zone when it has one), because teachers have no `school.read` and scheduled times must be picked and shown in the school's zone, never the browser's. Code: `apps/web/components/feed/*`, pure logic in `apps/web/lib/feed/*` (queries, composer state and payloads, consent-error parsing, schedule time zones, optimistic reactions, keyset merging, story timing, cache patching, family targets), the menu and ability functions in `lib/school/access.ts`.
- **Routes:** class tab `?tab=feed` on `/s/{slug}/classes/{id}` (story strip, class feed with "Load more", composer, "My posts" by status, approvals link); `/s/{slug}/feed` (school-wide posts and stories, school composer for `school.feed.manage`; menu entry `feed` for teachers, other staff and feed managers); `/s/{slug}/feed/approvals`; the parent home feed on `/family` (child filter, stories, contribution dialog, "What I shared"); the student feed under the points on `/student-app`; notification targets `/feed/posts/{schoolId}/{postId}` and `/feed/stories/{schoolId}/{storyId}` (UUIDs validated, school taken from the notification's own `schoolId`) and `/feed/approvals` (school slug resolved from `/me`, like `ABSENCE_REPORTED`). Settings: the `feed.*` definitions have localized titles and descriptions on the school settings page.
- **Consent UX:** `GateNotice` shows the API's own `detail` for 422 `feed.media_consent_missing` (the students it names, "Remove these tags" or ask the school to record consent) and `feed.media_confirmation_required` (checkbox "I confirm no student without media consent appears in these photos"). The composer shows that checkbox whenever there are photos and no tag, and drops the confirmation when photos or tags change (it was given for another set). A refused request created nothing, so the composer keeps the text, photos and captions; a retry sends the same `Idempotency-Key` for the same body and a new one when the body changed. A contributor never gives the confirmation (the approver does, in the approve dialog, where tags can be adjusted).
- **Privacy in the UI:** nothing is filtered on the client; `mediaWithheld` with no media is a placeholder ("the photos are not shown right now ... the text is still here"), with media (author, moderators) a warning; authors show their name only when the API sent one, otherwise a role label; seen-by only when `seenCount` is present; post text, captions, names and signed URLs are never in URLs, storage or logs (query data is memory only, ids only in paths); photos use THUMB/SMALL in lists and MEDIUM only in the viewer.
- **Stories:** full-screen viewer with a progress bar per story (6 s photo, 8 s text), arrows/Space/Escape, one view per story (`POST .../view`, the ring turns off at once), "ends in 3 h 20 min", delete for author and moderators, `prefers-reduced-motion` stops auto-advance. Stories are listed per photo (not grouped by author), oldest first inside the viewer.
- **Verified:** vitest (pure logic: schedule zones incl. DST gap, consent-error parsing, composer state and payloads, optimistic reactions, page merge/dedupe, story timing, cache patching, menu rules, family targets, access and deep links); `next build`; a Playwright run against a real API on a separate database with real image uploads: post with consent, 422 consent-missing and recovery (untag), untagged confirmation, schedule (zone shown, past refused), draft and publish from the menu through the gate dialog, pin, edit, moderator hide with reason, reschedule/unschedule/delete, story create/view/keys/pause/auto-advance/reduced motion/delete, comment and reaction, report, parent feed with child filter, contribution then approval (gate and untag), rejection with reason shown to the contributor, withdrawal turning photos into the placeholder while the text stays, custody-restricted guardian sees nothing, another family sees a guardian commenter as a role label, school-wide post reaching the family feed, notification deep links, translation 503 handled, Amharic, 390 px.
- **Not done / follow-ups:** the student feed was built and typechecked but not driven in a browser (students sign in on the phone); student contributions on the web; grouping stories per author; uploads removed from a composer before sending stay as orphan `POST` media until the media retention removes them (no delete call yet); comment translation shares the post's setting but is only exercised against the 503 path; realtime refresh of the feed; Amharic strings need native review (`school.feed.*`, `family.feed.*`, `nav.feed`).

### M9 Messaging (≈ 3–4 weeks)
Tables: DB §4.11.

1. **Conversation types:** DIRECT, GROUP, CLASS and SCHOOL. A parent↔teacher thread carries `context_student_id`.
2. **Policy engine:**
   - checks relationships (§29) and the school's `messaging.*` settings (D3)
   - applies the **safeguarding floor:** adult↔student direct messages add guardian observers or give admins visibility, and are audited
   - honours `user_blocks`
3. **Sending:**
   - an idempotent send API (`client_message_id`)
   - WebSocket delivery
   - push to offline members, respecting quiet hours and teacher hours
   - scheduled messages
4. **Read state:** the `last_read_message_id` watermark, plus per-message receipts in DIRECT conversations ("Seen").
5. **Content:**
   - attachments through media
   - reactions
   - edit and delete with history
   - translate in place, the same as M8
6. **Groups:** class parent groups and staff groups. Who can create and moderate them comes from settings (§31).
7. **Mobile:**
   - inbox grouped by child for parents
   - offline outbox for messages
   - voice notes behind a flag

### M10 School communications centre (≈ 2 weeks)
Tables: DB §4.12.

1. Compose an announcement with an explicit audience: school, campus, grade, class or specific users, and target roles.
2. Draft, schedule, publish, edit (with revisions) and withdraw. The audit trail is complete.
3. Resolve recipients at publish into `announcement_recipients`, reaching guardians through their children.
4. Delivery through the notification service: in-app and push at launch. Email and SMS through adapters for each country, behind flags (§125).
5. Read and acknowledgement tracking is kept separate from delivery. An admin report shows delivered, failed, read and acknowledged, with a "remind unacknowledged" action.
6. URGENT and SAFETY types use their own quiet-hours policy.

**As built: M10a announcements backend (26 Nov 2026, backend only).** Steps 1-6 as an API contract (`openapi.json`); the web and mobile screens are M10b/M10c. New module `communications` (`api`, `application`, `infrastructure`; public: the events `AnnouncementPublished` and `AnnouncementNoticeRequested`, the SPIs `SmsAdapter` and `SmsOptOuts`); migration `V9_01__communications.sql`; settings, permissions and notification types are repeatable seeds. Deviations are in DB §4.12 and §4.13. The SMS provider checklist is `docs/ops/sms-provider-adapter.md`.
- **Text:** plain text only (title 200, body 5000 characters, `bodyFormat: "PLAIN_TEXT"`). Nothing is interpreted as markup by the server or expected from clients (they render text as text and may link http and https); the server drops control characters and the invisible bidirectional and zero-width characters that can disguise a title, and normalizes line ends. A `<script>` is just characters. Translations are a map language -> `{title, body}` written by people (D5); the notification title is in the recipient's language when a translation exists. **No attachments yet:** `media` has no announcement purpose (a purpose needs a `PurposeRules` row, a policy and allow/deny tests); documents and images are deferred.
- **Types and permissions:** NORMAL, IMPORTANT, URGENT, SAFETY (`announcement_type`); an acknowledgement can be required above NORMAL. `school.announcements.manage` (PLATFORM/COUNTRY/ORGANIZATION/SCHOOL admins; not CAMPUS_ADMIN, who announce to the classes they manage through `class.manage`) may address the school, a campus, a grade level, any class, chosen people and the STAFF role, read every report and remind; `school.announcements.urgent` (same roles) is needed for URGENT and SAFETY; an ACTIVE LEAD or CO_TEACHER (setting `announcements.teachers_can_announce`, default on) and a class manager may address CLASS audiences of their own classes and chosen guardians or students of them, to GUARDIAN and STUDENT only. Subject teachers and assistants cannot announce. STUDENT is addressable only when `announcements.students_enabled` (default off). Chosen people (USER audiences) must be related to the school (managers) or to the author's classes (teachers): an unrelated id is 400 `announcement.audience_user_invalid` without saying why, so ids cannot be enumerated across schools. Everything named (class, campus, grade) is checked against the URL's school.
- **Lifecycle:** DRAFT, SCHEDULED, PUBLISHED, WITHDRAWN; `mode` DRAFT|PUBLISH|SCHEDULE on create, `/publish`, `/schedule`, `/unschedule`, `PATCH`, `/withdraw`, `DELETE` of drafts. Publishing re-authorizes the audience against the acting user (the author when the scheduler publishes) and is rate limited (30 per user per hour). **Scheduler** `AnnouncementMaintenance.publishScheduled` (every minute, ShedLock `communications.publish-scheduled`, returns `Integer`): one transaction per announcement, `FOR UPDATE SKIP LOCKED`, idempotent; an announcement whose author may no longer send it (left the class, setting turned off) or that has nobody to receive it goes back to DRAFT with `scheduleErrorCode` and the author gets `ANNOUNCEMENT_PUBLISH_BLOCKED`. **Edit after publishing:** only title, body, translations and language; the previous version is stored as a revision; the editor must say `material` (true tells the recipients with `ANNOUNCEMENT_UPDATED`, false is a silent correction) or gets 400 `announcement.material_required`. **Withdraw:** the row stays as an audited tombstone: recipients see title, `withdrawnAt` and the public reason, not the body; the recipients are told (`ANNOUNCEMENT_WITHDRAWN`), pushes still held for quiet hours and email or SMS not yet sent are cancelled; idempotent. Every step is audited (`announcement.create|update|schedule|unschedule|publish|publish_blocked|withdraw|delete|remind|report.read|report.export`) with ids, type, audience kinds and counts only, never the title or text.
- **Audience and recipients (the rule):** at publish one SQL statement (`AudienceResolver`) writes the snapshot `announcement_recipients`. Guardians are reached through their children: ACTIVE, not custody-restricted `student_guardians` with `can_receive_notifications` of students ACTIVE in the school (SCHOOL), in an ACTIVE class of the campus (CAMPUS; also students enrolled at the campus without a class), grade level (GRADE_LEVEL) or class (CLASS). Pending, rejected and revoked links, restricted guardians and former class members reach nobody. One entry per adult however many children (`via_student_id` = the first child); one per person across roles (STAFF, then GUARDIAN, then STUDENT); only ACTIVE accounts; never the author. Students only when the school allows. Staff by role: all ACTIVE school staff (SCHOOL), staff of the campus or teachers of its classes, the teachers of the grade's or class's ACTIVE classes. The snapshot is never rewritten (a link revoked later does not change what was sent or who is counted). **Late joiners and reading:** a person can open an announcement if (1) they are the author or a school announcement manager, (2) they are in the snapshot and still hold that role in the school (a revoked or restricted guardian, a withdrawn student, a former employee loses access although they were notified), or (3) the audience rule matches them now and it was published in the last 180 days (a guardian linked later, a student who joined the class later): they find it in `GET /schools/{s}/announcements` and the single read, are not notified, are not counted as recipients (their reads are the report's `lateReaders`). `GET /me/announcements` (the inbox) is the snapshot only. `AnnouncementAccess.readable` (person to audience) and `AudienceResolver` (audience to people) are two queries of one rule; `AnnouncementAudienceTest` proves they agree for every audience kind.
- **Delivery:** after commit (`@ApplicationModuleListener` on `AnnouncementPublished`), chunks of 500 recipients, each its own transaction that claims the next recipients without `notified_at` (`FOR UPDATE SKIP LOCKED`), creates the notifications through `NotificationService.notify` (`ANNOUNCEMENT_PUBLISHED`, or `ACK_REQUESTED` when an acknowledgement is asked for; URGENT priority for URGENT and SAFETY; title in the recipient's language and the school name, never the body or a child's name; `data.path` `/announcements/{id}`), stamps `notified_at` and `notification_id`, and plans email and SMS. A crash or a second worker loses and duplicates nothing; the resume job (`communications.resume-deliveries`, every minute, `Integer`) continues deliveries older than two minutes and open notices, sends email and SMS that came due, and frees claimed rows after 15 minutes. `delivery_completed_at` marks the end. Notices (update, reminder, withdrawal) are delivered the same way (`announcement_notices`, keyset by recipient, people who already have the notice are skipped).
- **Email and SMS:** per announcement `channels` (IN_APP always, PUSH default, EMAIL, SMS). Email: `announcements.channel.email.enabled` (default on) and only when outbound email is configured (`EmailSender.configured()`); the mail names the school and the title and says to open the app, the full text only with `announcements.email.include_body` (default off). SMS: `announcements.channel.sms.enabled` default off, then only the types in `announcements.sms.types` (default URGENT, SAFETY), at most `announcements.sms.max_per_announcement` (500) and `announcements.sms.max_per_day` (1000 per school day) texts, counted under an advisory lock; the text is the school name and the title. `SmsAdapterRegistry` picks the adapter by the number's country (libphonenumber), `*` as fallback; the only adapter is `SystemSmsAdapter` (the platform sender: console), so no provider is chosen. Opt-outs are always honoured, urgent or not: `PUT /me/communication-channels` (email, sms) and numbers that opted out with the provider (`SmsOptOuts.optOut`, which the person cannot undo from the app). A non-urgent email or SMS waits for the end of the recipient's quiet hours (`channels_not_before`); urgent and safety do not. SMS is at-most-once (a row claimed but not finished becomes FAILED `interrupted`), email at-least-once. Reminders, updates and withdrawals never use email or SMS. A channel the school no longer allows when a scheduled announcement publishes is dropped (audited); by hand it is 400 `announcement.channel_unavailable` or `announcement.sms_not_allowed`.
- **Delivered, read, acknowledged:** three records. Delivered: `announcement_recipients.notified_at` (in-app) plus the push, email and SMS state. Read: `POST .../read` (first time kept, idempotent). Acknowledged: `POST .../acknowledge` (idempotent, also marks read, keeps the first time and the revision acknowledged; 409 `announcement.ack_not_required`, 409 `announcement.withdrawn`). Neither is audited row by row (own data, high volume); the report counts them. Marking an announcement read does not mark its notification read (the client calls `POST /notifications/{id}/read`).
- **Report** (author and `school.announcements.manage`; a reader gets 403, anybody else 404): `GET .../report` (recipients, delivered, failed, read, acknowledged, per channel sent/failed/skipped/pending, late readers, reminders), `GET .../report/recipients?filter=UNREAD|UNACKNOWLEDGED|ACKNOWLEDGED|FAILED` (keyset by recipient; name, role, the child a guardian was reached through, masked phone and email, push/email/SMS state, read and acknowledged; audited without names), `GET .../report.csv` (BOM, CRLF, headers in the caller's language, `CsvCells.safe`, no contact details, audited, 10 per 10 minutes, 20000 rows). `failed` = recipients with a failed push, email or SMS; `delivered` = the in-app notification exists. **Remind unacknowledged** `POST .../remind`: in-app and push to the delivered recipients who have not acknowledged (decided while delivering), URGENT for urgent announcements, once per `announcements.reminder.min_hours` (default 24; 429 `announcement.reminder_too_soon` with Retry-After), at most 5 per announcement, 10 requests per user per hour, audited.
- **Quiet hours and the floor (extends M2b minimally):** two columns of `notification_types` (data): `defer_in_quiet_hours` (a push inside the recipient's own quiet hours is QUEUED until the end of the window instead of being dropped; the in-app item is immediate) and `urgent_overrides_preferences` (an URGENT notification is pushed even when the user switched the type's push off). Both on for ANNOUNCEMENT_PUBLISHED, ACK_REQUESTED, ANNOUNCEMENT_UPDATED, ANNOUNCEMENT_REMINDER; no other type changed. **The floor of urgent and safety announcements:** the in-app item always, and a push to every active device whatever the push preference and the quiet hours; the only ways out are removing the device or the OS-level switch (email and SMS stay opt-out-able). The school-level `notifications.quiet_hours` setting is still not applied (only the user's own window counts, as before). `GET /me/notification-preferences` types now carry `urgentOverridesPreferences`.
- **Settings, permissions, flag:** `announcements.teachers_can_announce` (true), `announcements.students_enabled` (false), `announcements.channel.email.enabled` (true), `announcements.channel.sms.enabled` (false), `announcements.sms.types` (URGENT, SAFETY), `announcements.sms.max_per_announcement` (500), `announcements.sms.max_per_day` (1000), `announcements.email.include_body` (false), `announcements.reminder.min_hours` (24); permissions `school.announcements.manage` and `school.announcements.urgent` (PLATFORM/COUNTRY/ORGANIZATION/SCHOOL admins); feature flag `announcements` (existing; 403 `announcement.feature_disabled`).
- **Error codes:** `announcement.not_found`, `.feature_disabled`, `.access_denied`, `.audience_not_allowed`, `.audience_invalid`, `.audience_user_invalid`, `.students_disabled`, `.urgent_not_allowed`, `.ack_not_allowed`, `.ack_not_required`, `.channel_unavailable`, `.sms_not_allowed`, `.sms_blocked_by_provider`, `.language_invalid`, `.title_required|too_long`, `.body_required|too_long`, `.reason_too_long`, `.change_note_too_long`, `.material_required`, `.locked_after_publish`, `.withdrawn`, `.invalid_state`, `.not_published`, `.no_recipients` (422), `.schedule_required|in_past|too_far`, `.rate_limited`, `.reminder_too_soon` (429), `.reminder_limit_reached`, `.nobody_to_remind`, `.export_rate_limited` (429), `.export_too_large` (422), plus `class.not_found`, `class.archived`, `school.not_found`, `invalid_cursor`. 404 for a school or class the caller cannot see; guardians (not school members) who are related to the school get 403 where members would.
- **Open questions (product/legal):** (1) Attachments (documents, images): a media purpose and its policy. (2) May announcement SMS be sent without prior opt-in (today everybody is opted in until they opt out, only urgent and safety by default)? Per-country sender registration and templates. (3) Should a guardian see an announcement to a class their child left (today: only while in the snapshot with a valid link, else only if the audience matches now)? (4) Should students ever be addressed directly (off by default; from what age)? (5) Retention: announcements, revisions, the recipient snapshot and withdrawn tombstones are kept (no purge job yet); the audit rows carry no text. (6) Should the school-level `notifications.quiet_hours` setting become the default window of people without their own? (7) A campus administrator may announce only to classes they manage, not to a campus as such; a campus-scoped permission would need the same checks at campus scope. (8) Should reminders also go by SMS or email (today never)?
- **Tests:** about 65 in `communications` (`AnnouncementAudienceTest`: resolution matrix, siblings, restricted/pending/revoked, students, staff, teachers, explicit users, late joiners, resolver-versus-reading agreement; `AnnouncementLifecycleTest`; `AnnouncementDeliveryTest`: 1250-recipient chunked delivery with two concurrent workers and an interrupted run, push, quiet hours, the URGENT floor, withdrawal while held; `AnnouncementChannelsTest`: email and SMS defaults, per-country adapters, opt-outs, caps, quiet hours, stuck rows; `AnnouncementReportTest`; `AnnouncementInboxTest`; `BodyTextTest`, `SmsAdapterRegistryTest`) and 27 endpoint cases in `AuthorizationMatrixTest`.
- **Not done / follow-ups:** web and mobile screens (M10b/M10c), attachments, voice, delivery receipts and a real SMS adapter (checklist in `docs/ops/sms-provider-adapter.md`), digest mode, announcement templates, the calendar link (`event_id`, M11), a retention job, marking an announcement's notification read when it is opened.

**As built: M10b announcements web UI (26 Nov 2026).** Scope items 1-7 on `apps/web`; mobile is M10c.
- **Routes.** Inbox `/announcements` (guardians, staff, students where allowed; `/me/announcements` with unread / needs-acknowledgement filters and a sort, summary badges in the header, in the family tabs); one announcement `/announcements/{schoolId}/{announcementId}` (the notification deep link: `/announcements/{id}` + `schoolId` from the notification or its `data` is mapped to it in `lib/notifications/deep-link.ts`, ids checked as UUIDs); school workspace `/s/{slug}/announcements` (tabs School, Mine, and for managers Everything written, with a status filter), `/new`, `/{id}` (detail with the author's panel), `/{id}/edit`, `/{id}/report`. Menu item from `canOpenAnnouncements` in `lib/school/access.ts` (teachers, staff, `school.announcements.manage`); everything inside is driven by `GET .../announcements/capabilities` and the `can.*` flags.
- **Composer** (`components/announcements/composer-view.tsx`, `audience-builder.tsx`, `people-picker.tsx`, pure logic in `lib/announcements/composer.ts`): type picker (urgent/safety only with `urgentAllowed`, with the warning that they ignore quiet hours and mute), plain-text title and body with counters, language and hand-written translations, audience kind (school, campus, grade, classes, chosen people) from the capabilities, roles, an explanation of the API's rule in words (no client-side resolution), channels (SMS only when offered for the type, caps shown), acknowledgement switch (above NORMAL), publish now / schedule (school time zone from the capabilities, never the browser's; 180 days) / draft. **Publishing is create-as-draft, then count, then confirm**: the text is saved first, the API's `GET .../preview` gives the head count, a dialog confirms (a checkbox for 25 or more recipients and for urgent/safety) and calls `/publish` or `/schedule`. Errors (422 no recipients, 429, anything else) stay in the dialog or form; the draft text is never lost, a retry of the same body reuses its `Idempotency-Key`, and a second press updates the draft instead of creating another. Chosen people come only from lists the API already gives the caller: managers search students and staff, teachers pick a child from the roster of one of their own classes; guardians are reached through the child's ACTIVE, not custody-restricted links.
- **Reading.** Titles and bodies are rendered as text only (`PlainText`, `lib/announcements/text.ts`: React escaping, links only for `http(s)` without credentials). Opening a published announcement marks it read (not for the author or a manager reviewing it); acknowledge, and again after a material edit (`mine.acknowledgedRevision < revision`); withdrawn announcements are a tombstone; no "new" badge for late joiners; translations are chosen by the reader. Urgent and safety are an icon, a word and a border, never colour alone. The notification's own read mark is set where it is opened (bell, notifications page), which knows its id.
- **Author side.** Edit after publishing asks "Notify people about this change" (material) before saving, edit history, withdraw with a public reason, delete a draft, schedule / unschedule; the report page (`ReportView`) has counts, per-channel counts, the recipient table (filter, keyset paging, masked contacts as sent), CSV through the BFF as a `no-store` Blob, and "remind unacknowledged" with the API's own 429 message plus its `Retry-After` (`ApiRequestError.retryAfter`).
- **Preferences and settings.** `/account/notifications` has the "Email and text messages" card (`/me/communication-channels`) and a lock note on types with `urgentOverridesPreferences`; the nine `announcements.*` settings have localized titles on the school settings page.
- **API change.** `AnnouncementCapabilities` gained `timeZone` (the school's IANA zone), `smsMaxPerAnnouncement` and `smsMaxPerDay` (the composer shows the caps and schedules in the school's zone); `openapi.json` and the generated types were regenerated.
- **Not done:** attachments, a school filter in the inbox for people in several schools, names of previously chosen people when editing a draft (only a count), a school-wide staff list for people without `school.staff.manage`.
- **Tests:** vitest for access, composer state and payloads, schedule, text safety, view helpers (inbox query, acknowledge, report), deep links and settings ids (`lib/announcements/*.test.ts`, `access.test.ts`, `deep-link.test.ts`); verified in a browser against a real API (compose, publish, schedule, urgent, inbox, acknowledge and re-acknowledge, material edit, withdraw, report, CSV, remind and its 429, late joiner, limits per role, XSS text, Amharic, 390 px).

### M11 Calendar & events (≈ 1–2 weeks)
Tables: DB §4.14.

1. Native events with scope, recurrence rules (RRULE) and RSVP. Parent-meeting events have `regarding_student_id`.
2. The calendar API combines native events with holidays and academic period boundaries (and later assignments, sessions and tutoring) and applies access filtering (R-17).
3. Month, week and agenda views on web and mobile, with a Gregorian or Ethiopian display toggle that uses `packages/calendar`.
4. UPCOMING_EVENT reminders through scheduled jobs.

### M12 Student portfolios — basic (≈ 2 weeks)
Tables: DB §4.16 (without collections).

1. **Students create items:** photo, drawing (an in-app canvas), document scan, text, short video or audio, with a reflection field.
2. **Teacher quick capture** from the class view (§126).
3. **Review workflow:** submitted → approved, changes requested or rejected, with history in `portfolio_approvals`.
4. **Visibility:** resolved from school settings and item visibility. Guardians see approved items and can leave encouraging comments.
5. Approved items can optionally be shared to the class story. This needs consent and approval.

### M13 Moderation, safeguarding & platform admin (≈ 2–3 weeks)
Tables: DB §4.17.

1. A "Report" action on posts, comments, stories, messages and users creates `content_reports`, which are grouped into `moderation_cases`.
2. **Moderation queues:** one per school for school admins, and one for the platform (PLATFORM_MODERATOR). Decisions include hide, remove, warn and suspend, with history in `moderation_actions`.
3. **Safeguarding incidents:**
   - restricted access for SAFEGUARDING_LEAD
   - encrypted narrative
   - notes
   - escalation from a case
   - every read is audited
4. **Platform admin:**
   - users (search, lock, unlock)
   - schools
   - plans
   - flags
   - an audit log viewer
   - support view (read-only, audited)
   - system health

### M14 Localization, low-data & release-gate instrumentation (≈ 2 weeks, ongoing from M3)
1. Complete Amharic UI strings and get them reviewed by native speakers. Add Afaan Oromo, Tigrinya and Somali catalogs as translations become available, with a per-school language list (§110).
2. **Low-data pass:**
   - image variants everywhere
   - pagination limits
   - compressed requests
   - "data saver" mode (no autoplay, thumbnails only)
   - measure payload sizes on 3G profiles
3. **Analytics events for the §133 gates:**
   - weekly active teachers
   - classes created
   - import success
   - guardian link completion
   - announcement read rate
   - attendance completion time
   - offline sync success
   - report response time

   Send them to a privacy-respecting analytics store. Analytics are not used to profile children.
4. **Accessibility:** screen-reader labels, dynamic type, colour contrast.

### M15 Hardening & pilot launch (≈ 3–4 weeks)
1. **Security:**
   - threat model
   - dependency and container scanning
   - OWASP ASVS L2 checklist
   - external penetration test focused on authorization, IDOR and tenant isolation
2. **Authorization audit:** generate the role × endpoint matrix from the tests, then review it manually against DB §5.
3. **Load test:** pilot-scale traffic plus 10× headroom, covering feed, messaging and attendance sync. Tune indexes and set up partitioning.
4. **Privacy:**
   - publish the privacy notice
   - review data processing agreements (R2, FCM, SMS and translation providers)
   - review Ethiopian data-protection requirements with counsel (§131)
5. **Operations:** runbooks, on-call, alerting, backup and restore drill, incident process.
6. **Launch:**
   - app store submissions (EAS)
   - staged rollout
   - pilot schools onboarded with training material for teachers and parents in Amharic and English
7. Review the release gates after 4–6 weeks of pilot use before starting Phase 2 (§112 says: prove regular use first).

---

## Phase 2 — School learning (≈ 4–5 months)

Scope: §113, §124 (full) and §127. Tables: DB §4.21–4.23, plus the P2 columns (`grade_levels.academic_level_id`, `subjects.curriculum_subject_id`, `schools.education_system_id`).

### M16 Curriculum foundation
1. Education systems, curriculum versions, levels, subjects, topics and objectives. A platform-admin editor, plus CSV import of the Ethiopian national curriculum (with source, license and owner recorded).
2. A school adopts a curriculum version and maps its grades and subjects to it.
3. A tagging component (pick objectives) that can be reused in assignments, assessments, questions, lessons, portfolio items and resources.

### M17 Assignments & submissions
1. Assignments scoped to a class (and later a course), with attachments, targets, scheduling, due dates and late policy.
2. Student submissions: text, photos and files. Offline drafts. Guardians can help young children submit (`submitted_by`).
3. Teacher review: return, request resubmission, feedback. Notifications for NEW_ASSIGNMENT and returned work. Due dates appear on the calendar.

### M18 Assessment, grading & quizzes
1. Grading schemes and bands (templates per country, overrides per school), and rubrics.
2. Assessments: offline exams and participation, or graded assignments or quizzes. A gradebook grid on web with keyboard entry. Subject teachers are limited to their own subject.
3. **Shared quiz engine:** question types, attempts, auto-grading, and manual grading for essays. Correct answers are never sent to students before results are released.
4. Result publication, `period_grades` computed from weights, teacher overrides, report-card PDF generation (`report_jobs`), and parent access to published results.
5. Learning evidence comes from results. Mastery is recorded only by teachers (§127).

### M19 Digital library
1. Resources with ownership, licence and a rights attestation (§35). A takedown workflow through moderation.
2. Access rules (R-06), evaluated on the server. Browse by grade and subject, with search (`pg_trgm`).
3. Reader and viewer (PDF, audio, video) with low-data streaming. Downloads only where allowed.

### M20 Portfolios (full) & teacher preparation
1. Collections, objective tags, formal teacher feedback with rubrics, and featured work.
2. Lesson preparation templates (`classroom_activity_templates`, LESSON_PREP) linked to objectives.

### M21 Data governance
1. Retention policies and jobs, legal holds, data-subject export and deletion requests, and an admin UI for them (§131).

---

## Phase 3 — Online learning (≈ 4 months)

Scope: §114, plus the payment core and video moved here by D4. Tables: DB §4.24–4.26.

### M22 Payment core (moved from Phase 5)
1. A `PaymentService` provider abstraction (§61). The first Ethiopian provider adapter is chosen after commercial evaluation (for example Chapa or telebirr).
2. Orders and order items, then payments, then webhooks. Webhook intake is idempotent and verifies signatures. A status-polling fallback handles providers whose webhooks aren't reliable.
3. A reconciliation report (manual at first), and a sandbox mode for test environments.

### M23 Courses & enrollment
1. **Course builder:** sections, lessons (video, text, PDF, audio, quiz, assignment, live), materials, preview lessons.
2. Ownership and audience rules, including the rule that SCHOOL and CLASS audiences mean the course is school-owned (R-20). Enrollment policies and pricing are separate fields.
3. **Enrollment flows:**
   - open
   - approval required (§42: request → teacher approves)
   - invite
   - school-assigned
   - paid (order → payment → ACTIVE)
4. Course player with lesson progress, offline download where allowed, and completion.
5. Course discussions (COURSE-scoped posts) and learner notifications.

### M24 Video & live sessions
1. A video adapter (Agora) and `video_rooms`. Short-lived join tokens are issued only to the host and ACTIVE enrolled students. Each issue is audited.
2. Live session scheduling, a calendar entry, reminders, and join from web and mobile. Attendance is recorded in `course_live_participants`.
3. Recording only after every participant's RECORDING consent has been checked (R-35). A visible indicator. A stop control. Retention policy. The recording is published to enrolled students as a lesson.

---

## Phase 4 — Tutor marketplace (≈ 4–5 months)

Scope: §115 and §128. Tables: DB §4.27, plus `tutor_profiles` and `data_sharing_grants`.

### M25 Tutor onboarding & verification
1. Create a tutor profile. A teacher can opt in with a **separate** profile (§40). Subjects, languages, qualifications, intro video.
2. Verification requirements for each country, document upload, a platform review queue, and status changes. Only VERIFIED tutors can be listed.

### M26 Services, availability & discovery
1. Services (one-to-one or small group, online or in person, trial), cancellation policies.
2. A weekly availability pattern plus exceptions. A slot generator that respects notice period, booking window and existing sessions.
3. **Discovery:** filters (§48) and **transparent matching** (`tutor_match_requests` and `results` with factor explanations, §128). Nothing from school records is used in discovery.

### M27 Booking, sessions & safety
1. **Booking state machine (R-22):**
   - request
   - guardian approval for minors
   - payment (M22)
   - confirmed: session created or joined, with the exclusion constraint preventing double booking
   - reschedule, cancel (policy-based refunds are manual until M30), no-show, complete
2. Tutoring relationships and guardian **data-sharing grants** (R-21). Tutoring conversations follow the messaging policy, with guardian observers for minors.
3. Session delivery: video rooms for online sessions, location for in-person ones. Attendance. Session notes with visibility.
4. **Trials:** a post-trial assessment shared with the family. Follow-up booking or a series.
5. Reviews tied to completed bookings. A report flow into moderation.

---

## Phase 5 — Marketplace finance, tutor insights & billing (≈ 3–4 months)

Scope: §116. Tables: DB §4.28, plus subscription billing.

### M28 Commissions, earnings & payouts
1. Platform commission and tutor earnings created on order payment. They become available after the session completes and a hold period passes.
2. Payout accounts (tokenized), a payout batch job, payout records and a tutor earnings dashboard.

### M29 Refunds & reconciliation
1. Policy-driven refunds (cancellation, no-show, dispute), provider refund calls, and reversal of commissions and earnings.
2. Automated reconciliation against provider reports, with an exception queue.

### M30 Tutor insights & parent tutoring history
1. Tutor dashboard: upcoming and completed sessions, cancellations, earnings, reliability metrics. These are kept separate from teaching-quality signals and are not punitive (§128).
2. Parent view: tutoring history, shared notes, progress summaries.

### M31 Subscription billing & school fees (D7)
1. Automated school subscription billing (plan prices per country), usage limits from `plan_entitlements`, dunning.
2. **School fees:** fee items, invoices, invoice lines, guardian payment through the payment core, receipts, an arrears report. This domain stays separate from marketplace orders.

---

## Phase 6 — AI Tutor Copilot & Student AI Tutor (≈ 4–6 months)

Scope: §117 and §129–§131. Tables: DB §4.29, plus `session_transcripts`. The model provider is chosen at this point.

### M32 AI platform & governance
1. An AI gateway service:
   - model routing (`ai_model_configs`)
   - versioned prompt templates
   - data minimization (references, not raw records)
   - logging to `ai_generations`
   - usage metering and quotas, kept separate from billing
2. Benchmark sets and evaluation runs for each subject × language. Enablement records gate every combination (§130). A kill switch and suspension.
3. AI_PROCESSING and TRANSCRIPTION consent types. AI-output reports flow into moderation.

### M33 Tutor Copilot
1. Before a lesson: lesson plan, questions and activities from curriculum objectives and authorized notes.
2. During a lesson (optional): tutor-only prompts. Transcription only with consent and a visible indicator. No emotion inference.
3. After a lesson: summary, homework suggestions and a parent-report draft. **The tutor approves before anything is shared or recorded** (`tutoring_session_notes.ai_generation_id`).

### M34 Student AI Tutor
1. A guided-learning chat (explain, hint, practice), grounded in curriculum and library content, with citations and clearly labelled AI-generated content.
2. Practice attempts feed `learning_evidence`, never mastery. Teacher and guardian activity view. Session limits, age-appropriate filters, escalation.
3. Launch with Mathematics, Science and Reading in Amharic and English only, after benchmarks pass (§130). Expand only through the release gates (§133).

---

## Cross-cutting tracks (run throughout)

| Track | Practice |
|---|---|
| **Security** | Deny-by-default authorization. Tenant-isolation tests for every endpoint. Rate limits. Secrets in a vault. Dependency scanning. Yearly external pen test, plus one before each phase launch. |
| **Testing** | Unit tests for domain policies. Integration tests with Testcontainers. Authorization allow/deny matrix. Contract tests against the generated client. Playwright end-to-end for web. Maestro end-to-end for mobile. Offline and sync scenario tests. |
| **Performance** | p95 API < 300 ms for feed, inbox and roster. Mobile cold start < 3 s on a mid-range Android. Low-data budgets per screen. |
| **Localization** | Every string in catalogs. Native-speaker review for each language. Ethiopian calendar display. Country-labelled name and address fields (R-37). |
| **Accessibility** | WCAG 2.2 AA target on web. Platform accessibility APIs on mobile. |
| **Observability** | Traces across web/mobile → API → jobs. Dashboards for sync success, notification delivery, payment webhooks. |
| **Documentation** | Module READMEs. ADRs for major choices (auth, jobs, offline store, providers). Runbooks. A seed script for a demo school. |
| **Compliance** | Consent coverage reporting. Data processing agreements with every processor. Country legal review before each new market (§131). |

---

## Provider decisions to make (and when)

| Decision | Needed by | Recommendation |
|---|---|---|
| Cloud and hosting region | M1.5 | A managed platform (e.g. DigitalOcean App Platform + Managed Postgres, Render, Fly.io) for the pilot; region checked against Ethiopian data-protection law. Cloudflare in front |
| SMS / OTP provider (Ethiopia) | M1 | Evaluate local aggregators on delivery rate and price. Keep it behind the adapter |
| Push | M2 | FCM HTTP v1 (covers iOS through APNs) |
| Machine translation (D5) | M8 | Run a blind quality test for am, om, ti and so on real school messages, then enable the winner with a flag |
| Analytics store | M14 | Self-hosted or EU-region product analytics with no child profiling |
| Payment provider(s) | M22 | One Ethiopian provider first (§119: don't build many providers initially) |
| Video | M24 | Agora, as in §56 |
| AI model provider | M32 | Choose after evaluation on benchmark sets |

---

## Timeline summary (rough, team of ~5)

| Phase | Duration | Cumulative |
|---|---|---|
| 0 Foundation | 3–4 weeks | ~1 month |
| 1 School & family MVP, then pilot | 7–9 months | ~9–10 months |
| Pilot evaluation window | 1–2 months (Phase 2 design runs in parallel) | |
| 2 School learning | 4–5 months | |
| 3 Online learning (payments core, video) | ~4 months | |
| 4 Tutor marketplace | 4–5 months | |
| 5 Finance, insights, billing, school fees | 3–4 months | |
| 6 AI | 4–6 months | |

Phase 1's critical path is M1 → M3 → M5 → M8/M9, because guardian linking and consent gate the feed and messaging. M4, M6, M7, M10–M12 run in parallel tracks once M3 is done.
