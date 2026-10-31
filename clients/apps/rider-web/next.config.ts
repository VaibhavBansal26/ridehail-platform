import path from "node:path";
import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  output: "standalone",
  poweredByHeader: false,
  // @ridehail/api is TypeScript source in the workspace; Next compiles it with the app
  transpilePackages: ["@ridehail/api"],
  // the workspace root (where node_modules and the lockfile live), so Next does not guess from a stray lockfile elsewhere
  turbopack: {
    root: path.join(__dirname, "../.."),
    rules: { "*.css": { loaders: ["@tailwindcss/turbopack"], as: "*.css" } },
  },
  outputFileTracingRoot: path.join(__dirname, "../.."),
  // The Content-Security-Policy needs a fresh nonce per request, so proxy.ts sets it. These are the static ones.
  async headers() {
    return [
      {
        source: "/:path*",
        headers: [
          { key: "X-Content-Type-Options", value: "nosniff" },
          { key: "X-Frame-Options", value: "DENY" },
          { key: "Referrer-Policy", value: "same-origin" },
          // the rider's position is the point of this app: this origin may ask for it, nobody embedded in it may
          { key: "Permissions-Policy", value: "camera=(), microphone=(), geolocation=(self), payment=()" },
          { key: "Strict-Transport-Security", value: "max-age=63072000; includeSubDomains" },
          { key: "Cross-Origin-Opener-Policy", value: "same-origin" },
        ],
      },
    ];
  },
};

export default nextConfig;
