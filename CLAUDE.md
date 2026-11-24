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
npm run dev -- --filter=school-backend      # API on :8080 ("local" profile)
```

Before declaring work done, run `npm run lint typecheck test` (or the filtered equivalents) and make sure they pass.

## Monorepo conventions

- Shared packages live in `packages/*` as **source-only TypeScript** (`exports` point at `src/*.ts`, no build step). Next.js (Turbopack) and Expo (Metro) compile them directly. Keep them framework-agnostic: no React Native or Next.js imports.
- Generated files are committed: `packages/api-client/src/generated/`, `packages/design-tokens/generated/`. Never edit them by hand. Regenerate with `npm run codegen`; CI runs `codegen:check`.
- New packages extend `@school/config/tsconfig/library.json`, re-export `@school/config/eslint/library`, and provide `lint`, `typecheck` and `test` (vitest) scripts.
- Node version: `.nvmrc` (26). The design-token script relies on Node's built-in TypeScript type stripping.

## Product rules that apply everywhere

- **Authorization is server-side only.** Never filter sensitive data on the client (spec §100). Every endpoint gets allow/deny tests per role, including another school's admin.
- **Tenant isolation:** school-owned rows carry `school_id`, with composite FKs to the parent (Database_Design §1).
- **No hard-coded UI strings.** Add keys to `packages/i18n/messages/en.json` *and* `am.json`; the catalog test enforces identical keys and placeholders. Amharic text needs native-speaker review before release.
- **Dates:** store instants as `timestamptz` (UTC) and dates as Gregorian `date`. Ethiopian calendar is display-only, via `@school/calendar`, and always with an explicit timezone.
- **Validation messages** are i18n keys (`validation.*`), never English sentences.
- **Children's data:** media of students needs media-publication consent; minimise data sent to third parties; audit sensitive reads (spec §131).
- ClassDojo is a UX benchmark only. Don't copy its brand, characters, illustrations or copy.

## Git

- Branch from `main`; never commit straight to `main`.
- Don't commit secrets or `.env` files. Dev-only credentials live in `infra/` and `application-local.yaml` and are documented as such.
