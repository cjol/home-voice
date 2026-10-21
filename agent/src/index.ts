import { routeAgentRequest } from "agents";
import type { Env } from "./env";

export { VoiceAgent } from "./agent";

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    if (url.pathname === "/health") return new Response("ok");

    // OAuth callbacks from Executor come back without our device token.
    const isOAuthCallback = /\/callback(\/|$)/.test(url.pathname);
    if (!isOAuthCallback) {
      const token = url.searchParams.get("token") ?? request.headers.get("authorization")?.replace(/^Bearer /, "");
      if (!env.DEVICE_TOKEN || token !== env.DEVICE_TOKEN) {
        return new Response("unauthorized", { status: 401 });
      }
    }

    return (await routeAgentRequest(request, env)) ?? new Response("not found", { status: 404 });
  },
} satisfies ExportedHandler<Env>;
