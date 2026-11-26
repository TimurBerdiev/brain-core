import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { createInterface } from 'node:readline';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';
import { GitHub } from '../github.js';
import { answerComment, currentCard, grouped } from '../model.js';

test('Boss dogfood seeds once, answers the top card and resumes via a real task.py claim', { timeout: 20000 }, async t => {
  const script = fileURLToPath(new URL('../../tests/boss_dogfood_server.py', import.meta.url));
  const server = spawn('python3', [script, '--port', '0'], { stdio: ['ignore', 'pipe', 'pipe'] });
  const exited = once(server, 'exit');
  let stderr = '';
  server.stderr.setEncoding('utf8').on('data', data => { stderr += data; });
  t.after(async () => {
    server.kill('SIGINT');
    await exited;
  });
  async function ready() {
    const lines = createInterface({ input: server.stdout });
    for await (const line of lines) {
      if (line.startsWith('Boss dogfood: ')) return new URL(line.slice('Boss dogfood: '.length)).origin;
    }
    throw new Error(`Dogfood server did not start: ${stderr}`);
  }
  const origin = await ready();
  const worker = () => fetch(`${origin}/worker/take`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}',
  });
  const client = new GitHub('local/boss', '', { localOrigin: origin });
  const queue = await client.list('queue');
  assert.equal(queue.issues.length, 4, 'repeated seeding did not create duplicate issues');
  assert.equal(queue.warnings.length, 0);
  const rank = issues => Object.fromEntries(grouped(issues, queue.owner))['input-needed'];
  const ranked = rank(queue.issues);
  assert.deepEqual(ranked.map(issue => issue.priorityLabel), ['p1', 'p2']);
  assert.ok(ranked.every(issue => issue.waitingSince));
  assert.equal(queue.issues.filter(issue => issue.status === 'input-needed').length, 3);
  const blocked = await worker();
  assert.equal(blocked.status, 409);
  assert.equal((await blocked.json()).message, 'nothing to pick up');
  const { issue, comments } = await client.detail(ranked[0]);
  assert.equal(comments.length, 1, 'repeated seeding did not post a duplicate card');
  const card = currentCard(issue, comments);
  assert.match(card.question, /worker resume/);
  const reply = answerComment(card.question, 'Run on demand', 'Verify one pickup.');
  const answered = await client.reply(issue, reply);
  assert.deepEqual(answered.labels, ['p1', 'ready']);
  assert.equal(answered.waitingOn, '');
  assert.equal(rank((await client.list('queue')).issues).length, 1);
  const resume = await worker();
  assert.equal(resume.status, 200, await resume.clone().text());
  const pickup = await resume.json();
  assert.equal(pickup.number, issue.number);
  assert.equal(pickup.answer, reply);
  assert.match(pickup.next, /Run one scoped pickup/);
  assert.equal((await client.list('queue')).issues.find(i => i.key === issue.key).claim, pickup.worker);
  const after = await client.detail(issue);
  assert.match(after.comments.at(-1).body, /Resumed.*after reading:[\s\S]*Run on demand[\s\S]*Verify one pickup/);
});

test('the loopback API client rejects non-local origins and never accepts a real token', () => {
  assert.throws(() => new GitHub('local/boss', 'sample-token', { localOrigin: 'http://127.0.0.1:4174' }), /no GitHub token/);
  assert.throws(() => new GitHub('local/boss', '', { localOrigin: 'https://example.com' }), /loopback/);
});
