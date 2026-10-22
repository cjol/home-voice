import { Agent, type Connection, type ConnectionContext, type WSMessage } from "agents";
import {
  PROTOCOL_VERSION,
  SHOW_UI_PARAMETERS,
  TTS_SAMPLE_RATE,
  normalizeUi,
  type AgentState,
  type ClientMessage,
  type ServerMessage,
  type UiMessage,
} from "@home-voice/shared";
import type { Env } from "./env";
import { FluxStt, type FluxEvent } from "./stt";
import { NON_TOOL_MODELS, streamChat, type ChatMessage, type ToolDef } from "./llm";
import { AuraTts, TtsTurn } from "./tts";
import { SentenceChunker, speakable } from "./sentences";
import { clockNote, systemPrompt } from "./prompt";
import { toolResultForModel } from "./unwrap";

const EXECUTOR = "executor";
const MAX_TOOL_ROUNDS = 5;
const HISTORY_TURNS = 12;
const HISTORY_IDLE_RESET_MS = 10 * 60_000;
const PREAMBLE = "Let me check.";
/** Tools that only draw on the device: no preamble, and a reply that already spoke needn't go round again. */
const UI_TOOLS = new Set(["show_ui", "close_ui"]);
/** Lets the model decline speech that wasn't meant for it (a false wake, background talk). First round only. */
const IGNORE_TOOL = "ignore";
/** A short turn ending this soon after a wake-word barge-in is the wake word itself (Flux may hear "Hey Jarvis" as "HRV"). */
const WAKE_ECHO_MS = 4_000;
const WAKE_PREFIX = /^\s*(hey|hi|okay|ok)[,\s]+jarvis\b[,.!?\s]*/i;

const IGNORE_DEF: ToolDef = {
  type: "function",
  function: {
    name: IGNORE_TOOL,
    description:
      "Call this alone, with no spoken text, when the user's words were not meant for you: a fragment of speech, " +
      "background conversation or TV, or a non sequitur unrelated to the conversation. You stay silent and the " +
      "device goes back to waiting for the wake word.",
    parameters: { type: "object", properties: { reason: { type: "string", description: "A few words, for logs." } } },
  },
};

interface Turn {
  id: string;
  abort: AbortController;
  tts: TtsTurn;
  firstAudioAt: number;
  /** Index of this turn's final assistant message, if the LLM finished before any barge-in. */
  replyIndex?: number;
}

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

  // In-memory fields are lost when the DO hibernates between sessions; anything that must
  // survive is re-derived (device connection) or persisted (history).
  private _device: Connection | null = null;
  private stt: FluxStt | null = null;
  private history: ChatMessage[] = [];
  private lastTurnAt = 0;
  private turn: Turn | null = null;
  /** A reply cut off by barge-in, until we know how much of it was heard (see settleInterrupted). */
  private interrupted: { turn: Turn; at: number } | null = null;
  private aura: AuraTts | null = null;
  /** What's on the device screen now, by id, so the model can build on it (persisted with history). */
  private surfaces = new Map<string, UiMessage>();
  private agentState: AgentState = "idle";
  private wakeHeardAt = 0;

  /** The device socket, re-attached from the hibernation-surviving connection list if needed. */
  private get device(): Connection | null {
    if (!this._device) this._device = [...this.getConnections()].at(-1) ?? null;
    return this._device;
  }

  private get model() {
    return this.state.model ?? this.env.LLM_MODEL;
  }

  // ---- connection lifecycle ------------------------------------------------

  async onConnect(conn: Connection, ctx: ConnectionContext) {
    // Single device per DO: newest connection wins.
    for (const c of this.getConnections()) if (c.id !== conn.id) c.close(4000, "replaced");
    this._device = conn;
    const tools = await this.ensureExecutor(new URL(ctx.request.url).origin);
    this.send({ type: "ready", v: PROTOCOL_VERSION, model: this.model, tools });
  }

  async onClose(conn: Connection) {
    if (this.device?.id !== conn.id) return;
    this._device = null;
    this.cancelTurn();
    this.endSession();
  }

  async onMessage(conn: Connection, message: WSMessage) {
    this._device = conn;
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
        this.cancelTurn();
        this.endSession();
        this.setVoiceState("idle");
        break;
      case "interrupt":
        this.bargeIn();
        break;
      case "playback_done":
        break;
      case "played":
        // How much of an interrupted reply the device actually played.
        if (this.interrupted?.turn.id === msg.turnId) this.settleInterrupted(msg.playedMs);
        break;
      case "text_input":
        if (msg.text?.trim()) void this.runTurn(msg.text.trim());
        break;
      case "ui_state": {
        await this.loadHistory();
        const open = new Set(msg.openIds);
        for (const id of this.surfaces.keys()) if (!open.has(id)) this.surfaces.delete(id);
        await this.saveHistory();
        break;
      }
      case "ui_event":
        // A hibernated object starts with an empty map: hydrate before applying the close.
        await this.loadHistory();
        if (msg.action === "dismissed" && this.surfaces.delete(msg.uiId)) await this.saveHistory();
        // TODO: feed other UI taps into the conversation (e.g. as a user message "[tapped X]").
        break;
      case "ping":
        this.send({ type: "pong" });
        break;
    }
  }

  /** Lets you change the model per device without redeploying. */
  /** Clears history after HISTORY_IDLE_RESET_MS idle and tells the device, so its transcript matches. */
  private resetIfIdle() {
    if (Date.now() - this.lastTurnAt <= HISTORY_IDLE_RESET_MS) return;
    this.history = [];
    this.send({ type: "new_conversation" });
  }

  async onRequest(request: Request): Promise<Response> {
    const url = new URL(request.url);
    if (request.method === "POST" && url.pathname.endsWith("/config")) {
      const body = (await request.json()) as { model?: string };
      const why = body.model ? NON_TOOL_MODELS[body.model] : undefined;
      if (why) return Response.json({ error: `${body.model} can't be used: ${why}` }, { status: 400 });
      this.setState({ ...this.state, model: body.model || undefined });
      return Response.json({ model: this.model });
    }
    await this.loadHistory();
    return Response.json({ model: this.model, mcp: this.getMcpServers().servers, history: this.history, screens: [...this.surfaces.values()] });
  }

  // ---- Executor MCP --------------------------------------------------------

  private executorId(): string | undefined {
    const servers = this.getMcpServers().servers;
    return Object.entries(servers).find(([, s]) => s.name === EXECUTOR)?.[0];
  }

  private async ensureExecutor(origin: string): Promise<"connected" | "auth_required" | "error" | "disabled"> {
    if ((this.env.EXECUTOR_ENABLED as string) !== "true") return "disabled";
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
          description:
            "Show a card, list, table, chart or status on the tablet alongside your spoken reply. Reusing an id replaces that screen.",
          parameters: SHOW_UI_PARAMETERS,
        },
      },
      {
        type: "function",
        function: {
          name: "close_ui",
          description: "Close a screen (by id), or all screens if no id is given.",
          parameters: { type: "object", properties: { id: { type: "string" } } },
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
      const ui = normalizeUi(args, `ui-${crypto.randomUUID().slice(0, 6)}`);
      if (!ui) return JSON.stringify({ error: "invalid ui" });
      const replaced = this.surfaces.has(ui.id);
      this.surfaces.set(ui.id, ui);
      this.send({ type: "ui", ui });
      return JSON.stringify({ shown: true, id: ui.id, replaced });
    }
    if (name === "close_ui") {
      const id = typeof args.id === "string" ? args.id : undefined;
      if (id) this.surfaces.delete(id);
      else this.surfaces.clear();
      this.send({ type: "dismiss_ui", uiId: id });
      return JSON.stringify({ closed: id ?? "all" });
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

  private auraFor(): AuraTts {
    return (this.aura ??= new AuraTts(this.env, this.env.TTS_VOICE));
  }

  private endSession() {
    this.stt?.close();
    this.stt = null;
    this.aura?.close();
    this.aura = null;
  }

  private async startSession() {
    console.log("session start", { sttOpen: !!this.stt?.isOpen });
    await this.loadHistory();
    this.resetIfIdle();
    this.auraFor().warm(); // opening an Aura socket costs ~0.5 s; do it while the user speaks
    if (!this.stt) {
      const stt: FluxStt = new FluxStt(this.env, {
        onEvent: (e) => this.onFlux(e),
        onClose: () => {
          if (this.stt === stt) this.stt = null;
        },
      });
      this.stt = stt;
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
    if (this.agentState === "speaking" || this.agentState === "thinking") return;
    const text = e.transcript?.trim() ?? "";
    switch (e.event) {
      case "StartOfTurn":
        // Speech never interrupts a reply; the device exposes a manual button.
        break;
      case "Update":
      case "EagerEndOfTurn":
      case "TurnResumed":
        // TODO: speculative LLM start on EagerEndOfTurn, cancel on TurnResumed.
        if (text) this.send({ type: "transcript", text, final: false });
        break;
      case "EndOfTurn": {
        const words = text.replace(WAKE_PREFIX, "").trim();
        const wakeEcho = Date.now() - this.wakeHeardAt < WAKE_ECHO_MS && text.split(/\s+/).length <= 3;
        if (!words || wakeEcho) {
          console.log("turn dropped", { text, wakeEcho }); // the interruption itself, not a request
          return;
        }
        this.send({ type: "transcript", text: words, final: true });
        void this.runTurn(words);
        break;
      }
    }
  }

  // ---- turn loop -----------------------------------------------------------

  private bargeIn() {
    const turn = this.turn;
    if (!turn) return;
    this.send({ type: "stop_playback", turnId: turn.id });
    this.cancelTurn();
    // The device answers stop_playback/interrupt with `played`; until then we only know an upper bound.
    if (turn.tts.started) this.interrupted = { turn, at: Date.now() };
    this.setVoiceState("listening");
  }

  private cancelTurn() {
    const turn = this.turn;
    if (!turn) return;
    turn.abort.abort();
    void turn.tts.abort();
    this.turn = null;
  }

  /**
   * Record an interrupted reply as only what was heard, ending with a dash (the prompt explains the
   * dash). `playedMs` comes from the device; without it, wall-clock time since first audio is an
   * upper bound (audio is sent faster than real time, so "sent" would overstate it).
   */
  private settleInterrupted(playedMs?: number) {
    const cut = this.interrupted;
    if (!cut) return;
    this.interrupted = null;
    const heardMs = playedMs ?? Math.max(0, cut.at - cut.turn.firstAudioAt);
    const heard = cut.turn.tts.segments.filter((s) => s.endMs <= heardMs).map((s) => s.text).join(" ");
    const content = `${heard}—`.trim();
    const i = cut.turn.replyIndex;
    if (i !== undefined && this.history[i]?.role === "assistant") {
      this.history[i] = { role: "assistant", content }; // the LLM had finished; keep only what was heard
    } else {
      this.dropDanglingToolCalls();
      this.history.push({ role: "assistant", content });
    }
    void this.saveHistory();
  }

  /** An aborted turn can leave an assistant tool_calls message without all its results: invalid next time. */
  private dropDanglingToolCalls() {
    for (let i = this.history.length - 1; i >= 0; i--) {
      const m = this.history[i];
      if (m.role === "user") return;
      if (m.role === "assistant" && m.tool_calls?.length) {
        const answered = new Set(this.history.slice(i + 1).flatMap((x) => (x.role === "tool" ? [x.tool_call_id] : [])));
        if (!m.tool_calls.every((c) => answered.has(c.id))) this.history.splice(i);
        return;
      }
    }
  }

  private async runTurn(userText: string) {
    this.cancelTurn();
    await this.loadHistory();
    this.settleInterrupted(); // no `played` report came in time: use the estimate
    this.dropDanglingToolCalls();
    this.resetIfIdle();
    const prevTurnAt = this.lastTurnAt;
    this.lastTurnAt = Date.now();
    const userIndex = this.history.push({ role: "user", content: userText }) - 1;
    this.setVoiceState("thinking");

    const turn: Turn = {
      id: crypto.randomUUID(),
      abort: new AbortController(),
      firstAudioAt: 0,
      tts: null as unknown as TtsTurn,
    };
    turn.tts = new TtsTurn(
      this.auraFor(),
      (pcm) => this.turn === turn && this.device?.send(pcm),
      () => {
        turn.firstAudioAt = Date.now();
        this.send({ type: "tts_start", turnId: turn.id, sampleRate: TTS_SAMPLE_RATE, encoding: "pcm_s16le" });
        this.setVoiceState("speaking");
      },
    );
    this.turn = turn;
    const signal = turn.abort.signal;
    const say = (s: string) => {
      const clean = speakable(s);
      if (!clean) return;
      this.send({ type: "assistant_text", turnId: turn.id, text: clean });
      turn.tts.say(clean);
    };

    try {
      const tools = this.toolDefs();
      const executor = tools.some((t) => !UI_TOOLS.has(t.function.name));
      for (let round = 0; round <= MAX_TOOL_ROUNDS && !signal.aborted; round++) {
        const chunker = new SentenceChunker();
        // Static instructions first (a cacheable prefix), the time last.
        const messages: ChatMessage[] = [
          { role: "system", content: systemPrompt({ executor }) },
          ...this.history,
          { role: "system", content: [clockNote(new Date()), this.screenNote()].filter(Boolean).join("\n") },
        ];
        const { text, toolCalls } = await streamChat(
          this.env,
          this.model,
          messages,
          round === 0 ? [...tools, IGNORE_DEF] : round < MAX_TOOL_ROUNDS ? tools : [],
          (d) => chunker.push(d).forEach(say),
          signal,
        );
        const rest = chunker.flush();
        if (rest) say(rest);

        const ignore = toolCalls.find((c) => c.function.name === IGNORE_TOOL);
        if (ignore) {
          // Not meant for us: say nothing, and leave no trace in history or the idle clock.
          console.log("turn ignored", { userText, args: ignore.function.arguments });
          await turn.tts.abort();
          if (turn.tts.started) this.send({ type: "stop_playback", turnId: turn.id });
          this.history.splice(userIndex);
          this.lastTurnAt = prevTurnAt;
          this.send({ type: "ignored" });
          return;
        }
        if (!toolCalls.length) {
          turn.replyIndex = this.history.push({ role: "assistant", content: text }) - 1;
          break;
        }
        // Spoken preamble before tools, if the model didn't produce one itself.
        if (!text.trim() && round === 0 && toolCalls.some((c) => !UI_TOOLS.has(c.function.name))) say(PREAMBLE);

        this.history.push({ role: "assistant", content: text || null, tool_calls: toolCalls });
        for (const call of toolCalls) {
          if (signal.aborted) break;
          const content = await this.runTool(call.function.name, call.function.arguments);
          this.history.push({ role: "tool", tool_call_id: call.id, content });
        }
        // Spoke and only drew on screen: nothing left to say, so skip another LLM round trip.
        if (text.trim() && toolCalls.every((c) => UI_TOOLS.has(c.function.name))) break;
      }
      await turn.tts.drain();
      if (!signal.aborted && turn.tts.started) this.send({ type: "tts_end", turnId: turn.id });
    } catch (err) {
      if (!signal.aborted) {
        console.error("turn failed", err);
        await turn.tts.abort();
        this.send({ type: "stop_playback", turnId: turn.id });
        this.send({ type: "error", message: String(err) });
        this.dropDanglingToolCalls();
        // Drop the dangling user message so history stays valid.
        if (this.history.at(-1)?.role === "user") this.history.pop();
      }
    } finally {
      if (this.turn === turn) {
        this.turn = null;
        this.setVoiceState(this.stt ? "listening" : "idle");
      }
      this.trimHistory();
      await this.saveHistory();
    }
  }

  private saveHistory() {
    return this.ctx.storage.put("history", { at: this.lastTurnAt, messages: this.history, surfaces: [...this.surfaces.values()] });
  }

  /** What's on screen, in full, so the model can extend it even after older turns are trimmed. */
  private screenNote(): string {
    if (!this.surfaces.size) return "Device screen state (authoritative): no generated screens are open. Earlier show_ui tool results describe past screens, not screens currently visible. Do not say a table is still on screen unless you show it again.";
    const shown = [...this.surfaces.values()].map(({ v: _v, ...ui }) => ui);
    let json = JSON.stringify(shown);
    if (json.length > 6000) json = json.slice(0, 6000) + "…";
    return `Device screen state (authoritative; overrides old tool results). On screen now (show_ui with the same id replaces one): ${json}`;
  }

  private historyLoaded = false;
  private async loadHistory() {
    if (this.historyLoaded) return;
    this.historyLoaded = true;
    const saved = await this.ctx.storage.get<{ at: number; messages: ChatMessage[]; surfaces?: UiMessage[] }>("history");
    if (saved) {
      this.history = saved.messages;
      this.lastTurnAt = saved.at;
      this.surfaces = new Map((saved.surfaces ?? []).map((u) => [u.id, u]));
    }
  }

  /** Keep the last N user turns, never splitting an assistant/tool sequence. */
  private trimHistory() {
    const userIdx = this.history.map((m, i) => (m.role === "user" ? i : -1)).filter((i) => i >= 0);
    if (userIdx.length > HISTORY_TURNS) this.history = this.history.slice(userIdx[userIdx.length - HISTORY_TURNS]);
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
