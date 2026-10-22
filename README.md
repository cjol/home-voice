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
   ├─ Flux STT   @cf/deepgram/flux (WebSocket; detects end of turn; audio held until `Connected`)
   ├─ LLM        env.LLM_MODEL (default @cf/deepseek-ai/deepseek-v4.1-flash-1017), streamed, tool calls
   │    ├─ Executor MCP client  https://mcp.executor.sh/mcp  (OAuth 2.1 PKCE + DCR,
   │    │    tokens in DO storage via Agents SDK; results unwrapped before the model)
   │    └─ show_ui tool  → `ui` message to the tablet
   └─ TTS        @cf/deepgram/aura-2-en over its Speak WebSocket, voice luna, one socket per session
```

A turn works like this:
1. The wake word fires on the tablet. It beeps, sends `start_session` and starts streaming mic PCM.
2. The DO opens a Flux WebSocket and, in parallel, an Aura socket, which takes about 0.5 s to open. It holds the audio until Flux says `Connected` (audio sent earlier can be lost), then sends the backlog at no more than 2× real time. Flux sends interim transcripts and, at the end of the turn, `EndOfTurn`.
3. The LLM streams its reply. The text is split into sentences, and each sentence goes to Aura as Speak + Flush once the previous one is Flushed. First audio arrives about 0.1–0.3 s after the first sentence. The PCM is sent to the tablet between `tts_start` and `tts_end`.
4. On tool calls, the agent speaks a short preamble first ("Let me check."). The model is prompted to say one itself, and code adds one if it doesn't. Then it runs the tools and loops, up to 5 rounds.
5. After playback the tablet keeps streaming for an 8 s follow-up window. If nothing happens it sends `end_session` and goes back to wake-word-only listening.

**Playback and microphone:** Android currently uses half-duplex audio to prevent speaker feedback on phones whose AEC does not remove the assistant's voice. During playback and for 600 ms afterwards, microphone frames are replaced with silence for STT and are not passed to the wake-word detector. Listening resumes after the hardware playback buffer drains. Speech never interrupts a reply. Use **Stop reply** in either transcript view to cancel manually, or wait for the hardware playback to finish before following up. Closing the transcript ends the session immediately and returns to wake-word-only listening. The protocol still supports interruption for a future reliable echo-cancellation path.

**What was heard:** either way, the tablet reports `played` (ms of that reply that came out of the speaker, from the AudioTrack playback head). History keeps only the sentences heard, ending with "—". The prompt tells the model what the dash means, so it can offer to finish. Many of these patterns come from [cjol/voice-spike](https://github.com/cjol/voice-spike) (`spikes/runtime-host`).

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
| `played` | `turnId, playedMs` | after a barge-in: how much of that reply played |
| `playback_done` | `turnId` | TTS finished playing |
| `text_input` | `text` | typed/test input; runs a turn without STT |
| `ui_event` | `uiId, action, value?` | tap on an overlay (TODO: wired to the LLM) |
| `ping` | | |

**Agent → device (JSON):**

| type | fields | meaning |
|---|---|---|
| `ready` | `v, model, tools` | `tools`: `connected` \| `auth_required` \| `error` \| `disabled` |
| `state` | `state` | `idle` \| `listening` \| `thinking` \| `speaking` |
| `transcript` | `text, final` | live STT |
| `assistant_text` | `turnId, text` | caption of what is being spoken |
| `tts_start` / `tts_end` | `turnId` (+`sampleRate, encoding`) | brackets binary TTS audio |
| `stop_playback` | `turnId?` | server barge-in: flush now |
| `ui` | `ui: UiMessage` | show on screen |
| `dismiss_ui` | `uiId?` | |
| `auth_required` | `url` | Executor OAuth needed; the tablet posts a tap-to-sign-in notification |
| `error` | `message` | |
| `ignored` | | the agent judged the last utterance not meant for it; the device drops it from the transcript and ends the session |
| `new_conversation` | | history was reset after 10 min idle; the device clears earlier turns from the transcript |

**UiMessage v1:** fields common to all kinds are `{v:1, id, title?, ttlMs?, display?}`, plus one of the following.
- `card`: `body, subtitle?, imageUrl?, tone?`
- `list`: `items[{title, subtitle?, trailing?}]`
- `chart`: `chartType bar|line, unit?, series[{label, points[{x, y}]}]`
- `status`: `text, tone?, progress? 0..1`
- `table`: `columns[{label, align?}], rows[[cell…]], footer?` (a totals row), `note?`

**Display modes:**
- **`display: "panel"`** (the default, except for tables): the UI goes inside the assistant panel over the current app and fades after `ttlMs`, 20 s by default.
- **`display: "fullscreen"`** (the default for tables): the UI takes over the screen and stays until it's closed or replaced (`ttlMs` 0). The conversation shows as a strip underneath.
- **Locked or sleeping screen:** UI always goes full screen, because overlays can't show there.

**Ids and building across turns:** a UI with an existing `id` replaces that screen in place, which is how the agent builds on a screen over several turns, such as a running carb table.
- **What the agent sees:** it gets an "On screen now" note with the full content of every open screen, so it can extend one even after older turns have been trimmed from history.
- **Closing:** `close_ui` closes screens (server message `dismiss_ui`). The device reports closing a screen itself, by the ✕ button, Back or a timeout, as `ui_event {action: "dismissed"}`.

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

Deployed at `https://home-voice-agent.cjols.workers.dev`. The config uses cf's native format:
- **[`agent/cloudflare.config.ts`](agent/cloudflare.config.ts):** the Worker, its bindings and the `VoiceAgent` Durable Object export.
- **[`agent/wrangler.config.ts`](agent/wrangler.config.ts):** tooling, such as binding the dev server to `laptop:8787`.

`cf deploy` uses your `cf` login, so it needs no API token. `env` types are generated from the config by `cf workers types`, which `npm run typecheck` runs.

Node 24 LTS is pinned in `mise.toml`, since the native config needs Node 22.18 or later. Run commands through `mise exec --`, or let mise activate in your shell.

```sh
npm install                    # at repo root (npm workspaces; .npmrc nests deps per workspace)
npm run typecheck              # regenerates agent/.cloudflare/types, then tsc
npm run deploy -w agent        # cf deploy
```

**Secrets:** `DEVICE_TOKEN` is the shared secret the tablet sends. It's declared in `cloudflare.config.ts` as `bindings.secret()`, set on the deployed Worker, and read from `agent/.dev.vars` (gitignored) for local dev and the test scripts. To rotate it, edit `.dev.vars`, then run `npx cf deploy --secrets-file .dev.vars`.

**Test without a device:** `node scripts/smoke.mjs` plays the tablet. It streams a spoken PCM fixture over the WebSocket, prints every message, saves the TTS reply to `scripts/out/*.wav`, and reports the latency from end of turn to first audio.

```sh
scripts/make-fixtures.sh                         # espeak questions -> scripts/fixtures/*.raw
node scripts/smoke.mjs math                      # audio path (Flux -> LLM -> Aura)
node scripts/smoke.mjs "text:what's 7 times 6"   # skip STT
IDLE_MS=30000 node scripts/smoke.mjs math        # idle first, so the DO hibernates
```

**Local dev:**
1. Run `npm run dev -w agent`. That's `wrangler dev --experimental-new-config`, because `cf dev` (beta.20) fails with `[object Object]` when it opens the remote connection. It binds to `laptop:8787` and uses `agent/.dev.vars`.
2. Point a client at it with `node scripts/smoke.mjs math http://laptop:8787`.

Workers AI is always remote, even in local dev, and that needs wrangler credentials. `cf`'s login isn't shared with wrangler, so `agent/.env` (gitignored) holds `CLOUDFLARE_API_TOKEN` for an account token named `home-voice-dev`:
- **ID:** `64d78e9bb77ea72a8fbaab01354c279e`
- **Scopes:** Workers Scripts write, Account Settings read, Workers AI read
- **Expires:** 2027-01-19

Deploys don't use this token.

**Hibernation:** the Durable Object hibernates between sessions, which wipes its in-memory fields.
- The device connection is re-attached from `getConnections()`.
- Conversation history is persisted in DO storage. It's reset after 10 min idle.

**Change the model** per device without redeploying:

```sh
curl -X POST "https://<worker>/agents/voice-agent/<deviceId>/config?token=$DEVICE_TOKEN" \
  -H 'content-type: application/json' -d '{"model":"@cf/openai/gpt-oss-120b"}'
```

Send `{"model":""}` to go back to the default. To change the default for all devices, edit `LLM_MODEL` in `cloudflare.config.ts`. `GET` on the same URL (without `/config`) returns the model, the MCP servers and the stored history.

**Model choice:** the default is `@cf/deepseek-ai/deepseek-v4.1-flash-1017`. In the model benchmark it was the only Workers AI model that answered all four test questions correctly through Executor, in 4–16 s. The model must make real tool calls. These two don't, and `/config` rejects them (the list is `NON_TOOL_MODELS` in `agent/src/llm.ts`):
- `@cf/mistralai/mistral-small-3.1-24b-instruct` rejects requests that include tools.
- `@cf/meta/llama-4-scout-17b-16e-instruct` prints tool calls as text instead of making them.

**Executor sign-in:** Executor is off by default (`EXECUTOR_ENABLED` in `cloudflare.config.ts`), so the voice loop can be tested on its own. With it off, the model only has the `show_ui` tool. Once enabled, the first time the tablet connects, the DO starts OAuth with dynamic client registration. The tablet shows a "Connect Executor" notification; tap it and sign in. The tokens persist in the DO.

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

**What you see during a conversation:**
- **Assistant panel:** a compact panel slides up over whatever app is in front. It shows the conversation (live transcript, replies as they're spoken) and any panel UI. It stays about 12 s after the session ends, longer if it holds UI.
- **Full-screen view:** shows full-screen UI with the conversation underneath and a ✕ to close. While a full-screen screen is open, the conversation is kept across sessions.
- **Where the panel can't appear:** Android hides third-party overlays over Settings and permission dialogs.

### Emulator (no microphone needed)

There's a Pixel Tablet AVD (`HomeVoice_Tablet`, API 35, x86_64). The emulator can't use host audio here (its Pulse driver fails to start), so debug builds take speech through `adb` instead.

**How audio gets in:** the debug-only `DebugReceiver` lives in `src/debug/` and isn't in release builds. It injects PCM into the mic path. That runs the real wake-word model, streaming, the session timers and barge-in.

```sh
cd android
scripts/emu.sh boot                       # headless emulator
scripts/emu.sh install                    # build, install, grant perms incl. overlay, configure from agent/.dev.vars, start
scripts/emu.sh speak "what is 7 times 6"  # espeak "Hey Jarvis." + question -> injected as mic audio
scripts/emu.sh say "show me a chart"      # text turn, skips STT
scripts/emu.sh logs                       # VoiceService log: session start/end, transcripts, replies, barge-in
scripts/emu.sh shot /tmp/x.png            # screenshot (overlay)
```

Debug builds allow plain `ws://`, so the emulator can use a local `wrangler dev`: `adb reverse tcp:8787 tcp:<port>` to a host port that reaches `laptop:8787`, then `AGENT_URL=http://127.0.0.1:8787 scripts/emu.sh install`.

## Status / TODO

These are known gaps, in rough priority order:
- [x] End to end on the emulator against the deployed agent: the wake word (espeak "Hey Jarvis" scores 0.56–0.82), Flux, DeepSeek, Aura, the `show_ui` overlay coming to the front and auto-dismissing, and barge-in by wake word and by Flux `StartOfTurn`.
- [ ] Try it on the real tablet: AEC on hardware, real voices, screen off, boot.
- [ ] Verify openWakeWord scores against the Python reference, and tune the threshold.
- [x] Aura over WebSocket, Flux `Connected`/pacing/silence fill, history cut to what was heard.
- [ ] Talker/brain split for Executor (from voice-spike): a fast model talks, and slow tool work runs as a background `ask_brain` task whose answer arrives later. Do this when Executor is enabled.
- [ ] Silero VAD on mic audio to drop words Flux invents from noise (voice-spike `adapters/vad.ts`).
- [ ] Drop the wake word from a turn by Flux word timings rather than a regex. Today, when Flux merges it into the question, a mishearing such as "HRV" can get through.
- [ ] Speculative LLM start on `EagerEndOfTurn`.
- [ ] One session (the third in a row, started ~4 s after the previous one ended) produced partial transcripts but no final turn, and the retry worked. Logging was added on both sides to catch it next time.
- [ ] Nutrition values vary between runs (banana came out at 20, 22 or 23 g of carbs per 100 g). For consistent tracking, give the agent a food database tool through Executor instead of model memory.
- [ ] Code-level confirmation gate for mutating tools, on top of the prompt policy.
- [ ] Feed `ui_event` taps back into the conversation. Load images in cards.
- [ ] Compare `USAGE_ASSISTANT` and `USAGE_VOICE_COMMUNICATION` playback for AEC quality.
- [ ] Custom wake word training.

**Stopping:** use **Stop listening** in the persistent Home Voice notification, or **Stop** on the app settings screen. This stops audio, removes assistant screens and disables startup on boot until you choose **Save & start** again. Swiping the app out of Recents does not stop its background service.

### Conversation and playback behaviour

- Each device has one history containing up to 12 user turns. The next session or request after 10 minutes without a new turn clears that history, and the agent sends `new_conversation` so the on-screen transcript drops the old turns too.
- **Speech not meant for the assistant:** in the first round of a turn the model also gets an `ignore` tool. It calls it, silently, for fragments, talk addressed to someone else and non sequiturs. The utterance is removed from history and the idle clock, and the tablet drops it from the transcript and goes back to wake-word listening.
- **Wake word sensitivity:** the default threshold is 0.7 (it was 0.5, which fired on background speech), and the score must stay above it for two consecutive 80 ms chunks. Adjust the threshold with the slider on the settings screen; "Last wake" shows the score of the most recent activation. `scripts/emu.sh install` sets 0.5 because espeak scores lower than real voices (`WAKE_THRESHOLD` overrides).
- **Hide conversation:** with a generated screen up, both views show a "Hide conversation" button. It ends the conversation and leaves only the screen, the status line and the end/stop button. The transcript comes back with the next reply; speech the agent ignores doesn't bring it back. Closing the transcript ends listening but does not erase history. Open generated screens remain separate context until closed.
- Wake detection sends a two-second local pre-roll buffer to STT, preserving words spoken immediately after “Hey Jarvis”. Audio stays local before detection.
- Transcript text scrolls inside a bounded area without animating the overlay's size on each update. Full-screen views use smaller margins and text on phones, with a separately scrollable transcript.
- TTS socket closure and a missing flush acknowledgement fail the turn instead of leaving it speaking indefinitely. Playback completion uses the hardware playback head, with a bounded drain wait.

TTS regression checks (from the repository root):
```sh
agent/node_modules/wrangler/node_modules/esbuild/bin/esbuild agent/src/tts.test.ts --bundle --platform=node --format=esm --outfile=/tmp/homevoice-tts-test.mjs
mise exec -- node --test /tmp/homevoice-tts-test.mjs
```

Open screen contents are persisted separately from conversation messages and supplied to every model turn, with no time or turn-count expiry. This includes tables even after the 10-minute history reset. Closing a screen removes this separate context. The combined screen note is capped at 6,000 characters, so very large screens may be truncated.

Native-library alignment check after building: `python3 android/scripts/check-native-alignment.py android/app/build/outputs/apk/debug/app-debug.apk`. ONNX Runtime 1.23.0 replaces the older JNI library that triggered Android’s 16 KB compatibility warning.

### Transcript controls and screen reconciliation

Both transcript views have one icon at the bottom: a stop square while speaking (cancels speech and opens a follow-up listening window), otherwise a crossed conversation bubble (ends the conversation and follow-up listening). Ending the conversation during speech takes two taps. In full-screen mode, ending the conversation leaves the table open. The table's **✕** closes the table and ends listening. The transcript follows new content after layout without animating its scroll position.

The tablet reports `ui_state {openIds}` on every connection, so tables closed while offline or lost on an app restart are removed from the agent's saved screen context. Dismiss events load persisted state before removing a screen, including after Durable Object hibernation. Each model request gets an authoritative screen-state note, including an explicit empty state when nothing is open.

To exercise the persisted-dismissal regression against the deployed test device:
```sh
mise exec -- node agent/scripts/screen-state.mjs seed
mise exec -- npm run deploy -w agent  # evicts in-memory DO state
mise exec -- node agent/scripts/screen-state.mjs verify
```

The table activity keeps Android’s status and navigation bars visible. When the wake word fires with the screen off or locked, the same activity opens immediately with a transcript and the bottom action, even without a generated card. It displays above the keyguard without unlocking the device, and closes when a conversation-only session ends.

### Sharing a personal APK

Run `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 android/scripts/build-share.sh` to produce `dist/home-voice-0.1.0-arm64.apk` and setup instructions. See [android/SHARING.md](android/SHARING.md). This non-debuggable ARM64 build includes wake-word models and excludes the ADB debug receiver. It is signed with the existing local development key. Agent credentials are entered on the device and are not embedded in the APK.
