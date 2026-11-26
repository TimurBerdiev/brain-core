import { issueModel, statusChange } from './model.js';

const history = [
  { number: 29, title: 'Check how the Boss link opens from Messages', labels: ['on-hold', 'ws:boss'],
    body: 'Waiting on: iPhone test device\n\nRepeat the first-run test from a shared link.' },
  { number: 18, title: 'Explore a faster Boss task handoff', labels: ['backlog', 'ws:boss'],
    body: 'Look for repeated steps when moving a task between reviewers.' },
  { number: 7, title: 'Publish the Boss web app shell', state: 'closed', stateReason: 'completed' },
  { number: 4, title: 'Research a separate Boss demo app', state: 'closed', stateReason: 'not_planned' },
];

export class Demo {
  constructor() {
    this.home = 'demo/boss';
    this.issues = new Map();
    this.comments = new Map();
  }

  async load() {
    const response = await fetch(new URL('./dogfood.json', import.meta.url));
    if (!response.ok) throw new Error('Could not load the Boss sample tasks.');
    const catalog = await response.json();
    const samples = catalog.map((item, index) => {
      const number = index + 41;
      const card = item.card;
      const sections = [['Goal', item.goal], ['Done when', item.done], ['Plan', item.plan], ['Next', item.next]];
      const body = [...(card ? [`Waiting on: ${card[0].slice(4)}`] : []),
        ...sections.map(([title, text]) => `## ${title}\n\n${text}`)].join('\n\n');
      if (card) this.comments.set(String(number), [{ author: 'boss-worker', body: ['<!-- card -->', ...card].join('\n') }]);
      return { number, title: item.title, body, assignees: [item.owner],
        labels: [card ? 'input-needed' : 'ready', item.priority, 'ws:boss'] };
    });
    samples.push(...history, { number: 38, title: 'Check the Boss preview build', labels: ['ready'],
      repo: 'demo/checks', repoAlias: 'checks', claim: 'agent:web', body: 'Check the preview on a phone-sized screen.' });
    for (const sample of samples) {
      const issue = issueModel({ updatedAt: '2026-11-25T10:00:00Z', ...sample });
      this.issues.set(issue.key, issue);
    }
  }

  async list(kind) {
    this.loading ??= this.load().catch(error => { this.loading = null; throw error; });
    await this.loading;
    return { issues: [...this.issues.values()].filter(i => kind === 'search'
      || (kind === 'completed' ? i.stateReason === 'completed' : i.state === 'open')),
    owner: 'timur', warnings: [] };
  }

  async detail(issue) {
    return { issue: this.issues.get(issue.key), comments: this.comments.get(issue.key) ?? [] };
  }

  async move(issue, status, waitingOn, checkAfter) {
    const fresh = this.issues.get(issue.key);
    const patch = statusChange(fresh, status, waitingOn, checkAfter);
    const updated = issueModel({ ...fresh, ...patch, stateReason: patch.state_reason ?? fresh.stateReason,
      updatedAt: new Date().toISOString() });
    this.issues.set(issue.key, updated);
    return updated;
  }

  async reply(issue, body) {
    this.comments.set(issue.key, [...(this.comments.get(issue.key) ?? []), { author: 'timur', body }]);
    return this.move(issue, 'ready');
  }

  async image() {
    throw new Error('Sample mode does not load remote images.');
  }
}
