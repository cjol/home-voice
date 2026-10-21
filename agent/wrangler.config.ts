import { defineWranglerConfig } from "wrangler/experimental-config";

// Tooling config (complements cloudflare.config.ts). Dev binds to the Tailscale host, not localhost.
export default defineWranglerConfig({
	dev: { ip: "laptop", port: 8787 },
});
