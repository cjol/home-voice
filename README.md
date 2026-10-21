<img src="assets/icon.svg" width="64" height="64" alt="">

# home-voice

A personal voice assistant that replaces the Home Assistant voice pipeline. A docked Pixel Tablet listens for a wake word on the device, then streams audio to a Cloudflare Agent. The agent runs speech-to-text, an LLM with tools (through Executor) and text-to-speech, and can send UI screens back to the tablet.

```
android/   Kotlin + Jetpack Compose app (wake word, audio I/O, UI overlay)
agent/     Cloudflare Worker + Agents SDK Durable Object (one per device)
shared/    Versioned message protocol + generative UI schema (TS source of truth, JSON Schema)
```

## Architecture

```
 Pixel Tablet (VoiceService: foreground service, type=microphone)
 ┌─────────────────────────────────────────────────────────────┐
 │ AudioRecord VOICE_COMMUNICATION 16 kHz + AEC/NS/AGC         │
 │   └─ 80 ms frames ─► openWakeWord (ONNX) ── idle: stays local
 │                         │ wake word                          │
 │                         ▼                                    │
 │   WebSocket ◄──────── PCM frames (session) ─────────────────►│──┐
 │   AudioTrack 24 kHz ◄─ TTS PCM frames                        │  │
 │   OverlayActivity ◄─── `ui` messages (full-screen, ttl)      │  │
 └─────────────────────────────────────────────────────────────┘  │
                                                                   │ wss
 Cloudflare: Worker ─► VoiceAgent DO (name = deviceId)  ◄──────────┘
   ├─ Flux STT   @cf/deepgram/flux (WebSocket; detects end of turn)
   ├─ LLM        env.LLM_MODEL (default @cf/openai/gpt-oss-120b), streamed, tool calls
   │    ├─ Executor MCP client  https://mcp.executor.sh/mcp  (OAuth 2.1 PKCE + DCR,
   │    │    tokens in DO storage via Agents SDK; results unwrapped before the model)
   │    └─ show_ui tool  → `ui` message to the tablet
   └─ TTS        @cf/deepgram/aura-2-en, voice luna, per sentence as text streams
```

A turn works like this:
1. The wake word fires on the tablet. It beeps, sends `start_session` and starts streaming mic PCM.
2. The DO opens a Flux WebSocket and forwards the audio. Flux sends interim transcripts and, at the end of the turn, `EndOfTurn`.
3. The LLM streams its reply. The text is split into sentences, and each sentence goes to Aura as it completes. Requests run in parallel and the audio is played in order. The PCM is sent to the tablet between `tts_start` and `tts_end`.
4. On tool calls, the agent speaks a short preamble first ("Let me check."). The model is prompted to say one itself, and code adds one if it doesn't. Then it runs the tools and loops, up to 5 rounds.
5. After playback the tablet keeps streaming for an 8 s follow-up window. If nothing happens it sends `end_session` and goes back to wake-word-only listening.

**Barge-in** has two paths:
- **Server:** the mic keeps streaming during playback, with AEC on. A Flux `StartOfTurn` while the agent is thinking or speaking aborts the LLM and TTS and sends `stop_playback`.
- **Local:** the wake word heard during playback flushes the AudioTrack and sends `interrupt`.

### Why openWakeWord (not microWakeWord)

- **Pretrained models:** openWakeWord has several (`hey_jarvis`, `alexa`, `hey_mycroft`, …) and a documented pipeline for training a custom phrase from synthetic TTS data. You can ship today and train your own word later.
- **Runs on stock ONNX Runtime:** the three small models (mel → embedding → classifier) run on `onnxruntime-android` with no custom ops. microWakeWord's streaming TFLite models are tuned for ESP32 / TFLite Micro, and Android would need streaming-state handling and TFLite plumbing.
- **CPU cost doesn't matter here:** the tablet is docked and powered. openWakeWord uses a few percent of one core, which is higher than microWakeWord, but irrelevant on this device.
- **Shared embedding:** the embedding model is common to all wake words, so you can run several of them, or later a "stop" word, for little extra cost.

## Message protocol (v1)

Source of truth: [`shared/src/protocol.ts`](shared/src/protocol.ts) and [`shared/src/ui.ts`](shared/src/ui.ts). The Kotlin mirror is in `android/app/src/main/java/net/littlesavage/homevoice/protocol/`. A JSON Schema of the UI payload is in [`shared/schema/ui.v1.schema.json`](shared/schema/ui.v1.schema.json).

**Endpoint:** `wss://<worker>/agents/voice-agent/<deviceId>?token=<DEVICE_TOKEN>`

**Binary frames:**
- Device → agent: PCM s16le mono 16 kHz.
- Agent → device: PCM s16le mono 24 kHz, sent only between `tts_start` and `tts_end`.

**Device → agent (JSON):**

| type | fields | meaning |
|---|---|---|
| `hello` | `v, deviceId, appVersion?` | first message |
| `start_session` | `wakeWord?` | wake word fired; audio follows |
| `end_session` | `reason?` | follow-up window over |
| `interrupt` | | local barge-in |
| `playback_done` | `turnId` | TTS finished playing |
| `ui_event` | `uiId, action, value?` | tap on an overlay (TODO: wired to the LLM) |
| `ping` | | |

**Agent → device (JSON):**

| type | fields | meaning |
|---|---|---|
| `ready` | `v, model, tools` | `tools`: `connected` \| `auth_required` \| `error` |
| `state` | `state` | `idle` \| `listening` \| `thinking` \| `speaking` |
| `transcript` | `text, final` | live STT |
| `assistant_text` | `turnId, text` | caption of what is being spoken |
| `tts_start` / `tts_end` | `turnId` (+`sampleRate, encoding`) | brackets binary TTS audio |
| `stop_playback` | `turnId?` | server barge-in: flush now |
| `ui` | `ui: UiMessage` | show on screen |
| `dismiss_ui` | `uiId?` | |
| `auth_required` | `url` | Executor OAuth needed; the tablet posts a tap-to-sign-in notification |
| `error` | `message` | |

**UiMessage v1:** fields common to all kinds are `{v:1, id, title?, ttlMs?}`, plus one of the following. `ttlMs` defaults to 20 s, and 0 means stay until dismissed.
- `card`: `body, subtitle?, imageUrl?, tone?`
- `list`: `items[{title, subtitle?, trailing?}]`
- `chart`: `chartType bar|line, unit?, series[{label, points[{x, y}]}]`
- `status`: `text, tone?, progress? 0..1`

`tone` is one of `neutral|info|success|warning|error`.

**Versioning rules:**
- Adding an optional field doesn't bump the version.
- Any other change bumps `v`.
- Clients ignore unknown fields and unknown message types.

### Executor results

Executor returns MCP envelopes `{content:[{type:"text",text}], structuredContent?, isError?}`, often nested: `execute` returns an envelope whose text is JSON that contains more envelopes from the app tools. [`agent/src/unwrap.ts`](agent/src/unwrap.ts) unwraps them in code before the model sees anything:
- It prefers `structuredContent`.
- Otherwise it JSON-parses the text parts, recursively.
- It truncates the result to 12 kB.

Run its test with `npm run test:unwrap -w agent`. The system prompt asks for one `search` and then one `execute` per request.

### Confirmation policy

The policy lives in the system prompt in [`agent/src/prompt.ts`](agent/src/prompt.ts):
- **Reading never needs confirmation.**
- **External changes need a spoken yes in the next turn.** This covers sending, deleting or archiving email, messages, purchases, calendar changes and other people's data. The agent says what it will do and asks "Shall I go ahead?". Anything other than a clear yes means it doesn't act.
- **Home devices are exempt, except locks, alarms and doors.**

The policy is enforced only by the prompt. Code doesn't gate anything yet (see TODO).

## Setup

### Agent (Cloudflare)

```sh
npm install                    # at repo root (npm workspaces)
npm run typecheck
npx wrangler login             # wrangler is not logged in yet
cd agent
npx wrangler secret put DEVICE_TOKEN   # long random string; same value goes in the app
npm run deploy                 # account 39f3f1b5073547672a0d7ad67319df81 is set in wrangler.jsonc
```

**Local dev:**
1. Run `cp .dev.vars.example agent/.dev.vars` and set a token.
2. Run `npm run dev -w agent`, which binds to `laptop:8787`.

Workers AI calls go to your account even in local dev. The Executor OAuth callback needs a public URL, so do the first sign-in against the deployed worker, or set `PUBLIC_URL`.

**Change the model** per device without redeploying:

```sh
curl -X POST "https://<worker>/agents/voice-agent/<deviceId>/config?token=$DEVICE_TOKEN" \
  -H 'content-type: application/json' -d '{"model":"@cf/meta/llama-4-scout-17b-16e-instruct"}'
```

To change the default for all devices, edit `LLM_MODEL` in `wrangler.jsonc`.

**Executor sign-in:** the first time the tablet connects, the DO starts OAuth with dynamic client registration. The tablet shows a "Connect Executor" notification; tap it and sign in. The tokens persist in the DO.

### Android

Requirements: JDK 17 and Android SDK platform 35 + build-tools. The repo has a Gradle wrapper.

```sh
cd android
./scripts/fetch-models.sh            # openWakeWord models -> assets (not committed); optional arg = model name
echo "sdk.dir=$HOME/Android/Sdk" > local.properties
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On the tablet:
1. Enter the worker URL and device token, then tap **Save & start**. Grant the mic and notification permissions.
2. Grant **Display over other apps**. Android 14+ won't let a microphone foreground service start straight from `BOOT_COMPLETED`, so at boot the app launches an invisible activity, which this permission allows. The same permission lets the UI overlay come to the front over other apps. Without it, the app falls back to a full-screen-intent notification, which only appears full-screen when the tablet is locked.
3. Turn off battery optimisation.

The default wake word is **"Hey Jarvis"**. Use **Test overlay** to check the UI path.

## Status / TODO

These are known gaps, in rough priority order:
- [ ] End-to-end test on the device (nothing has run against live Workers AI yet).
- [ ] Verify openWakeWord scores against the Python reference, and tune the threshold.
- [ ] Flux `CloseStream` on end of session, and speculative LLM start on `EagerEndOfTurn`.
- [ ] Persist conversation history in DO SQL. It's in memory now and resets after 10 min idle.
- [ ] Code-level confirmation gate for mutating tools, on top of the prompt policy.
- [ ] Feed `ui_event` taps back into the conversation. Load images in cards.
- [ ] Aura streaming over WebSocket (text in, audio out) once it's available on Workers AI. It's per-sentence HTTP now.
- [ ] Compare `USAGE_ASSISTANT` and `USAGE_VOICE_COMMUNICATION` playback for AEC quality.
- [ ] Custom wake word training.
