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
- **Link status:** PENDING unless channel SMS/EMAIL, `guardians.auto_verify_invite` on and the accepting adult proved the invited destination (then ACTIVE, `SCHOOL_INVITE`, verified_by = inviter). Printed codes always PENDING; the same adult and child are idempotent (existing link, no use consumed). Verifiers: `school.guardians.manage` or (setting `guardians.teachers_can_verify`) the child's LEAD teacher; approval = `SCHOOL_VERIFIED`. Admin-created links (`POST /students/{id}/guardians`) are ACTIVE `SCHOOL_VERIFIED`. Custody restriction, REVOKED, REJECTED and PENDING grant nothing; access is read from the database per request.
- **Consent:** effective document = the school's own (once a version is published) else the country default; current version = latest label in effect, reader's language -> en -> any. Status = most restrictive of the latest decision per current consent-giving link (in-app or paper) and per named paper guardian: any DENIED/WITHDRAWN -> DENIED; else a GRANTED not outdated by a `requires_reconsent` version -> GRANTED; else UNKNOWN (`RECONSENT_REQUIRED` / `NO_DECISION`). `mediaPublicationAllowed` is true only for GRANTED. CONSENT_REQUESTED when a link becomes ACTIVE and when a school publishes a `requires_reconsent` version (idempotent listeners).
- **Tests:** guardian personas in `support.Personas` (`GUARDIAN_A1`, `GUARDIAN_PENDING_A1`, `GUARDIAN_RESTRICTED_A1`, `GUARDIAN_REVOKED_A1`, `GUARDIAN_OF_A2`, `UNRELATED_ADULT`), every new endpoint and the roster/student endpoints in `AuthorizationMatrixTest` (cases may now expect public endpoints for anonymous callers), flow tests `GuardianInvitationFlowTest`, `ParentCodeTest`, `GuardianAccessTest`, `ConsentTest`.
- **Not done / follow-ups:** scanned paper-consent evidence (no `CONSENT_EVIDENCE` media purpose yet; `evidence_media_id` exists), an expiry job for invitations (EXPIRED is derived), invitations of staff/class joins, guardian self-unlink, the M4 import wiring (`invitations.import_job_id`), legal review of the policy texts and native review of the Amharic.

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
