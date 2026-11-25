# School mobile app (Expo SDK 58, iOS + Android)

Teacher, parent and student app. The Next.js app (`apps/web`) is the web client, so this app targets iOS and Android only.

## Run

```bash
npm run infra:up                           # Postgres etc. (repo root)
npm run dev -- --filter=school-backend     # API on :8180
cd apps/mobile
npx expo run:ios --device "iPhone 17 Pro"  # first run: generates ios/ (git-ignored) and builds the dev client
npx expo start --dev-client                # afterwards: just start Metro
```

The app needs a **development build** (SQLite, secure store, camera, embedded fonts are native modules/config plugins). Expo Go is not supported.

`__DEV__` builds find the backend through the Metro host (`http://<LAN IP>:8180`), so physical devices on the same Wi-Fi work. Override with `EXPO_PUBLIC_API_URL` (see `.env.example`).

## Layout

- `src/app/`: Expo Router routes only. `(auth)` sign-in, `(teacher)` / `(guardian)` / `(student)` tab shells, `settings`, `status`, and the development-only `dev` role previewer.
- `src/components/ui/`: theme-aware primitives (`Screen`, `Text`, `Button`, `Card`, `TextField`, `EmptyState`, `SegmentedControl`).
- `src/lib/`: session, API client, i18n (`use-intl` + `@school/i18n`), theme (`@school/design-tokens`), preferences, query cache, local SQLite store. Files imported by `*.test.ts` are pure TypeScript (no React Native imports).

## Teachers and students (M3b)

- **Roles** come from `GET /me` memberships: a TEACHER membership opens the teacher tabs (Classes, Messages, Calendar, Profile), a STUDENT membership the student tabs (Home, Portfolio, Profile). An account with both gets a role switcher in Settings; the last role is remembered. Administrators and STAFF are web-only.
- **Students sign in with a class code** (`(auth)/student-code`): scan the QR code with the camera or type the text code (`XXXXX-XXXXX`). Simulators have no camera, so use the text field there. Teachers show a student's code from the roster (student, "Show login code"); the plaintext code lives only in that screen's state. Class-code sessions are short (7 days, 2 idle), and an ended one returns to the code screen.
- Seed a school for manual testing with the dev admin (`+251900000001`): create a school, year, grade, a teacher through `POST /schools/{id}/staff`, a class, students (`POST /schools/{id}/students` with `classId`) and issue codes with `POST /schools/{id}/classes/{classId}/login-codes` (the OTP prints in the backend log as `[console SMS]`).

## Scripts

`npm run lint | typecheck | test` (vitest, pure modules only). `npx expo-doctor` should report no issues.

## EAS

`eas.json` defines `development`, `preview` and `production` profiles. The project is not linked to an EAS account yet: run `npx eas-cli init` once, then set the real API URLs in `eas.json`.
