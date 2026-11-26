export const OPEN = ['input-needed', 'ready', 'on-hold', 'backlog'];
export const MOVE_TO = ['ready', 'on-hold', 'backlog', 'done', 'dropped'];
const LEGACY = new Map([['in-review', 'input-needed']]);
const MARK = { card: '<!-- card -->', answer: '<!-- answer -->', question: '<!-- question -->' };
const lines = text => text.split(/\r\n|\n|\r/);
const compare = (a, b) => a < b ? -1 : a > b ? 1 : 0;

export function issueModel({ number, title = '', body = '', labels = [], state = 'open',
  updatedAt = '', waitingSince = '', url = '', claim = '', stateReason = '', repo = '', repoAlias = '', assignees = [] }) {
  const taskRepoName = repoAlias || repo.split('/').at(-1);
  const workstreams = labels.filter(l => l.startsWith('ws:')).map(l => l.slice(3));
  if (repo && !workstreams.includes(taskRepoName.toLowerCase())) workstreams.push(taskRepoName.toLowerCase());
  const status = state === 'closed' ? (stateReason === 'not_planned' ? 'dropped' : 'done')
    : OPEN.find(s => labels.some(l => (LEGACY.get(l) ?? l) === s)) || 'ready';
  const priorityLabel = ['p1', 'p2', 'p3'].find(label => labels.includes(label));
  return { number, title, body, labels, state, updatedAt, waitingSince, url, claim, stateReason, repo, repoAlias, assignees,
    taskRepoName, workstreams, status, key: repo ? `${repo}#${number}` : String(number),
    ref: `${repo ? taskRepoName : ''}#${number}`, inProgress: state === 'open' && Boolean(claim),
    waitingOn: field(body, 'Waiting on'), checkAfter: field(body, 'Check after'),
    priorityLabel, priority: priorityLabel ? Number(priorityLabel[1]) : 2,
    tags: labels.filter(l => !OPEN.includes(l) && !LEGACY.has(l) && !/^p[123]$/.test(l)) };
}

export function field(body, name) {
  return body.match(new RegExp(`^${name}:\\s*(.+?)\\s*$`, 'm'))?.[1] ?? '';
}

export function commentKind(body) {
  return Object.keys(MARK).find(k => body.trimStart().startsWith(MARK[k])) ?? 'log';
}

export function commentText(body) {
  return body.trimStart().slice(MARK[commentKind(body)]?.length ?? 0).trim();
}

export function parseCard(text) {
  let question = '', options = [], recommend = '';
  const rest = [];
  for (const line of lines(text.replace(/^<!-- card -->/, '').trim())) {
    const t = line.trim();
    if (!question && t.startsWith('### ')) question = t.slice(4).trim();
    else if (!options.length && t.startsWith('Options:')) options = t.slice(8).split('|').map(v => v.trim()).filter(Boolean);
    else if (!recommend && t.startsWith('Recommend:')) recommend = t.slice(10).trim();
    else rest.push(line);
  }
  return { question, options, recommend, rest: rest.join('\n').trim() };
}

export function currentCard(issue, comments) {
  if (issue.status !== 'input-needed') return null;
  for (let i = comments.length - 1; i >= 0; i--) {
    const { body } = comments[i];
    const kind = commentKind(body);
    if (kind === 'card') return parseCard(commentText(body));
    if (kind !== 'log') break;
  }
  return { question: issue.waitingOn.trim() ? issue.waitingOn : issue.title, options: [], recommend: '', rest: '' };
}

export function stripStatusLines(body) {
  return body.replace(/^(Waiting on|Check after):[^\r\n]*\r?\n?/gm, '').replace(/^\n+/, '');
}

export function statusChange(issue, status, waitingOn, checkAfter) {
  const stripped = stripStatusLines(issue.body);
  const body = waitingOn?.trim()
    ? `Waiting on: ${waitingOn.trim()}\n${checkAfter?.trim() ? `Check after: ${checkAfter.trim()}\n` : ''}\n${stripped}` : stripped;
  const labels = issue.labels.filter(l => !OPEN.includes(l) && !LEGACY.has(l));
  if (OPEN.includes(status)) labels.push(status);
  const patch = { body, labels: labels.sort() };
  if (status === 'done' || status === 'dropped') {
    if (issue.state === 'open') Object.assign(patch, { state: 'closed', state_reason: status === 'done' ? 'completed' : 'not_planned' });
  } else if (issue.state === 'closed') patch.state = 'open';
  return patch;
}

const quote = text => lines(text).map(line => `> ${line}`).join('\n');
export const answerComment = (question, choice, note) =>
  `${MARK.answer}\n${quote(question)}\n\n${choice?.trim() ? `**${choice}**\n\n` : ''}${note.trim()}`.trim();
export const questionComment = (question, text) => `${MARK.question}\n${quote(question)}\n\n${text.trim()}`;

export function taskReposOf(yaml) {
  return new Map(Object.entries(yaml?.repos ?? {}).sort(([a], [b]) => compare(a, b)).flatMap(([name, entry]) => {
    const match = typeof entry?.remote === 'string' && entry.remote.match(/github\.com[:/]([^/]+\/[^/]+?)(?:\.git)?\/?$/);
    return match && (entry.tasks === true || entry.tasks === 'assigned') ? [[name, match[1]]] : [];
  }));
}

export const ownerOf = yaml => typeof yaml?.github === 'string' ? yaml.github.trim() : '';
export const belongsTo = (issue, me) => !issue.assignees.length || !me
  || issue.assignees.some(name => name.toLowerCase() === me.toLowerCase());

export function matches(issue, filter) {
  const only = [...filter].filter(term => !term.startsWith('-'));
  return (!only.length || issue.workstreams.some(ws => only.includes(ws)))
    && !issue.workstreams.some(ws => filter.has(`-${ws}`));
}

export const workstreamsOf = (issues, filter) =>
  [...new Set([...issues.flatMap(i => i.workstreams), ...[...filter].map(term => term.replace(/^-/, ''))])].sort();

export const pickOf = (filter, ws) => filter.has(ws) ? 'only' : filter.has(`-${ws}`) ? 'except' : 'off';

export function cycle(filter, ws) {
  const next = new Set(filter);
  if (next.delete(ws)) next.add(`-${ws}`);
  else if (!next.delete(`-${ws}`)) next.add(ws);
  return next;
}

export function describeFilter(filter) {
  const only = [...filter].filter(term => !term.startsWith('-')).sort().join(', ');
  const hidden = [...filter].filter(term => term.startsWith('-')).map(t => t.slice(1)).sort().join(', ');
  return only ? (hidden ? `${only}, not ${hidden}` : only) : (hidden ? `All except ${hidden}` : 'All');
}

export function dispatcherFilter(yaml) {
  const match = yaml.match(/^filter:[ \t]*\[([^\]\n]*)\][ \t]*(#.*)?$/m);
  if (!match) return null;
  const terms = match[1].split(/[,\s]+/).map(t => t.replace(/^["']+|["']+$/g, '').toLowerCase()).filter(Boolean);
  return new Set(terms.length === 1 && terms[0] === 'all' ? [] : terms);
}

export const visibleIssues = (issues, filter, me, showEveryone) =>
  issues.filter(issue => matches(issue, filter) && (showEveryone || belongsTo(issue, me)));

export function grouped(issues, me = '') {
  const section = i => i.status === 'input-needed' && !belongsTo(i, me) ? 'on-hold' : i.status;
  const decisions = (a, b) => (a.priorityLabel ? a.priority : 4) - (b.priorityLabel ? b.priority : 4)
    || compare(a.waitingSince || a.updatedAt || '\uffff', b.waitingSince || b.updatedAt || '\uffff')
    || a.number - b.number || compare(a.key, b.key);
  const recent = (a, b) =>
    compare(b.updatedAt, a.updatedAt) || Number(b.inProgress) - Number(a.inProgress)
    || a.priority - b.priority || a.number - b.number;
  return OPEN.map(status => [status, issues.filter(i => section(i) === status)
    .sort(status === 'input-needed' ? decisions : recent)]);
}

export function searchIssues(issues, query, homeRepo = '') {
  const text = query.trim().toLowerCase();
  const qualified = text.match(/^([a-z0-9._-]+(?:\/[a-z0-9._-]+)?)#(\d+)$/);
  const digits = text.match(/^#?(\d+)$/)?.[1];
  const rank = issue => {
    if (!text) return 0;
    if (qualified) {
      const names = issue.repo ? [issue.taskRepoName, issue.repo] : [homeRepo];
      return issue.number === Number(qualified[2]) && names.some(s => s.toLowerCase() === qualified[1]) ? 0 : 4;
    }
    if (digits && String(issue.number) === digits) return 0;
    if (digits && String(issue.number).includes(digits)) return 1;
    if (!(digits && text.startsWith('#'))) {
      if (issue.title.toLowerCase().includes(text)) return 2;
      if (issue.body.toLowerCase().includes(text)) return 3;
    }
    return 4;
  };
  const seen = new Set();
  return issues.filter(i => !seen.has(i.key) && seen.add(i.key)).map(i => [i, rank(i)])
    .filter(([, r]) => r < 4).sort(([a, ar], [b, br]) => ar - br
      || compare(b.updatedAt, a.updatedAt) || b.number - a.number).map(([i]) => i);
}

export function claimHolder(message, ageHours) {
  return ageHours > 2 ? '' : message.match(/^claim #\d+ by (\S+)/)?.[1] ?? '';
}

export function apiImageUrl(url) {
  const match = url.match(/^https:\/\/github\.com\/([^/]+)\/([^/]+)\/raw\/([^/]+)\/(.+)$/)
    ?? url.match(/^https:\/\/raw\.githubusercontent\.com\/([^/]+)\/([^/]+)\/([^/]+)\/(.+)$/);
  if (!match) return null;
  const [, owner, repo, ref, path] = match;
  const api = new URL(`https://api.github.com/repos/${owner}/${repo}/contents/${path}`);
  if (!api.pathname.startsWith(`/repos/${owner}/${repo}/contents/`)) return null;
  api.search = `ref=${encodeURIComponent(ref)}`;
  return api.href;
}
