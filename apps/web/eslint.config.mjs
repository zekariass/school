import { defineConfig, globalIgnores } from "eslint/config";
import nextVitals from "eslint-config-next/core-web-vitals";
import nextTs from "eslint-config-next/typescript";
import { noCountryHardcoding } from "@school/config/eslint/no-country-hardcoding";

const eslintConfig = defineConfig([
  ...nextVitals,
  ...nextTs,
  // D42: no country, zone, currency or language list in code.
  ...noCountryHardcoding,
  // Override default ignores of eslint-config-next.
  globalIgnores([
    // Default ignores of eslint-config-next:
    ".next/**",
    "out/**",
    "build/**",
    "next-env.d.ts",
  ]),
]);

export default eslintConfig;
