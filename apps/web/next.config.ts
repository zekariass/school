import path from "node:path";

import type { NextConfig } from "next";
import createNextIntlPlugin from "next-intl/plugin";

const withNextIntl = createNextIntlPlugin("./i18n/request.ts");

const securityHeaders = [
  { key: "X-Content-Type-Options", value: "nosniff" },
  { key: "Referrer-Policy", value: "strict-origin-when-cross-origin" },
  { key: "X-Frame-Options", value: "DENY" },
  { key: "Permissions-Policy", value: "camera=(), microphone=(), geolocation=()" },
];

/** The class-code sign-in is the one page that may use the camera (to scan the QR code a teacher printed): this page only, this origin only. */
const cameraHeaders = [{ key: "Permissions-Policy", value: "camera=(self), microphone=(), geolocation=()" }];

const nextConfig: NextConfig = {
  // Self-contained server bundle for the container image (apps/web/Dockerfile).
  // Trace from the monorepo root so the source-only @school/* workspace
  // packages and hoisted node_modules are included in the standalone output.
  output: "standalone",
  outputFileTracingRoot: path.join(import.meta.dirname, "../.."),
  cacheComponents: true,
  partialPrefetching: true,
  turbopack: {
    rules: {
      "*.css": {
        loaders: ["@tailwindcss/turbopack"],
        as: "*.css",
      },
    },
  },
  async headers() {
    // Later rules override earlier ones for the same header: every page denies the camera except `/{locale}/student-code`.
    return [
      { source: "/:path*", headers: securityHeaders },
      { source: "/:locale/student-code", headers: cameraHeaders },
    ];
  },
};

export default withNextIntl(nextConfig);
