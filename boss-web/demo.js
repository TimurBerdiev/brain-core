import { issueModel, statusChange } from './model.js';

const samples = [
  { number: 42, title: 'Choose the launch checklist', labels: ['input-needed', 'ws:launch'],
    body: 'Waiting on: Pick the launch checklist\n\nThe launch is ready for a final handoff.' },
  { number: 38, title: 'Update the landing page copy', labels: ['ready'], repo: 'demo/site', repoAlias: 'site',
    claim: 'agent:web', body: 'Revise the homepage copy and send it for review.' },
  { number: 29, title: 'Confirm the vendor timeline', labels: ['on-hold', 'ws:ops'],
    body: 'Waiting on: Vendor reply\nCheck after: 2026-11-27\n\nFollow up when the new estimate arrives.' },
  { number: 18, title: 'Draft the onboarding guide', labels: ['backlog', 'ws:launch'],
    body: 'Write a short guide for the next teammate.' },
  { number: 12, title: 'Review the analytics plan', labels: ['input-needed', 'ws:ops'], assignees: ['alex'] },
  { number: 7, title: 'Set up the project workspace', state: 'closed', stateReason: 'completed' },
  { number: 4, title: 'Retire the old launch notes', state: 'closed', stateReason: 'not_planned' },
];

export class Demo {
  constructor() {
    this.home = 'demo/brain';
    this.issues = new Map(samples.map(sample => {
      const issue = issueModel({ updatedAt: '2026-11-25T10:00:00Z', ...sample });
      return [issue.key, issue];
    }));
    this.comments = new Map([['42', [{ author: 'release-agent', body: [
      '<!-- card -->', '### Which checklist should we use for launch?', '',
      'Options: Short checklist | Full checklist', 'Recommend: Short checklist', '',
      'The **short checklist** covers all required release checks. The full checklist adds a second review.',
    ].join('\n') }]]]);
  }

  async list(kind) {
    return { issues: [...this.issues.values()].filter(i => kind === 'search'
      || (kind === 'completed' ? i.stateReason === 'completed' : i.state === 'open')),
    owner: 'demo', warnings: [] };
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
    this.comments.set(issue.key, [...(this.comments.get(issue.key) ?? []), { author: 'demo', body }]);
    return this.move(issue, 'ready');
  }

  async image() {
    throw new Error('Sample mode does not load remote images.');
  }
}
