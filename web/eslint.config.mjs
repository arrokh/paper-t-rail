import { fixupConfigRules } from "@eslint/compat";
import { defineConfig, globalIgnores } from "eslint/config";
import nextCoreWebVitals from "eslint-config-next/core-web-vitals";
import nextTypeScript from "eslint-config-next/typescript";

export default defineConfig([
  ...fixupConfigRules([...nextCoreWebVitals, ...nextTypeScript]),
  globalIgnores([".next/**", "out/**", "build/**", "next-env.d.ts"]),
]);
