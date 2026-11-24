# syntax=docker/dockerfile:1
# Build context: REPOSITORY ROOT
#   docker build -f apps/web/Dockerfile -t school-web:local .

ARG NODE_VERSION=26

# ---- prune: reduce the monorepo to school-web and the workspaces it needs ----
FROM node:${NODE_VERSION}-slim AS prune
WORKDIR /repo
COPY . .
# Install the exact turbo version pinned in the root package.json.
RUN npm install --global --no-audit --no-fund \
      "turbo@$(node -p "require('./package.json').devDependencies.turbo")" \
    && turbo prune school-web --docker

# ---- build ----
FROM node:${NODE_VERSION}-slim AS build
WORKDIR /repo
ENV NEXT_TELEMETRY_DISABLED=1 \
    TURBO_TELEMETRY_DISABLED=1 \
    CI=true

# 1. Install dependencies. This layer is reused until a package.json or the lockfile changes.
COPY --from=prune /repo/out/json/ ./
COPY --from=prune /repo/out/package-lock.json ./package-lock.json
RUN npm ci --no-audit --no-fund

# 2. Copy the pruned sources and build.
COPY --from=prune /repo/out/full/ ./
RUN mkdir -p apps/web/public \
    && npx turbo run build --filter=school-web

# ---- runtime: Next.js standalone server only ----
FROM node:${NODE_VERSION}-slim AS runtime
ENV NODE_ENV=production \
    NEXT_TELEMETRY_DISABLED=1 \
    PORT=3000 \
    HOSTNAME=0.0.0.0
WORKDIR /app

# Standalone output mirrors the monorepo layout: the server is apps/web/server.js.
COPY --from=build --chown=node:node /repo/apps/web/.next/standalone ./
COPY --from=build --chown=node:node /repo/apps/web/.next/static ./apps/web/.next/static
COPY --from=build --chown=node:node /repo/apps/web/public ./apps/web/public

USER node
EXPOSE 3000
CMD ["node", "apps/web/server.js"]
