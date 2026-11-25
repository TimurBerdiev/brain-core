import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import { test } from 'node:test';
import { answerComment, apiImageUrl, claimHolder, commentText, currentCard, cycle, describeFilter,
  dispatcherFilter, grouped, issueModel, matches, ownerOf, parseCard, questionComment,
  searchIssues, statusChange, taskReposOf, visibleIssues } from '../model.js';

const dir = new URL('../../tests/fixtures/', import.meta.url);
const fixtures = {};
const base = values => issueModel({ number: 7, title: 'Task', ...values });
for (const file of readdirSync(dir).filter(name => name.endsWith('.json'))) {
  fixtures[file] = JSON.parse(readFileSync(new URL(file, dir)));
}

test('all shared fixture files are covered', () => {
  assert.deepEqual(Object.keys(fixtures).sort(), ['cards.json', 'claims.json', 'filter.json', 'status.json', 'transitions.json']);
});

for (const c of fixtures['cards.json'].cases) test(`card: ${c.name}`, () => {
  const { question, options, recommend } = parseCard(commentText(`<!-- card -->\n${c.card.join('\n')}`));
  assert.deepEqual({ question, options, recommend }, { question: c.question, options: c.options, recommend: c.recommend });
});

for (const c of fixtures['status.json'].cases) test(`status: ${c.labels}`, () => {
  assert.equal(base({ labels: c.labels }).status, c.status);
});

for (const c of fixtures['status.json'].fields) test(`fields: ${c.body}`, () => {
  const issue = base({ body: c.body });
  assert.equal(issue.waitingOn, c.waiting_on);
  assert.equal(issue.checkAfter, c.check_after);
});

for (const c of fixtures['transitions.json'].cases) test(`transition: ${c.name}`, () => {
  const before = base({ ...c.before, stateReason: c.before.state_reason });
  const change = statusChange(before, c.to, c.waiting_on, c.check_after);
  const after = base({ ...before, ...change, stateReason: change.state_reason ?? before.stateReason });
  assert.equal(after.body, c.after.body);
  assert.deepEqual(after.labels, c.after.labels);
  assert.equal(after.state, c.after.state);
  if (after.state === 'closed') assert.equal(after.stateReason, c.after.state_reason);
  assert.equal(after.status, c.to);
});

for (const c of fixtures['claims.json'].cases) test(`claim: ${c.subject} (${c.age_hours}h)`, () => {
  assert.equal(claimHolder(c.subject, c.age_hours), c.holder);
});

for (const c of fixtures['filter.json'].lines) test(`filter syntax: ${c.yaml}`, () => {
  assert.deepEqual(dispatcherFilter(c.yaml), c.terms === null ? null : new Set(c.terms));
});

for (const c of fixtures['filter.json'].matches) test(`filter: ${JSON.stringify(c)}`, () => {
  assert.equal(matches(base({ labels: c.labels, repo: c.repo ? `owner/${c.repo}` : '', repoAlias: c.repo ?? '' }),
    new Set(c.filter)), c.keep);
});

for (const c of fixtures['filter.json'].describe) test(`describe filter: ${c.filter}`, () => {
  assert.equal(describeFilter(new Set(c.filter)), c.text);
});

test('latest unanswered card, fallback, and outbound comment format', () => {
  const issue = base({ labels: ['input-needed'], body: 'Waiting on: A quick question' });
  const comments = [{ body: '<!-- card -->\n### Launch?\nOptions: Today | Tomorrow' }, { body: 'Still waiting.' }];
  assert.deepEqual(currentCard(issue, comments).options, ['Today', 'Tomorrow']);
  const question = questionComment('Launch?', '  What time? ');
  assert.equal(question, '<!-- question -->\n> Launch?\n\nWhat time?');
  assert.equal(currentCard(issue, [...comments, { body: question }]).question, 'A quick question');
  assert.equal(answerComment('Launch?\nToday?', 'Today', ' OK '), '<!-- answer -->\n> Launch?\n> Today?\n\n**Today**\n\nOK');
});

test('assignee and workstream filters are for the queue, not search', () => {
  const home = base({ labels: ['input-needed', 'ws:ops'] });
  const other = base({ labels: ['input-needed'], assignees: ['Alex'], repo: 'team/website', repoAlias: 'site' });
  const issues = [home, other];
  assert.deepEqual(visibleIssues(issues, new Set(), 'me'), [home]);
  assert.deepEqual(grouped(issues, 'me').find(([status]) => status === 'on-hold')[1], [other]);
  const only = cycle(new Set(), 'site');
  assert.deepEqual(visibleIssues(issues, only, 'me', true), [other]);
  assert.deepEqual(visibleIssues(issues, cycle(only, 'site'), 'me', true), [home]);
  assert.deepEqual(searchIssues(issues, 'site#7'), [other]);
  assert.deepEqual(searchIssues(issues, 'TEAM/website#7'), [other]);
});

test('reads task repo aliases and maps private card images', () => {
  assert.deepEqual(taskReposOf({ repos: {
    site: { tasks: 'assigned', remote: 'git@github.com:team/website.git' },
    misc: { tasks: false, remote: 'https://github.com/team/misc' },
  } }), new Map([['site', 'team/website']]));
  assert.equal(ownerOf({ github: ' me ' }), 'me');
  assert.equal(apiImageUrl('https://github.com/alex/brain/raw/cards/36/pop.png'),
    'https://api.github.com/repos/alex/brain/contents/36/pop.png?ref=cards');
});
