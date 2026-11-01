import react from "@vitejs/plugin-react";
import path from "node:path";
import { defineConfig } from "vitest/config";

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      "@": path.resolve(import.meta.dirname),
      // "server-only" deliberately throws outside the server bundle; in tests we are the server
      "server-only": path.resolve(import.meta.dirname, "test/empty.ts"),
    },
  },
  test: {
    environment: "jsdom",
    setupFiles: ["./test/setup.ts"],
    include: ["**/*.test.{ts,tsx}"],
    exclude: ["node_modules", ".next"],
    restoreMocks: true,
    testTimeout: 15_000, // the first test of a file pays for importing the screen and its libraries, which is slow when the machine is busy
  },
});
