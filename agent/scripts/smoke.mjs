// Pretends to be the tablet: streams a PCM fixture to the agent and records the reply.
// Usage: node scripts/smoke.mjs [fixture=math | "text:<words>"] [url=https://home-voice-agent.cjols.workers.dev]
//   text:<words> sends a text_input message instead of audio (skips STT).
// Reads DEVICE_TOKEN from agent/.dev.vars. Writes scripts/out/<fixture>.wav (TTS reply).
import { readFileSync, writeFileSync, mkdirSync } from "node:fs";
import { execFileSync } from "node:child_process";

const here = new URL(".", import.meta.url).pathname;
const fixture = process.argv[2] ?? "math";
const base = (process.argv[3] ?? process.env.AGENT_URL ?? "https://home-voice-agent.cjols.workers.dev").replace(/\/$/, "");
const token = readFileSync(`${here}../.dev.vars`, "utf8").match(/DEVICE_TOKEN=(\S+)/)?.[1];
const textInput = fixture.startsWith("text:") ? fixture.slice(5) : null;
const pcm = textInput ? null : readFileSync(`${here}fixtures/${fixture}.raw`);
const deviceId = process.env.DEVICE_ID ?? "smoke-test";

const t0 = Date.now();
const ts = () => `${((Date.now() - t0) / 1000).toFixed(2)}s`.padStart(7);
const ws = new WebSocket(`${base.replace(/^http/, "ws")}/agents/voice-agent/${deviceId}?token=${token}`);
ws.binaryType = "arraybuffer";
const tts = [];
let firstAudioAt = 0, eotAt = 0;

const finish = (code) => {
  mkdirSync(`${here}out`, { recursive: true });
  if (tts.length) {
    const raw = `${here}out/${fixture}.raw`;
    writeFileSync(raw, Buffer.concat(tts));
    execFileSync("ffmpeg", ["-loglevel", "error", "-y", "-f", "s16le", "-ar", "24000", "-ac", "1", "-i", raw, `${here}out/${fixture}.wav`]);
    console.log(`${ts()} wrote out/${fixture}.wav (${(Buffer.concat(tts).length / 48000).toFixed(1)}s audio)`);
  }
  if (eotAt && firstAudioAt) console.log(`latency end-of-turn -> first audio: ${((firstAudioAt - eotAt) / 1000).toFixed(2)}s`);
  ws.close();
  process.exit(code);
};
setTimeout(() => { console.log(`${ts()} TIMEOUT`); finish(1); }, 60_000 + Number(process.env.IDLE_MS ?? 0));

async function stream() {
  const frame = 2560; // 80 ms, like the tablet
  const silence = Buffer.alloc(16000 * 2 * 4); // 4 s trailing silence so Flux can end the turn
  const all = Buffer.concat([pcm, silence]);
  for (let i = 0; i < all.length; i += frame) {
    ws.send(all.subarray(i, i + frame));
    await new Promise((r) => setTimeout(r, 80));
  }
}

ws.onopen = () => {
  console.log(`${ts()} open`);
  ws.send(JSON.stringify({ type: "hello", v: 1, deviceId }));
};
ws.onmessage = (ev) => {
  if (typeof ev.data !== "string") {
    if (!firstAudioAt) { firstAudioAt = Date.now(); console.log(`${ts()} first TTS audio`); }
    tts.push(Buffer.from(ev.data));
    return;
  }
  const m = JSON.parse(ev.data);
  if (m.type?.startsWith("cf_agent_")) return; // Agents SDK protocol frames
  if (m.type === "transcript" && m.final) eotAt = Date.now();
  if (process.env.VERBOSE || m.type !== "transcript" || m.final) console.log(`${ts()} <- ${ev.data}`);
  if (m.type === "ready") {
    const delay = Number(process.env.IDLE_MS ?? 0); // simulate idle time between sessions (DO hibernation)
    if (delay) { console.log(`${ts()} idling ${delay}ms`); setTimeout(() => ws.onmessage({ data: JSON.stringify({ type: "go" }) }), delay); return; }
  }
  if (m.type === "ready" || m.type === "go") {
    if (textInput) {
      eotAt = Date.now();
      ws.send(JSON.stringify({ type: "text_input", text: textInput }));
    } else {
      ws.send(JSON.stringify({ type: "start_session" }));
      stream();
    }
  }
  if (m.type === "tts_end") setTimeout(() => finish(0), 300);
  if (m.type === "error") finish(1);
};
ws.onerror = (e) => { console.log(`${ts()} ws error`, e.message ?? e); };
ws.onclose = (e) => console.log(`${ts()} closed ${e.code} ${e.reason}`);
