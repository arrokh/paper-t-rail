import { fileURLToPath } from "node:url";
import { defineConfig } from "vitest/config";

const webRoot = fileURLToPath(new URL(".", import.meta.url));

export default defineConfig({
  resolve: {
    alias: { "@": webRoot },
  },
  test: {
    environment: "jsdom",
    include: ["test/**/*.test.tsx"],
    setupFiles: ["./test/setup.ts"],
    restoreMocks: true,
    clearMocks: true,
  },
});
