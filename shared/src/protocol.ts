import type { UiMessage } from "./ui";

/**
 * Device <-> agent WebSocket protocol, version 1.
 *
 * Endpoint: wss://<worker>/agents/voice-agent/<deviceId>?token=<DEVICE_TOKEN>
 * (one Durable Object per deviceId).
 *
 * Text frames carry JSON messages below. Binary frames carry raw audio:
 *   device -> agent: PCM s16le, mono, 16 kHz (mic, after AEC/NS)
 *   agent -> device: PCM s16le, mono, 24 kHz (TTS), only between `tts_start` and `tts_end`
 *
 * Kotlin mirror: android/app/src/main/java/net/littlesavage/homevoice/protocol/Protocol.kt
 */
export const PROTOCOL_VERSION = 1 as const;
export const MIC_SAMPLE_RATE = 16_000;
export const TTS_SAMPLE_RATE = 24_000;

// ---- device -> agent -------------------------------------------------------

export type ClientMessage =
  /** First message after connecting. */
  | { type: "hello"; v: typeof PROTOCOL_VERSION; deviceId: string; appVersion?: string }
  /** Wake word fired; binary mic frames follow. */
  | { type: "start_session"; wakeWord?: string }
  /** Device stopped streaming (follow-up window expired or user cancelled). */
  | { type: "end_session"; reason?: string }
  /** User manually stopped the reply. */
  | { type: "interrupt" }
  /** After stopping playback early (barge-in): how much of that reply's audio actually played. */
  | { type: "played"; turnId: string; playedMs: number }
  /** Device finished playing a TTS segment (used for follow-up timing). */
  | { type: "playback_done"; turnId: string }
  /** Typed/test input: runs a turn exactly as if Flux had transcribed it (skips STT). */
  | { type: "text_input"; text: string }
  /** User touched something in a UI overlay. */
  | { type: "ui_event"; uiId: string; action: string; value?: string }
  /** Authoritative screen IDs on this device, sent after each reconnect. */
  | { type: "ui_state"; openIds: string[] }
  | { type: "ping" };

// ---- agent -> device -------------------------------------------------------

export type AgentState = "idle" | "listening" | "thinking" | "speaking";

export type ServerMessage =
  | { type: "ready"; v: typeof PROTOCOL_VERSION; model: string; tools: "connected" | "auth_required" | "error" | "disabled" }
  | { type: "state"; state: AgentState }
  /** Interim transcript of the current user turn. */
  | { type: "transcript"; text: string; final: boolean }
  /** Assistant text as it is spoken (for captions). */
  | { type: "assistant_text"; turnId: string; text: string }
  | { type: "tts_start"; turnId: string; sampleRate: typeof TTS_SAMPLE_RATE; encoding: "pcm_s16le" }
  | { type: "tts_end"; turnId: string }
  /** Stop playback immediately and flush buffers (server-side barge-in). */
  | { type: "stop_playback"; turnId?: string }
  | { type: "ui"; ui: UiMessage }
  | { type: "dismiss_ui"; uiId?: string }
  /** Executor needs OAuth; the device shows this URL (open on any browser). */
  | { type: "auth_required"; url: string }
  | { type: "error"; message: string }
  /** The agent judged the last utterance not meant for it: drop it from the transcript and stop listening. */
  | { type: "ignored" }
  /** Server history was reset (idle too long): clear earlier turns from the on-screen transcript. */
  | { type: "new_conversation" }
  | { type: "pong" };
