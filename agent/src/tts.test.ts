import { test } from 'node:test';
import assert from 'node:assert/strict';
import { TtsTurn } from './tts';
import { SentenceChunker } from './sentences';

function fixture() {
  const sent: any[] = [];
  const sock = {
    closed: false, flushBudget: 20, onClose: () => {},
    onAudio: (_pcm: ArrayBuffer) => {}, onMessage: (_m: any) => {},
    send: (m: any) => sent.push(m), flush: () => 0,
  };
  const tts = { socket: async () => sock, retire: () => { sock.closed = true; sock.onClose(); } };
  const turn = new TtsTurn(tts as any, () => {}, () => {});
  return { sock, turn, sent };
}
const tick = () => new Promise(resolve => setImmediate(resolve));
test('dash sentence is preserved and completes after Flushed', async () => {
  const text = 'Wombat poop is cube-shaped – their intestines have varying stiffness that squeezes it into cubes';
  const chunker = new SentenceChunker();
  assert.deepEqual([...chunker.push(text), chunker.flush()].filter(Boolean), [text]);
  const { turn, sock, sent } = fixture();
  turn.say(text);
  const done = turn.drain();
  await tick();
  assert.equal(sent[0].text.trim(), text);
  sock.onAudio(new ArrayBuffer(480));
  sock.onMessage({ type: 'Flushed', sequence_id: 0 });
  await done;
  assert.equal(turn.sentMs, 10);
});
test('socket closing mid-sentence rejects drain instead of leaving Speaking forever', async () => {
  const { turn, sock } = fixture();
  turn.say('A reply that gets cut off.');
  const done = turn.drain();
  await tick();
  sock.closed = true;
  sock.onClose();
  await assert.rejects(done, /closed during synthesis/);
});
test('manual abort releases drain while synthesis is pending', async () => {
  const { turn, sock } = fixture();
  turn.say('This should stop.');
  const done = turn.drain();
  await tick();
  sock.closed = true;
  await turn.abort();
  await done;
});
test('missing Flushed confirmation times out', async (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const { turn } = fixture();
  turn.say('A sentence without a completion acknowledgement.');
  const done = turn.drain();
  await tick();
  t.mock.timers.tick(30_000);
  await assert.rejects(done, /timed out/);
});
