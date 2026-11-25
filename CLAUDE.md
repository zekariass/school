# CLAUDE.md

Guidance for AI agents and developers working in this repository.

## Source of truth

- Product spec: `docs/Education_Platform.md`
- Resolved inconsistencies and confirmed decisions (D1–D7): `docs/Spec_Review.md`. Where they conflict, these override the spec.
- Schema: `docs/Database_Design.md`. Migrations must match it; update the doc in the same change if the schema must differ.
- Delivery order: `docs/Implementation_Plan.md`. Work milestone by milestone and keep its "definition of done".

## Framework versions are newer than most training data

- `apps/web` is Next.js 16: read `apps/web/AGENTS.md` and the bundled docs in `node_modules/next/dist/docs/` before writing web code.
- `apps/mobile` is Expo SDK 58: read `apps/mobile/AGENTS.md` and use the versioned docs. Add native packages with `npx expo install`.
- `apps/backend` is Spring Boot 4.2 on Java 25 (Maven Toolchains; see README). Verify library compatibility with Boot 4 before adding dependencies.

## Commands

```bash
npm run infra:up                  # Postgres 18 :5442, Redis :6390, S3 :8343, Mailpit :1035/:8035
npm run build | lint | typecheck | test     # Turborepo, whole monorepo
npm run codegen                   # regenerate API types + design-token CSS
npm run dev -- --filter=school-backend      # API on :8180 ("local" profile)
```

Before declaring work done, run `npm run lint typecheck test` (or the filtered equivalents) and make sure they pass.

## Monorepo conventions

- Shared packages live in `packages/*` as **source-only TypeScript** (`exports` point at `src/*.ts`, no build step). Next.js (Turbopack) and Expo (Metro) compile them directly. Keep them framework-agnostic: no React Native or Next.js imports.
- Generated files are committed: `packages/api-client/src/generated/`, `packages/design-tokens/generated/`. Never edit them by hand. Regenerate with `npm run codegen`; CI runs `codegen:check`.
- New packages extend `@school/config/tsconfig/library.json`, re-export `@school/config/eslint/library`, and provide `lint`, `typecheck` and `test` (vitest) scripts.
- Node version: `.nvmrc` (26). The design-token script relies on Node's built-in TypeScript type stripping.

## Web conventions (`apps/web`, Next.js 16)

- Every page lives under `app/[locale]/`. Use next-intl's `Link`/`useRouter` from `@/i18n/navigation`, never `next/link` for internal routes. All text comes from `useTranslations` / `getTranslations`.
- `cacheComponents` is on: never read `cookies()`, `headers()`, `params` or `searchParams` at the top of a layout. Read them inside a `<Suspense>` child. `npm run build -w school-web` fails on violations.
- The browser calls the API only through the BFF (`/api/bff/api/v1/...`, via `lib/api-client.ts`). Server Components and Server Actions use `createServerApi()` from `lib/server/api.ts`. Tokens live in httpOnly cookies (`lib/server/session.ts`) and never reach client JavaScript.
- `proxy.ts` auth checks are optimistic only (it also renews the access token silently, see `lib/server/session-core.ts` and `refresh.ts`). Server Components get the user from `requireSession()` (`lib/server/dal.ts`, `GET /me`), inside `<Suspense>`; every Server Action re-validates its input and relies on the API for authorization.
- Mutating Route Handlers under `/api/auth/*` and `/api/bff/*` call `guardOrigin()` (`lib/server/responses.ts`, rules in `lib/origin.ts`). Tokens are set only by Route Handlers and the proxy, never returned to the browser.
- Console data comes from TanStack Query through the BFF; creates use `useSubmit` (`components/console/use-submit.ts`), which sends an `Idempotency-Key`. Show the API's localized `problem.detail` for failures.
- Images come from `<MediaImage mediaId variant>` (signed URL from `GET /media/{id}/url`, cached ~4 min; plain `<img>`, not `next/image`); uploads go through `lib/media/upload.ts` and `<ImageUploader>` (limits come from `GET /media/policies`).
- UI primitives are shadcn/ui in `components/ui`, styled by `@school/design-tokens`. Don't add per-component colors.

## Mobile conventions (`apps/mobile`, Expo SDK 58)

- iOS and Android only. Routes live in `src/app/` (Expo Router); logic lives in `src/lib/`; UI primitives in `src/components/ui/` are built on `@school/design-tokens`.
- Text comes from `use-intl` (`useTranslations`) with the shared `@school/i18n` catalogs, the same keys as the web.
- The API is reached through `src/lib/api.ts`. The refresh token lives only in `expo-secure-store`; never store tokens in AsyncStorage, SQLite or the query cache.
- Photos are converted to JPEG and shrunk on the device (`lib/media/upload.ts`) before upload; show them with `<Avatar>`/`useMediaUrl` (signed links are never persisted in the query cache). Physical-device uploads need `SCHOOL_STORAGE_PUBLIC_ENDPOINT` set to the LAN address (README, Media storage).
- Anything dev-only must be guarded by `__DEV__`, both the route (`Stack.Protected`) and its entry points.
- Keep pure logic free of React Native imports so vitest can test it (`npm run test -w school-mobile`).
- Native folders (`ios/`, `android/`) are generated by prebuild and git-ignored. Configure native behaviour in `app.json` and config plugins.

## Backend conventions (`apps/backend`, `com.nemaz.school`)

- **Modules:** each direct subpackage is a Spring Modulith module with `api` / `application` / `domain` / `infrastructure` subpackages. Use other modules only through their top-level package or events; `ModularityTest` enforces this. `common` is the shared kernel and `system` the technical platform.
- **Errors:** throw `ApiException` with a stable snake_case `code` (e.g. `ApiException.forbidden("class.access_denied")`) and add `error.<code>` to *both* `messages.properties` and `messages_am.properties` (`MessagesTest`). Never return stack traces or internal messages.
- **Events:** publish domain events inside the transaction. Listeners use `@ApplicationModuleListener`, are **idempotent** (delivery is at-least-once), and never call back into the publisher synchronously.
- **Recurring jobs:** `@Scheduled` + `@SchedulerLock(name = "<module>.<job>")`.
- **Time and ids:** inject `Clock`; use `Ids.newId()` / Hibernate `@UuidGenerator(style = VERSION_7)`.
- **Lists:** keyset pagination with `CursorPage` / `Cursors`, never offsets.
- **API changes:** run `npm run openapi:export -w school-backend`, then `npm run codegen`, and commit both the spec and the generated types.
- **Authorization:** check permissions server-side with `@PreAuthorize("@authz.school('<permission>', #schoolId)")` (also `platform`, `country`, `organization`, `campus`, `anywhere`, `anyAt`) or `AccessControl` (`common.security`); filter lists with `AccessControl.accessible(...)`. Permission codes live in `common.security.Permissions` and `R__40_roles_permissions.sql` (kept equal by a test). Actors come from `CurrentUser` (never parse ids from requests).
- **Audit and idempotency:** mutations call `AuditLog.record(...)` in their transaction (no secrets, mask phones/emails with `Masking`); create endpoints get `@Idempotent`.
- **Media (`media` module):** clients upload straight to object storage with a presigned PUT, then `complete`; never proxy bytes through the API. Access follows the owning object (R-30): the module that owns a purpose registers a `MediaPurposePolicy` bean (`canCreate`/`canRead`); `media` depends on none of them. Other modules use `MediaService` (`requireAttachable`, `summary`, `discard`) and clear their references on `MediaDeleted`. Images are served only as re-encoded variants (metadata stripped); never issue a signed URL for an image original. A new purpose needs a row in `PurposeRules` and a policy, plus allow/deny tests and matrix entries.
- **Notifications (`notifications` module):** other modules call `NotificationService.notify(NotificationRequest)` (in the caller's transaction; recipients get title and body rendered from `notification.<type>.title|body` in their language, `data.path` is a relative app path, never secrets). Notification types are data: a new type needs a row in `R__50_notification_types.sql` and `.label`/`.title`/`.body` keys in both message bundles (`NotificationTypesSeedTest`). Listeners that notify must be idempotent (`alreadyNotified`). Pushes go through the `PushSender` SPI (`school.push.provider=expo|log`).
- **Real-time (`realtime` module):** push to a user's open apps with `RealtimePublisher.toUser(...)` (Redis pub/sub across instances, best effort: anything that matters is also stored). Clients may only SUBSCRIBE to `/user/queue/*` and never SEND; CONNECT is authenticated by bearer token or a single-use ticket (`StompAuthInterceptor`). A new revocation trigger must go through `TokenService` so `SessionsRevoked` closes the sockets and push devices.
- **Tests:** annotate integration tests with `@IntegrationTest` (shared context, Postgres 18 via Testcontainers). Keep its properties identical across classes so the context is reused (the server runs on a random port, so real WebSocket clients can connect). Call endpoints as real users from `support.Personas` (`persona.bearer()`), add new endpoints to `AuthorizationMatrixTest`, and reset `MutableClock` after moving time.

## Product rules that apply everywhere

- **Authorization is server-side only.** Never filter sensitive data on the client (spec §100). Every endpoint gets allow/deny tests per role, including another school's admin.
- **Tenant isolation:** school-owned rows carry `school_id`, with composite FKs to the parent (Database_Design §1).
- **No hard-coded UI strings.** Add keys to `packages/i18n/messages/en.json` *and* `am.json`; the catalog test enforces identical keys and placeholders. Amharic text needs native-speaker review before release.
- **Dates:** store instants as `timestamptz` (UTC) and dates as Gregorian `date`. Ethiopian calendar is display-only, via `@school/calendar`, and always with an explicit timezone.
- **Validation messages** are i18n keys (`validation.*`), never English sentences.
- **Children's data:** media of students needs media-publication consent; minimise data sent to third parties; audit sensitive reads (spec §131).
- ClassDojo is a UX benchmark only. Don't copy its brand, characters, illustrations or copy.

## Git

- `main` is protected: every change goes through a PR, and the `js`, `backend` and `migrations` checks must pass. Branch from `main` and open a PR.
- Dependencies: Expo-managed packages (React, React Native, `expo-*`, `react-native-*`) only move together via `npx expo install --fix` during an Expo SDK upgrade, and Dependabot ignores them. Majors of Next, ESLint and TypeScript are upgraded deliberately, not by Dependabot.
- Don't commit secrets or `.env` files. Dev-only credentials live in `infra/` and `application-local.yaml` and are documented as such.
