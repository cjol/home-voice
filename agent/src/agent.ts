import { Agent, type Connection, type ConnectionContext, type WSMessage } from "agents";
import {
  PROTOCOL_VERSION,
  SHOW_UI_PARAMETERS,
  TTS_SAMPLE_RATE,
  normalizeUi,
  type AgentState,
  type ClientMessage,
  type ServerMessage,
} from "@home-voice/shared";
import type { Env } from "./env";
import { FluxStt, type FluxEvent } from "./stt";
import { streamChat, type ChatMessage, type ToolDef } from "./llm";
import { TtsQueue } from "./tts";
import { SentenceChunker, speakable } from "./sentences";
import { systemPrompt } from "./prompt";
import { toolResultForModel } from "./unwrap";

const EXECUTOR = "executor";
const MAX_TOOL_ROUNDS = 5;
const HISTORY_TURNS = 12;
const HISTORY_IDLE_RESET_MS = 10 * 60_000;
const PREAMBLE = "Let me check.";

interface State {
  /** Per-device override of env.LLM_MODEL (set via onRequest POST /config). */
  model?: string;
}

/**
 * One Durable Object per device (the DO name is the deviceId).
 * Holds the device WebSocket, the Flux STT session, conversation history and
 * the persistent Executor MCP client (OAuth tokens live in this DO's storage,
 * managed by the Agents SDK).
 */
export class VoiceAgent extends Agent<Env, State> {
  initialState: State = {};

  private device: Connection | null = null;
  private stt: FluxStt | null = null;
  private history: ChatMessage[] = [];
  private lastTurnAt = 0;
  private turn: { id: string; abort: AbortController } | null = null;
  private agentState: AgentState = "idle";

  private get model() {
    return this.state.model ?? this.env.LLM_MODEL;
  }

  // ---- connection lifecycle ------------------------------------------------

  async onConnect(conn: Connection, ctx: ConnectionContext) {
    // Single device per DO: newest connection wins.
    if (this.device && this.device.id !== conn.id) this.device.close(4000, "replaced");
    this.device = conn;
    const tools = await this.ensureExecutor(new URL(ctx.request.url).origin);
    this.send({ type: "ready", v: PROTOCOL_VERSION, model: this.model, tools });
  }

  async onClose(conn: Connection) {
    if (this.device?.id !== conn.id) return;
    this.device = null;
    this.cancelTurn();
    this.stt?.close();
    this.stt = null;
  }

  async onMessage(conn: Connection, message: WSMessage) {
    if (typeof message !== "string") {
      // Binary = mic PCM.
      const buf = message instanceof ArrayBuffer ? message : (message as ArrayBufferView).buffer as ArrayBuffer;
      this.stt?.sendAudio(buf);
      return;
    }
    let msg: ClientMessage;
    try {
      msg = JSON.parse(message);
    } catch {
      return this.send({ type: "error", message: "bad json" });
    }
    switch (msg.type) {
      case "hello":
        break; // TODO: version negotiation if PROTOCOL_VERSION ever changes
      case "start_session":
        await this.startSession();
        break;
      case "end_session":
        this.stt?.close();
        this.stt = null;
        this.setVoiceState("idle");
        break;
      case "interrupt":
        this.bargeIn();
        break;
      case "playback_done":
        break; // TODO: use for follow-up window timing on the server if needed
      case "ui_event":
        // TODO: feed UI taps into the conversation (e.g. as a user message "[tapped X]").
        console.log("ui_event", msg);
        break;
      case "ping":
        this.send({ type: "pong" });
        break;
    }
  }

  /** Lets you change the model per device without redeploying. */
  async onRequest(request: Request): Promise<Response> {
    const url = new URL(request.url);
    if (request.method === "POST" && url.pathname.endsWith("/config")) {
      const body = (await request.json()) as { model?: string };
      this.setState({ ...this.state, model: body.model || undefined });
      return Response.json({ model: this.model });
    }
    return Response.json({ model: this.model, mcp: this.getMcpServers().servers });
  }

  // ---- Executor MCP --------------------------------------------------------

  private executorId(): string | undefined {
    const servers = this.getMcpServers().servers;
    return Object.entries(servers).find(([, s]) => s.name === EXECUTOR)?.[0];
  }

  private async ensureExecutor(origin: string): Promise<"connected" | "auth_required" | "error"> {
    try {
      const id = this.executorId();
      const existing = id ? this.getMcpServers().servers[id] : undefined;
      if (existing) {
        if (existing.state === "authenticating" && existing.auth_url) {
          this.send({ type: "auth_required", url: existing.auth_url });
          return "auth_required";
        }
        return existing.state === "ready" ? "connected" : "error";
      }
      // Default callback: <origin>/agents/voice-agent/<deviceId>/callback (exempt from DEVICE_TOKEN).
      const res = await this.addMcpServer(EXECUTOR, this.env.EXECUTOR_MCP_URL, {
        callbackHost: this.env.PUBLIC_URL ?? origin,
      });
      if (res.state === "authenticating") {
        this.send({ type: "auth_required", url: res.authUrl });
        return "auth_required";
      }
      return "connected";
    } catch (err) {
      console.error("executor connect failed", err);
      return "error";
    }
  }

  private toolDefs(): ToolDef[] {
    const id = this.executorId();
    const mcpTools = id
      ? this.mcp.listTools().filter((t) => t.serverId === id).map((t) => ({
          type: "function" as const,
          function: { name: t.name, description: t.description, parameters: t.inputSchema },
        }))
      : [];
    return [
      ...mcpTools,
      {
        type: "function",
        function: {
          name: "show_ui",
          description: "Show a card, list, chart or status on the tablet screen alongside your spoken reply.",
          parameters: SHOW_UI_PARAMETERS,
        },
      },
    ];
  }

  private async runTool(name: string, rawArgs: string): Promise<string> {
    let args: Record<string, unknown> = {};
    try {
      args = rawArgs ? JSON.parse(rawArgs) : {};
    } catch {
      return JSON.stringify({ error: "arguments were not valid JSON" });
    }
    if (name === "show_ui") {
      const ui = normalizeUi(args, crypto.randomUUID());
      if (!ui) return JSON.stringify({ error: "invalid ui" });
      this.send({ type: "ui", ui });
      return JSON.stringify({ shown: true });
    }
    const serverId = this.executorId();
    if (!serverId) return JSON.stringify({ error: "Executor is not connected" });
    try {
      const result = await this.mcp.callTool({ serverId, name, arguments: args });
      // Unwrap MCP envelopes ({content, structuredContent}) before the model sees them.
      return toolResultForModel(result);
    } catch (err) {
      return JSON.stringify({ error: String(err) });
    }
  }

  // ---- STT -----------------------------------------------------------------

  private async startSession() {
    if (!this.stt) {
      this.stt = new FluxStt(this.env, {
        onEvent: (e) => this.onFlux(e),
        onClose: () => (this.stt = null),
      });
    }
    try {
      await this.stt.open();
      this.setVoiceState("listening");
    } catch (err) {
      this.stt = null;
      this.send({ type: "error", message: `stt: ${err}` });
    }
  }

  private onFlux(e: FluxEvent) {
    const text = e.transcript?.trim() ?? "";
    switch (e.event) {
      case "StartOfTurn":
        // Server-side barge-in: the user started talking while we speak.
        // Relies on device AEC so our own TTS doesn't trigger this.
        if (this.agentState === "speaking" || this.agentState === "thinking") this.bargeIn();
        break;
      case "Update":
      case "EagerEndOfTurn":
      case "TurnResumed":
        // TODO: speculative LLM start on EagerEndOfTurn, cancel on TurnResumed.
        if (text) this.send({ type: "transcript", text, final: false });
        break;
      case "EndOfTurn":
        if (!text) return;
        this.send({ type: "transcript", text, final: true });
        void this.runTurn(text);
        break;
    }
  }

  // ---- turn loop -----------------------------------------------------------

  private bargeIn() {
    if (!this.turn) return;
    this.send({ type: "stop_playback", turnId: this.turn.id });
    this.cancelTurn();
    this.setVoiceState("listening");
  }

  private cancelTurn() {
    this.turn?.abort.abort();
    this.turn = null;
  }

  private async runTurn(userText: string) {
    this.cancelTurn();
    const turn = { id: crypto.randomUUID(), abort: new AbortController() };
    this.turn = turn;
    const signal = turn.abort.signal;

    if (Date.now() - this.lastTurnAt > HISTORY_IDLE_RESET_MS) this.history = [];
    this.lastTurnAt = Date.now();
    this.history.push({ role: "user", content: userText });
    this.setVoiceState("thinking");

    const tts = new TtsQueue(
      this.env,
      this.env.TTS_VOICE,
      (pcm) => this.turn === turn && this.device?.send(pcm),
      () => {
        this.send({ type: "tts_start", turnId: turn.id, sampleRate: TTS_SAMPLE_RATE, encoding: "pcm_s16le" });
        this.setVoiceState("speaking");
      },
      signal,
    );
    const say = (s: string) => {
      const clean = speakable(s);
      if (!clean) return;
      this.send({ type: "assistant_text", turnId: turn.id, text: clean });
      tts.enqueue(clean);
    };

    try {
      const tools = this.toolDefs();
      for (let round = 0; round <= MAX_TOOL_ROUNDS && !signal.aborted; round++) {
        const chunker = new SentenceChunker();
        const messages: ChatMessage[] = [{ role: "system", content: systemPrompt(new Date()) }, ...this.history];
        const { text, toolCalls } = await streamChat(
          this.env,
          this.model,
          messages,
          round < MAX_TOOL_ROUNDS ? tools : [],
          (d) => chunker.push(d).forEach(say),
          signal,
        );
        const rest = chunker.flush();
        if (rest) say(rest);

        if (!toolCalls.length) {
          this.history.push({ role: "assistant", content: text });
          break;
        }
        // Spoken preamble before tools, if the model didn't produce one itself.
        if (!text.trim() && round === 0 && toolCalls.some((c) => c.function.name !== "show_ui")) say(PREAMBLE);

        this.history.push({ role: "assistant", content: text || null, tool_calls: toolCalls });
        for (const call of toolCalls) {
          if (signal.aborted) break;
          const content = await this.runTool(call.function.name, call.function.arguments);
          this.history.push({ role: "tool", tool_call_id: call.id, content });
        }
      }
      await tts.drain();
      if (!signal.aborted && tts.started) this.send({ type: "tts_end", turnId: turn.id });
    } catch (err) {
      if (!signal.aborted) {
        console.error("turn failed", err);
        this.send({ type: "error", message: String(err) });
        // Drop the dangling user message so history stays valid.
        if (this.history.at(-1)?.role === "user") this.history.pop();
      }
    } finally {
      if (this.turn === turn) {
        this.turn = null;
        this.setVoiceState(this.stt ? "listening" : "idle");
      }
      this.trimHistory();
    }
  }

  /** Keep the last N user turns, never splitting an assistant/tool sequence. */
  private trimHistory() {
    const userIdx = this.history.map((m, i) => (m.role === "user" ? i : -1)).filter((i) => i >= 0);
    if (userIdx.length > HISTORY_TURNS) this.history = this.history.slice(userIdx[userIdx.length - HISTORY_TURNS]);
    // TODO: persist history in this.sql so it survives eviction.
  }

  // ---- helpers -------------------------------------------------------------

  private send(msg: ServerMessage) {
    this.device?.send(JSON.stringify(msg));
  }

  private setVoiceState(s: AgentState) {
    if (s === this.agentState) return;
    this.agentState = s;
    this.send({ type: "state", state: s });
  }
}
