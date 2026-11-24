# School — Monorepo

npm workspaces + [Turborepo](https://turbo.build) monorepo.

## Layout

```
apps/
  backend    Spring Boot API (Maven, Java)
  mobile     Expo / React Native app
  web        Next.js app
packages/    shared packages (add as needed)
```

## Commands

Run everything from the repo root:

```bash
npm install                 # install all JS workspaces

npm run dev                 # turbo: run all dev servers
npm run build               # turbo: build all packages
npm run lint                # turbo: lint all packages
npm run test                # turbo: test all packages
```

Target a single app with `--filter`:

```bash
npm run dev -- --filter=school-web       # Next.js dev server
npm run dev -- --filter=school-mobile    # Expo dev server
npm run dev -- --filter=school-backend   # Spring Boot (mvnw spring-boot:run)
npm run build -- --filter=school-web
```

Or run workspace scripts directly:

```bash
npm run dev --workspace=school-web
```

## Notes

- `apps/backend` is a Maven project; its `package.json` only wraps `mvnw`
  so Turborepo can schedule it. The pom requires JDK 25 via Maven
  Toolchains (`maven-toolchains-plugin`), so it builds even when
  `JAVA_HOME` points at another JDK — it just needs a matching entry in
  `~/.m2/toolchains.xml` (already configured on this machine).
- `apps/mobile` needs no Metro config changes — Expo SDK 52+ auto-detects
  the workspace root.
