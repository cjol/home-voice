import type { VoiceAgent } from "./agent";

export interface Env {
  AI: Ai;
  VoiceAgent: DurableObjectNamespace<VoiceAgent>;
  LLM_MODEL: string;
  TTS_VOICE: string;
  EXECUTOR_MCP_URL: string;
  EXECUTOR_ENABLED?: string;
  DEVICE_TOKEN?: string;
  PUBLIC_URL?: string;
}
