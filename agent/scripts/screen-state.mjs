// Run seed, redeploy to evict the DO, then verify. Reads only local dev credentials.
import assert from 'node:assert/strict';
import { readFileSync, writeFileSync } from 'node:fs';
const token = readFileSync(new URL('../.dev.vars', import.meta.url), 'utf8').match(/DEVICE_TOKEN=(\S+)/)[1];
const endpoint = `https://home-voice-agent.cjols.workers.dev/agents/voice-agent/screen-state-regression?token=${token}`;
const ws = new WebSocket(endpoint.replace('https:', 'wss:'));
const queue = [], waiters = [];
ws.onmessage = ({ data }) => {
  if (typeof data !== 'string') return;
  const msg = JSON.parse(data);
  if (msg.type.startsWith('cf_agent_')) return;
  const index = waiters.findIndex(w => w.type === msg.type);
  if (index >= 0) waiters.splice(index, 1)[0].resolve(msg);
  else queue.push(msg);
};
function wait(type) {
  const index = queue.findIndex(m => m.type === type);
  if (index >= 0) return Promise.resolve(queue.splice(index, 1)[0]);
  return new Promise(resolve => waiters.push({ type, resolve }));
}
const timeout = setTimeout(() => { console.error('screen regression timed out'); process.exit(1); }, 60_000);
const send = msg => ws.send(JSON.stringify(msg));
await wait('ready');
if (process.argv[2] === 'seed') {
  send({ type: 'text_input', text: 'Show a persistent fullscreen table titled Screen regression with a single row: banana, 80 g. You must call show_ui. Say only Ready.' });
  const { ui } = await wait('ui');
  await wait('tts_end');
  writeFileSync('/tmp/homevoice-screen-regression.json', JSON.stringify({ id: ui.id }));
  send({ type: 'end_session' });
  console.log('Saved regression table; redeploy before verify to evict in-memory state.');
} else {
  const { id } = JSON.parse(readFileSync('/tmp/homevoice-screen-regression.json', 'utf8'));
  send({ type: 'ui_event', uiId: id, action: 'dismissed' });
  // GET follows dismissal: it cannot hydrate state for the close handler.
  await new Promise(resolve => setTimeout(resolve, 500));
  const status = await (await fetch(endpoint)).json();
  assert.deepEqual(status.screens, []);
  send({ type: 'text_input', text: 'Is a generated table currently on my screen? Answer from the current device screen state, and do not show any UI.' });
  await wait('tts_end');
  const answer = queue.filter(m => m.type === 'assistant_text').map(m => m.text).join(' ');
  console.log('Closed table stays removed after loading persisted history. Agent reply:', answer);
  send({ type: 'end_session' });
}
ws.close();
clearTimeout(timeout);
