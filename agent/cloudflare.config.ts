import { bindings, defineConfig, exports } from "cf/config";

// The Worker's Cloudflare config (cf's native format; replaces wrangler.jsonc).
// Deploy: `npm run deploy -w agent`. Types for `env` stay hand-written in src/env.ts.
export default defineConfig({
	accountId: "39f3f1b5073547672a0d7ad67319df81",
	worker: {
		name: "home-voice-agent",
		compatibilityDate: "2026-10-01",
		compatibilityFlags: ["nodejs_compat"],
		entrypoint: "src/index.ts",
		observability: { enabled: true },
		env: {
			AI: bindings.ai({ dev: { remote: true } }), // always remote, even in local dev
			VoiceAgent: bindings.durableObject({ worker: "home-voice-agent", exportName: "VoiceAgent" }),
			// Benchmark winner: the only Workers AI model to answer all 4 Executor test questions (4-16 s).
			// It must make real tool calls: see NON_TOOL_MODELS in src/llm.ts for known-bad ones.
			LLM_MODEL: bindings.text("@cf/deepseek-ai/deepseek-v4.1-flash-1017"),
			TTS_VOICE: bindings.text("luna"),
			EXECUTOR_MCP_URL: bindings.text("https://mcp.executor.sh/mcp"),
			// Off until the voice loop is proven; "true" connects Executor (OAuth on first connect).
			EXECUTOR_ENABLED: bindings.text("false"),
			// Shared secret the tablet sends as ?token=. Locally from .dev.vars.
			DEVICE_TOKEN: bindings.secret(),
			// PUBLIC_URL (OAuth callback host) is derived from the request if unset.
		},
		exports: {
			// One instance per device. Was wrangler migration tag v1 (new_sqlite_classes).
			VoiceAgent: exports.durableObject({ storage: "sqlite" }),
		},
	},
});
