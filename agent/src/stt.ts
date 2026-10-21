import type { Env } from "./env";
import { MIC_SAMPLE_RATE } from "@home-voice/shared";

export type FluxEvent = {
  event?: "Update" | "StartOfTurn" | "EagerEndOfTurn" | "TurnResumed" | "EndOfTurn";
  transcript?: string;
  turn_index?: number;
  end_of_turn_confidence?: number;
};

export interface SttHandlers {
  onEvent(e: FluxEvent): void;
  onClose(code: number, reason: string): void;
}

/**
 * One streaming session with Workers AI Deepgram Flux (WebSocket only).
 * Flux does its own end-of-turn detection, so we never run a separate VAD.
 */
export class FluxStt {
  private ws: WebSocket | null = null;
  private pending: ArrayBuffer[] = [];
  private opening: Promise<void> | null = null;

  constructor(private readonly env: Env, private readonly handlers: SttHandlers) {}

  get isOpen() {
    return this.ws !== null;
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
      if (!ws) throw new Error(`Flux did not return a WebSocket (status ${resp.status})`);
      ws.accept();
      ws.addEventListener("message", (ev) => {
        if (typeof ev.data !== "string") return;
        try {
          this.handlers.onEvent(JSON.parse(ev.data));
        } catch (err) {
          console.warn("flux: bad message", err);
        }
      });
      ws.addEventListener("close", (ev) => {
        this.ws = null;
        this.opening = null;
        this.handlers.onClose(ev.code, ev.reason);
      });
      ws.addEventListener("error", (ev) => console.warn("flux: error", ev));
      this.ws = ws;
      for (const chunk of this.pending) ws.send(chunk);
      this.pending = [];
    })().catch((err) => {
      this.opening = null;
      throw err;
    });
    return this.opening;
  }

  sendAudio(pcm: ArrayBuffer) {
    if (this.ws) this.ws.send(pcm);
    else if (this.opening) this.pending.push(pcm); // buffer while handshaking
  }

  close() {
    try {
      // TODO: send Flux's CloseStream control message once confirmed on Workers AI, to flush the last turn.
      this.ws?.close(1000, "done");
    } catch {}
    this.ws = null;
    this.opening = null;
    this.pending = [];
  }
}
