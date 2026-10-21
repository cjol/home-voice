import type { Env } from "./env";
import { TTS_SAMPLE_RATE } from "@home-voice/shared";

/**
 * Speaks chunks of text in order as soon as they arrive.
 * Each chunk is synthesised by Workers AI Aura-2 immediately (requests run in
 * parallel) but audio is forwarded strictly in order, as raw PCM s16le 24 kHz.
 *
 * TODO: switch to Aura's WebSocket (streaming text in) if/when Workers AI
 * exposes it for aura-2-en; that would cut first-audio latency further.
 */
export class TtsQueue {
  private chain: Promise<void> = Promise.resolve();
  private aborted = false;
  private carry: Uint8Array | null = null;
  started = false;

  constructor(
    private readonly env: Env,
    private readonly voice: string,
    private readonly sendAudio: (pcm: ArrayBuffer) => void,
    private readonly onFirstAudio: () => void,
    private readonly signal: AbortSignal,
  ) {
    signal.addEventListener("abort", () => (this.aborted = true));
  }

  enqueue(text: string) {
    if (this.aborted || !text.trim()) return;
    const audio = this.synth(text); // start now, play in order
    this.chain = this.chain.then(() => this.pump(audio));
  }

  /** Resolves when everything queued so far has been sent. */
  drain(): Promise<void> {
    return this.chain;
  }

  private async synth(text: string): Promise<Response | null> {
    try {
      return await this.env.AI.run(
        "@cf/deepgram/aura-2-en",
        { text, speaker: this.voice as any, encoding: "linear16", container: "none", sample_rate: TTS_SAMPLE_RATE },
        { returnRawResponse: true, signal: this.signal },
      );
    } catch (err) {
      if (!this.aborted) console.error("tts: synth failed", err);
      return null;
    }
  }

  private async pump(audio: Promise<Response | null>) {
    const resp = await audio;
    if (!resp?.body || this.aborted) return;
    const reader = resp.body.getReader();
    try {
      while (!this.aborted) {
        const { done, value } = await reader.read();
        if (done) break;
        if (!this.started) {
          this.started = true;
          this.onFirstAudio();
        }
        this.sendAligned(value);
      }
    } finally {
      reader.releaseLock();
    }
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
    if (even > 0) this.sendAudio(data.slice(0, even).buffer);
  }
}
