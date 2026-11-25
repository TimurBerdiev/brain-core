import assert from 'node:assert/strict';
import { test } from 'node:test';
import { GitHub } from '../github.js';

const rawIssue = { number: 42, title: 'Plan the launch', body: 'Waiting on: A decision\n\nCurrent details.',
  labels: [{ name: 'input-needed' }, { name: 'ws:ops' }], state: 'open', updated_at: '2026-11-25T10:00:00Z',
  html_url: 'https://github.com/owner/brain/issues/42' };
const yamlFile = text => ({ content: Buffer.from(text).toString('base64') });

test('workspace discovery, issue paging, fresh card reply and authenticated image transport', async t => {
  const requests = [];
  t.mock.method(globalThis, 'fetch', async (url, options) => {
    assert.equal(url.origin, 'https://api.github.com');
    assert.equal(options.headers.Authorization, 'Bearer sample-token');
    assert.equal(options.credentials, 'omit');
    assert.equal(options.cache, 'no-store');
    requests.push([options.method, url.pathname, options.body && JSON.parse(options.body)]);
    const path = url.pathname.replace(/^\/repos\/owner\//, '');
    const page = url.searchParams.get('page');
    switch (path) {
      case 'brain/branches': return Response.json([]);
      case 'brain/contents/repos.yaml': return Response.json(yamlFile('repos:\n  site: { remote: "git@github.com:owner/web.git", tasks: assigned }'));
      case 'brain/contents/workspace.yaml': return Response.json(yamlFile('github: "owner" # profile'));
      case 'brain/issues':
        if (!page) return Response.json([rawIssue]);
        if (page === '1') return Response.json(Array.from({ length: 100 }, () => ({ pull_request: {} })));
        return Response.json([rawIssue]);
      case 'web/issues': return Response.json([{ ...rawIssue, number: 38 }]);
      case 'web/issues/38/comments':
        return Response.json(options.method === 'GET' ? [{ body: '<!-- card -->\n### Launch?', user: { login: 'agent' } }] : {});
      case 'web/issues/38': return Response.json(options.method === 'GET' ? { ...rawIssue, number: 38 }
        : { ...rawIssue, number: 38, body: 'Current details.', labels: [{ name: 'ready' }] });
      case 'web/contents/38/evidence.png':
        assert.equal(url.searchParams.get('ref'), 'cards');
        assert.equal(options.headers.Accept, 'application/vnd.github.raw+json');
        return new Response('image data', { headers: { 'Content-Type': 'image/png' } });
      default: assert.fail(`Unexpected request: ${path}`);
    }
  });
  const client = new GitHub('owner/brain', 'sample-token');
  await client.check();
  const queue = await client.list('queue');
  assert.equal(queue.owner, 'owner');
  assert.deepEqual(queue.warnings, []);
  assert.deepEqual(queue.issues.map(i => i.ref), ['#42', 'site#38']);
  const { issue, comments } = await client.detail(queue.issues[1]);
  assert.equal(comments[0].author, 'agent');
  assert.equal((await client.reply(issue, '<!-- answer -->')).status, 'ready');
  assert.deepEqual(requests.slice(-3), [
    ['GET', '/repos/owner/web/issues/38', undefined],
    ['POST', '/repos/owner/web/issues/38/comments', { body: '<!-- answer -->' }],
    ['PATCH', '/repos/owner/web/issues/38', { body: 'Current details.', labels: ['ready', 'ws:ops'] }],
  ]);
  const url = await client.image('https://github.com/owner/web/raw/cards/38/evidence.png');
  assert.match(url, /^blob:/);
  URL.revokeObjectURL(url);
  assert.equal(await client.image('https://example.org/evidence.png'), 'https://example.org/evidence.png');
});

test('search and completed discover task repos on a fresh connection without a queue refresh', async t => {
  let registryReads = 0;
  t.mock.method(globalThis, 'fetch', async (url, { headers }) => {
    assert.equal(headers.Authorization, 'Bearer replacement-token');
    switch (url.pathname) {
      case '/repos/owner/brain/contents/repos.yaml':
        registryReads++;
        return Response.json(yamlFile('repos: { site: { remote: "https://github.com/owner/web", tasks: true } }'));
      case '/repos/owner/brain/contents/workspace.yaml': return Response.json(yamlFile('github: owner'));
      case '/repos/owner/brain/issues': return Response.json([]);
      case '/repos/owner/web/issues': return Response.json([{ ...rawIssue, state: 'closed', state_reason: 'completed' }]);
      default: assert.fail(`Unexpected request: ${url.pathname}`);
    }
  });
  const client = new GitHub('owner/brain', 'replacement-token');
  const [search, completed] = await Promise.all([client.list('search'), client.list('completed')]);
  assert.equal(registryReads, 1);
  assert.deepEqual(search.issues.map(issue => issue.ref), ['site#42']);
  assert.deepEqual(completed.issues.map(issue => issue.ref), ['site#42']);
});
