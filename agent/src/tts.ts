import type { Env } from "./env";
import { TTS_SAMPLE_RATE } from "@home-voice/shared";

/**
 * Aura 2 over Deepgram's Speak WebSocket (Workers AI `websocket: true`).
 *
 * Why not REST: the REST call buffers the whole synthesis (first byte = last byte, ~0.4 s per
 * second of audio); over the socket first audio comes ~0.4 s after Flush whatever the length.
 * Socket behaviour below was measured in cjol/voice-spike (spikes/runtime-host/src/adapters/aura-ws.ts):
 *   - Opening costs ~0.5 s, so one socket lives for the whole session (open it at session start).
 *   - Aura closes ~5 s after the last message *from us* (1011). `Speak " "` keeps it open without
 *     producing audio (the space just joins the next sentence).
 *   - At most 20 Flush per socket per 60 s; the 21st is refused. Past the budget, use a new socket.
 *   - Binary audio carries no request id: after an abort send Clear, and reuse the socket only once
 *     Aura says Cleared; otherwise replace it.
 */
const KEEPALIVE_MS = 2_500;
const FLUSH_LIMIT = 20;
const FLUSH_WINDOW_MS = 60_000;
const CLEAR_TIMEOUT_MS = 1_500;

type AuraMsg = { type: string; sequence_id?: number; [k: string]: unknown };

class SpeakSocket {
  closed = false;
  private lastSent = Date.now();
  private flushAt: number[] = [];
  private keepalive: ReturnType<typeof setInterval>;
  onClose: () => void = () => {};
  onAudio: (pcm: ArrayBuffer) => void = () => {};
  onMessage: (m: AuraMsg) => void = () => {};

  constructor(private readonly ws: WebSocket) {
    ws.addEventListener("message", (e) => {
      if (typeof e.data !== "string") return this.onAudio(e.data as ArrayBuffer);
      try {
        this.onMessage(JSON.parse(e.data));
      } catch {}
    });
    ws.addEventListener("close", () => this.stop());
    ws.addEventListener("error", () => this.stop());
    this.keepalive = setInterval(() => {
      if (Date.now() - this.lastSent >= KEEPALIVE_MS) this.send({ type: "Speak", text: " " });
    }, 500);
  }

  get flushBudget() {
    const now = Date.now();
    return FLUSH_LIMIT - this.flushAt.filter((t) => now - t < FLUSH_WINDOW_MS).length;
  }

  /** Returns the sequence id Aura's Flushed reply will carry (numbered from 0 per socket). */
  flush(): number {
    this.send({ type: "Flush" });
    this.flushAt.push(Date.now());
    return this.flushAt.length - 1;
  }

  send(m: unknown) {
    if (this.closed) return;
    try {
      this.ws.send(JSON.stringify(m));
      this.lastSent = Date.now();
    } catch {
      this.stop();
    }
  }

  private stop() {
    if (this.closed) return;
    this.closed = true;
    clearInterval(this.keepalive);
    this.onClose();
  }

  close() {
    this.send({ type: "Close" });
    this.stop();
    try {
      this.ws.close();
    } catch {}
  }
}

/** One Aura socket for a device session, shared by that session's turns. */
export class AuraTts {
  private sock: SpeakSocket | null = null;
  private opening: Promise<SpeakSocket> | null = null;

  constructor(private readonly env: Env, private readonly voice: string) {}

  /** Open ahead of the first sentence (call at session start). */
  warm() {
    void this.socket().catch((err) => console.warn("aura: warm failed", err));
  }

  async socket(): Promise<SpeakSocket> {
    if (this.sock && !this.sock.closed) return this.sock;
    if (this.opening) return this.opening;
    this.opening = (async () => {
      const resp = await this.env.AI.run(
        "@cf/deepgram/aura-2-en",
        { speaker: this.voice as any, encoding: "linear16", sample_rate: String(TTS_SAMPLE_RATE) } as any,
        { websocket: true },
      );
      const ws = resp.webSocket;
      if (!ws) throw new Error(`Aura returned no WebSocket (HTTP ${resp.status})`);
      ws.binaryType = "arraybuffer";
      ws.accept();
      this.sock = new SpeakSocket(ws);
      return this.sock;
    })().finally(() => (this.opening = null));
    return this.opening;
  }

  /** Stop using a socket (flush budget spent, or a Clear went unconfirmed). */
  retire() {
    this.sock?.close();
    this.sock = null;
  }

  close() {
    this.retire();
  }
}

/**
 * One assistant reply: sentences in, PCM s16le 24 kHz out, in order, as early as possible.
 * One sentence per Speak+Flush, the next only once the previous is Flushed (Aura synthesises
 * faster than real time, so this adds no gap), which gives exact per-sentence audio boundaries.
 * Near the flush budget, queued sentences are batched into one Flush instead.
 */
export class TtsTurn {
  started = false;
  /** Audio sent so far for this reply, in ms; per-sentence end times let us cut history at what was heard. */
  sentMs = 0;
  readonly segments: { text: string; endMs: number }[] = [];

  private watchdog: ReturnType<typeof setTimeout> | null = null;
  private sock: SpeakSocket | null = null;
  private pending: string[] = []; // spoken but not yet flushed
  private inFlight: { seq: number; texts: string[] } | null = null;
  private carry: Uint8Array | null = null;
  private ended = false;
  private aborted = false;
  private done!: () => void;
  private failed!: (e: Error) => void;
  private readonly finished = new Promise<void>((res, rej) => {
    this.done = res;
    this.failed = rej;
  });

  constructor(
    private readonly tts: AuraTts,
    private readonly sendAudio: (pcm: ArrayBuffer) => void,
    private readonly onFirstAudio: () => void,
  ) {
    this.finished.catch(() => {}); // surfaced through drain()
  }

  say(text: string) {
    if (this.aborted || !text.trim()) return;
    this.pending.push(text);
    void this.pump();
  }

  /** No more text: resolves once all audio for the reply has been sent. */
  drain(): Promise<void> {
    this.ended = true;
    void this.pump();
    return this.finished;
  }

  /** Barge-in: drop everything Aura still has for this reply. */
  async abort() {
    if (this.aborted) return;
    this.aborted = true;
    clearTimeout(this.watchdog);
    this.pending = [];
    this.done();
    const s = this.sock;
    if (!s || !this.inFlight || s.closed) return;
    const cleared = await new Promise<boolean>((resolve) => {
      const t = setTimeout(() => resolve(false), CLEAR_TIMEOUT_MS);
      s.onAudio = () => {};
      s.onMessage = (m) => {
        if (m.type === "Cleared") {
          clearTimeout(t);
          resolve(true);
        }
      };
      s.send({ type: "Clear" });
    });
    s.onMessage = () => {};
    if (!cleared) this.tts.retire();
  }

  private async pump() {
    if (this.aborted || this.inFlight) return;
    if (!this.pending.length) {
      if (this.ended) this.done();
      return;
    }
    try {
      let s = await this.tts.socket();
      if (s.flushBudget < 1) {
        s.onClose = () => {};
        this.tts.retire();
        s = await this.tts.socket();
      }
      if (this.aborted || this.inFlight || !this.pending.length) return;
      this.attach(s);
      const texts = s.flushBudget > 2 ? [this.pending.shift()!] : this.pending.splice(0);
      for (const text of texts) s.send({ type: "Speak", text: text + " " });
      this.inFlight = { seq: s.flush(), texts };
      this.watchdog = setTimeout(() => {
        this.failed(new Error("Aura synthesis timed out waiting for Flushed"));
        this.tts.retire();
      }, 30_000);
    } catch (err) {
      this.failed(err instanceof Error ? err : new Error(String(err)));
    }
  }

  private attach(s: SpeakSocket) {
    if (this.sock === s) return;
    this.sock = s;
    s.onClose = () => {
      clearTimeout(this.watchdog);
      if (!this.aborted && (this.inFlight || this.pending.length)) this.failed(new Error("Aura socket closed during synthesis"));
    };
    s.onAudio = (pcm) => {
      if (this.aborted) return;
      if (!this.started) {
        this.started = true;
        this.onFirstAudio();
      }
      this.sendAligned(new Uint8Array(pcm));
    };
    s.onMessage = (m) => {
      if (m.type === "Flushed" && this.inFlight && m.sequence_id === this.inFlight.seq) {
        // Whole batch synthesised. Audio carries no text boundaries, so split the batch's audio
        // across its sentences by length (good enough to cut history at what was heard).
        clearTimeout(this.watchdog);
        const { texts } = this.inFlight;
        const startMs = this.segments.at(-1)?.endMs ?? 0;
        const total = texts.reduce((n, t) => n + t.length, 0) || 1;
        let acc = 0;
        for (const text of texts) {
          acc += text.length;
          this.segments.push({ text, endMs: startMs + ((this.sentMs - startMs) * acc) / total });
        }
        this.inFlight = null;
        void this.pump();
      } else if (m.type === "Warning" || m.type === "Error") {
        console.warn("aura:", JSON.stringify(m).slice(0, 300));
        this.tts.retire(); // don't reuse a socket in doubt
        this.failed(new Error(`Aura ${m.type}`));
      }
    };
  }

  /** Keep frames on 16-bit sample boundaries so the device can write them directly. */
  private sendAligned(chunk: Uint8Array) {
    let data = chunk;
    if (this.carry) {
      data = new Uint8Array(this.carry.length + chunk.length);
      data.set(this.carry);
      data.set(chunk, this.carry.length);
      this.carry = null;
    }
    const even = data.length & ~1;
    if (even < data.length) this.carry = data.slice(even);
    if (even > 0) {
      this.sentMs += (even / 2 / TTS_SAMPLE_RATE) * 1000;
      this.sendAudio(data.slice(0, even).buffer);
    }
  }
}
