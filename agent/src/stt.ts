import type { Env } from "./env";
import { MIC_SAMPLE_RATE } from "@home-voice/shared";

export type FluxEvent = {
  type?: string; // "Connected" | "TurnInfo" | "Error"
  event?: "Update" | "StartOfTurn" | "EagerEndOfTurn" | "TurnResumed" | "EndOfTurn";
  transcript?: string;
  turn_index?: number;
  end_of_turn_confidence?: number;
};

export interface SttHandlers {
  onEvent(e: FluxEvent): void;
  onClose(code: number, reason: string): void;
}

const BYTES_PER_MS = (MIC_SAMPLE_RATE * 2) / 1000;
/** Mic audio held while Flux connects (anything older is dropped). */
const MAX_BACKLOG_MS = 3_000;
const CONNECT_TIMEOUT_MS = 8_000;
/** Backlog is sent at most this much faster than real time (plus a small burst). */
const PACE = 2;
const BURST_MS = 200;
/** With no mic audio for this long, send silence at real time (see below). */
const IDLE_FILL_AFTER_MS = 1_000;
const FILL_MS = 100;

/**
 * One streaming session with Workers AI Deepgram Flux (WebSocket only). Flux does its own
 * end-of-turn detection, so there's no separate VAD. Lessons from cjol/voice-spike
 * (spikes/runtime-host/src/adapters/flux.ts):
 *   - The session is open when Flux says `Connected`, not when the socket opens: audio sent before
 *     that can be lost (up to ~1 s, so the first word goes missing). Buffer until then.
 *   - Send the backlog at <= 2x real time; faster than that, Flux misplaces words.
 *   - If audio stops, ~5 s later the Workers AI proxy sends Flux a `KeepAlive` that Flux rejects,
 *     closing the socket (1011). Fill gaps with silence at real time instead.
 */
export class FluxStt {
  private ws: WebSocket | null = null;
  private connected = false;
  private opening: Promise<void> | null = null;
  private backlog: ArrayBuffer[] = [];
  private backlogBytes = 0;
  private sentMs = 0;
  private connectedAt = 0;
  private lastAudioAt = 0;
  private timer: ReturnType<typeof setInterval> | null = null;
  private readonly silence = new ArrayBuffer(FILL_MS * BYTES_PER_MS);

  constructor(private readonly env: Env, private readonly handlers: SttHandlers) {}

  get isOpen() {
    return this.connected;
  }

  open(): Promise<void> {
    if (this.opening) return this.opening;
    this.opening = (async () => {
      const resp = await this.env.AI.run(
        "@cf/deepgram/flux",
        {
          encoding: "linear16",
          sample_rate: String(MIC_SAMPLE_RATE),
          eot_threshold: "0.75",
          eot_timeout_ms: "3000",
          mip_opt_out: "true",
        },
        { websocket: true },
      );
      const ws = resp.webSocket;
      if (!ws) throw new Error(`Flux did not return a WebSocket (HTTP ${resp.status})`);
      ws.accept();
      this.ws = ws;
      await new Promise<void>((resolve, reject) => {
        const t = setTimeout(() => reject(new Error("Flux did not say Connected in time")), CONNECT_TIMEOUT_MS);
        ws.addEventListener("message", (ev) => {
          if (typeof ev.data !== "string") return;
          let m: FluxEvent;
          try {
            m = JSON.parse(ev.data);
          } catch {
            return;
          }
          if (m.type === "Connected" && !this.connected) {
            clearTimeout(t);
            this.connected = true;
            this.connectedAt = this.lastAudioAt = Date.now();
            this.timer = setInterval(() => this.tick(), FILL_MS);
            resolve();
          } else if (m.type === "Error" && !this.connected) {
            clearTimeout(t);
            reject(new Error(`Flux error before Connected: ${ev.data}`));
          } else if (m.type === "TurnInfo" || m.event) {
            this.handlers.onEvent(m);
          }
        });
        ws.addEventListener("close", (ev) => {
          clearTimeout(t);
          const wasOpen = this.ws === ws;
          this.teardown();
          if (wasOpen) this.handlers.onClose(ev.code, ev.reason);
          reject(new Error(`Flux closed (${ev.code})`));
        });
      });
      this.tick();
    })().catch((err) => {
      this.teardown();
      throw err;
    });
    return this.opening;
  }

  sendAudio(pcm: ArrayBuffer) {
    if (!this.opening) return;
    this.backlog.push(pcm);
    this.backlogBytes += pcm.byteLength;
    while (this.backlogBytes > MAX_BACKLOG_MS * BYTES_PER_MS && this.backlog.length > 1) {
      this.backlogBytes -= this.backlog.shift()!.byteLength;
    }
    this.lastAudioAt = Date.now();
    if (this.connected) this.tick();
  }

  /** Sends what pacing allows; fills silence when the mic has gone quiet. */
  private tick() {
    const ws = this.ws;
    if (!ws || !this.connected) return;
    const allowedMs = (Date.now() - this.connectedAt) * PACE + BURST_MS;
    try {
      while (this.backlog.length && this.sentMs < allowedMs) {
        const chunk = this.backlog.shift()!;
        this.backlogBytes -= chunk.byteLength;
        ws.send(chunk);
        this.sentMs += chunk.byteLength / BYTES_PER_MS;
      }
      if (!this.backlog.length && Date.now() - this.lastAudioAt > IDLE_FILL_AFTER_MS) {
        ws.send(this.silence);
        this.sentMs += FILL_MS;
      }
    } catch {
      this.close(); // socket died under us; the next session start reopens
    }
  }

  private teardown() {
    if (this.timer) clearInterval(this.timer);
    this.timer = null;
    this.ws = null;
    this.connected = false;
    this.opening = null;
    this.backlog = [];
    this.backlogBytes = 0;
    this.sentMs = 0;
  }

  close() {
    const ws = this.ws;
    this.teardown();
    try {
      ws?.send(JSON.stringify({ type: "CloseStream" }));
      ws?.close(1000, "done");
    } catch {}
  }
}
