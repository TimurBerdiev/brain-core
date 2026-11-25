import { Demo } from './demo.js';
import { GitHub, validRepo } from './github.js';
import { externalLink, markdown } from './markdown.js';
import { answerComment, belongsTo, commentKind, commentText, currentCard, cycle, describeFilter, grouped,
  MOVE_TO, pickOf, questionComment, searchIssues, stripStatusLines, visibleIssues, workstreamsOf } from './model.js';

const root = document.querySelector('#app');
const demo = new URLSearchParams(location.search).has('demo');
const key = 'boss-web.settings';
const prefsKey = demo ? `${key}.demo` : key;
const settings = (() => {
  try { return JSON.parse(localStorage.getItem(prefsKey)) ?? {}; } catch { return {}; }
})();
const names = { 'input-needed': 'For you', ready: 'Agents', 'on-hold': 'On hold', backlog: 'Backlog',
  done: 'Completed', dropped: 'Dropped' };
const moveNames = { ready: 'Ready', 'on-hold': 'On hold', backlog: 'Backlog', done: 'Done', dropped: 'Dropped' };
let backend = demo ? new Demo() : (validRepo(settings.repo ?? '') && settings.token ? new GitHub(settings.repo, settings.token) : null);
let view, generation = 0, searchQuery = '', completedOpen = false;
let filter = new Set(settings.filter ?? []), showEveryone = Boolean(settings.showEveryone);
let owner = '', toast = '';
const caches = { queue: null, completed: null, search: null };
const known = new Map();
const blobs = new Set();

function el(tag, props = {}, ...children) {
  const node = document.createElement(tag);
  for (const [name, value] of Object.entries(props)) {
    if (name === 'on') for (const [event, handler] of Object.entries(value)) node.addEventListener(event, handler);
    else if (value !== null && value !== undefined) node.setAttribute(name, value);
  }
  node.append(...children.flat(Infinity).filter(child => child !== null && child !== undefined));
  return node;
}

const button = (label, action, attrs = {}) => el('button', { type: 'button', on: { click: action }, ...attrs }, label);
const alert = () => el('p', { class: 'notice error', role: 'alert', hidden: '' });
function showError(node, text) { node.textContent = text; node.hidden = !text; }
const status = text => el('p', { class: 'notice', role: 'status' }, text);
const tokenUrl = 'https://github.com/settings/personal-access-tokens/new';
const caption = text => el('p', { class: 'muted' }, text);
const panel = (...children) => el('section', { class: 'panel' }, ...children);
const formField = (text, input) => el('label', { class: 'field' }, el('span', {}, text), input);
const primary = (text, props = {}) => el('button', { type: 'submit', class: 'primary', ...props }, text);

function collapsed(title, content) {
  const details = el('details', { class: 'panel' }, el('summary', {}, title));
  function expand() {
    if (!details.open || !details.isConnected) return;
    details.removeEventListener('toggle', expand);
    details.append(...content().filter(Boolean));
  }
  details.addEventListener('toggle', expand);
  return details;
}

function saveSettings() {
  settings.filter = [...filter];
  settings.showEveryone = showEveryone;
  localStorage.setItem(prefsKey, JSON.stringify(settings));
}

function resetWorkspace() {
  known.clear();
  for (const name of Object.keys(caches)) caches[name] = null;
  owner = '';
  searchQuery = '';
  completedOpen = false;
  filter = new Set();
  showEveryone = false;
}

function navigate(page, extra = {}, replace = false) {
  const next = { page, ...extra };
  history[replace ? 'replaceState' : 'pushState'](next, '');
  render(next);
}

function back() {
  if (view.page === 'detail' || (backend && ['settings', 'search'].includes(view.page))) history.back();
  else navigate(backend ? 'queue' : 'settings', {}, true);
}

window.addEventListener('popstate', event => render(backend && event.state ? event.state : {
  page: backend ? 'queue' : 'settings',
}));

function heading(title, kicker, actions = []) {
  return el('header', { class: 'top' }, el('div', {}, el('div', { class: 'eyebrow' }, kicker), el('h1', {}, title)),
    el('div', { class: 'actions' }, actions));
}

function badge(text, kind) {
  return el('span', { class: `badge ${kind ?? ''}` }, text);
}

function remember(issues) {
  for (const issue of issues) known.set(issue.key, issue);
}

async function load(kind) {
  const current = backend;
  const data = await current.list(kind, caches[kind]?.issues);
  if (backend !== current) return;
  owner = data.owner;
  caches[kind] = data;
  remember(data.issues);
  return data;
}

function warnings(list) {
  return list?.length ? el('details', { class: 'notice' }, el('summary', {}, 'Some workspace data is unavailable'),
    el('ul', {}, list.map(message => el('li', {}, message)))) : null;
}

function render(next) {
  view = next;
  const renderId = ++generation;
  for (const url of blobs) URL.revokeObjectURL(url);
  blobs.clear();
  const main = el('main');
  root.replaceChildren(main);
  window.scrollTo(0, 0);
  if (!backend && next.page !== 'settings') return navigate('settings', {}, true);
  if (next.page === 'settings') renderSettings(main, renderId);
  else if (next.page === 'search') renderSearch(main, renderId);
  else if (next.page === 'detail') renderDetail(main, renderId, next);
  else renderQueue(main, renderId);
}

function renderSettings(main, id) {
  const repo = el('input', { id: 'repo', name: 'repo', value: settings.repo ?? '', required: '', autocapitalize: 'none',
    spellcheck: 'false', placeholder: 'owner/name', autocomplete: 'off', maxlength: 160 });
  const token = el('input', { id: 'token', name: 'token', type: 'password', required: '', value: settings.token ?? '',
    autocapitalize: 'none', spellcheck: 'false', autocomplete: 'off', placeholder: 'Paste your token' });
  const error = alert();
  const submit = primary('Connect to GitHub');
  const form = el('form', { class: 'stack', on: { submit: async event => {
    event.preventDefault();
    submit.disabled = true;
    showError(error, '');
    try {
      const next = new GitHub(repo.value.trim(), token.value.trim());
      await next.check();
      if (id !== generation) return;
      if (settings.repo?.toLowerCase() !== next.home.toLowerCase()) resetWorkspace();
      settings.repo = next.home;
      settings.token = token.value.trim();
      saveSettings();
      backend = next;
      if (view.fromQueue) history.back();
      else navigate('queue', {}, true);
    } catch (e) {
      showError(error, `${e.message} Check the repository name and that your token grants Issues and Contents access.`);
    } finally { submit.disabled = false; }
  } } }, formField('Brain repository', repo), formField('Fine-grained personal access token', token), error, submit);
  main.append(heading(backend ? 'Settings' : 'Boss', 'Brain Core', backend ? [button('Back', back)] : []));
  if (demo) {
    main.append(panel(el('h2', {}, 'Sample workspace'), caption('Demo mode does not connect to GitHub.'),
      el('a', { class: 'button', href: './' }, 'Connect a real workspace')));
    return;
  }
  main.append(panel(el('h2', {}, 'Connect your Brain'), form,
    el('p', { class: 'muted' }, externalLink(document, 'Create a fine-grained GitHub token', tokenUrl),
      ' for your Brain and all its task repositories. Grant ', el('strong', {}, 'Issues: Read and write'),
      ' and ', el('strong', {}, 'Contents: Read-only'), '. The token stays in this browser and is sent only to GitHub.')),
  panel(el('h2', {}, 'Use from your home screen'),
    caption('In Safari, tap Share → Add to Home Screen. Open Boss from the new icon and connect there if asked.'),
    el('a', { class: 'button', href: '?demo' }, 'Try with sample tasks')));
  if (backend) main.append(button('Forget this workspace', () => {
    localStorage.removeItem(prefsKey);
    delete settings.repo;
    delete settings.token;
    backend = null;
    resetWorkspace();
    navigate('settings', {}, true);
  }, { class: 'danger' }));
}

function taskList(issues) {
  return el('ul', { class: 'task-list' }, issues.map(issue => {
    const meta = [issue.ref];
    if (issue.inProgress) meta.push(`In progress · ${issue.claim}`);
    else if (!belongsTo(issue, owner)) meta.push(issue.assignees.map(name => `@${name}`).join(', '));
    else if (issue.waitingOn) meta.push(issue.waitingOn);
    return el('li', {}, button([
      el('span', { class: 'task-title' }, issue.title), el('span', { class: 'task-meta' }, meta.join(' · ')),
    ], () => navigate('detail', { issue: issue.key }), { class: 'task-row' }));
  }));
}

function renderQueue(main, id) {
  const refresh = button('Refresh', () => { reload(caches.completed && completedOpen); });
  const search = button('Search', () => { searchQuery = ''; navigate('search'); });
  const setup = button('Settings', () => navigate('settings', { fromQueue: true }));
  const content = el('div', { class: 'stack' });
  const notices = el('div');
  const error = alert();
  const done = toast;
  toast = '';
  main.append(heading('Tasks', demo ? 'Boss · Sample workspace' : `Boss · ${backend.home}`, [search, setup]),
    ...(done ? [status(done)] : []), error, notices, content);

  function draw() {
    if (id !== generation) return;
    const queue = caches.queue;
    const toolbar = el('div', { class: 'toolbar' }, el('strong', {}, 'Workstreams'), refresh);
    const chips = el('div', { class: 'chips' },
      button('All', () => { filter = new Set(); saveSettings(); draw(); }, { 'aria-pressed': String(!filter.size) }),
      workstreamsOf(queue?.issues ?? [], filter).map(ws => {
        const pick = pickOf(filter, ws);
        return button(pick === 'off' ? ws : `${pick === 'only' ? '✓' : '✗'} ${ws}`, () => {
          filter = cycle(filter, ws); saveSettings(); draw();
        }, { class: pick, 'aria-pressed': String(pick !== 'off'), 'aria-label': `${ws}: ${pick}` });
      }));
    const check = el('input', { type: 'checkbox', checked: showEveryone ? '' : null, on: { change: event => {
      showEveryone = event.target.checked; saveSettings(); draw();
    } } });
    const options = panel(toolbar, chips, caption(describeFilter(filter)),
      el('label', { class: 'toggle' }, check, "Show everyone's tasks"));
    const sections = grouped(visibleIssues(queue?.issues ?? [], filter, owner, showEveryone), owner).map(([name, issues]) =>
      issues.length ? el('section', { class: `group ${name}` }, el('h2', {}, names[name], badge(String(issues.length), name)),
        taskList(issues)) : null).filter(Boolean);
    const completed = caches.completed;
    const history = el('details', { class: 'panel history', open: completedOpen ? '' : null }, el('summary', {}, 'Completed'));
    if (completed) {
      const items = visibleIssues(completed.issues, filter, owner, showEveryone).sort((a, b) =>
        b.updatedAt.localeCompare(a.updatedAt));
      history.append(warnings(completed.warnings) ?? '', items.length ? taskList(items) : caption('No matching completed tasks.'));
    } else history.append(caption('Loading completed tasks…'));
    history.addEventListener('toggle', () => {
      if (!history.isConnected || id !== generation) return;
      completedOpen = history.open;
      if (completedOpen && !caches.completed) refreshCompleted();
    });
    notices.replaceChildren(warnings(queue?.warnings) ?? '');
    content.replaceChildren(options, ...sections,
      queue && !sections.length ? caption('No open tasks match these filters.') : '',
      !queue && refresh.disabled ? status('Loading tasks…') : '', history);
  }

  let pendingCompleted;
  async function refreshCompleted(force = false) {
    if (pendingCompleted || (!force && caches.completed)) return;
    pendingCompleted = true;
    try { await load('completed'); draw(); }
    catch (e) { if (id === generation) showError(error, e.message); }
    finally { pendingCompleted = false; }
  }

  async function reload(completed) {
    refresh.disabled = true;
    showError(error, '');
    draw();
    try {
      await load('queue');
      if (id === generation && completed) await refreshCompleted(true);
    } catch (e) {
      if (id === generation) showError(error, e.message);
    } finally {
      refresh.disabled = false;
      draw();
    }
  }

  if (!caches.queue) reload(false);
  else draw();
}

function renderSearch(main, id) {
  const field = el('input', { type: 'search', id: 'task-search', value: searchQuery, placeholder: '#42, site#38, or keywords',
    on: { input: event => { searchQuery = event.target.value; draw(); } } });
  const results = el('div');
  const error = alert();
  const refresh = button('Refresh', reload);
  const done = toast;
  toast = '';
  main.append(heading('Search', 'Boss · Tasks', [button('Back', back), refresh]),
    ...(done ? [status(done)] : []), formField('Search all tasks', field), error, results);

  function draw() {
    if (id !== generation) return;
    const data = caches.search;
    const hits = searchIssues(data?.issues ?? [...known.values()], searchQuery);
    results.replaceChildren(warnings(data?.warnings) ?? '', hits.length ? taskList(hits)
      : caption(refresh.disabled ? 'Loading task history…' : 'No matching tasks.'));
  }

  async function reload() {
    refresh.disabled = true;
    showError(error, '');
    draw();
    try { await load('search'); }
    catch (e) { if (id === generation) showError(error, e.message); }
    finally { refresh.disabled = false; draw(); }
  }
  if (!caches.search) reload();
  else draw();
}

function rich(text, id) {
  return markdown(document, text, async (url, image) => {
    const src = await backend.image(url);
    if (id !== generation) {
      if (src.startsWith('blob:')) URL.revokeObjectURL(src);
      return;
    }
    if (src.startsWith('blob:')) blobs.add(src);
    image.src = src;
  });
}

async function renderDetail(main, id, next) {
  const initial = known.get(next.issue);
  if (!initial) { navigate('queue', {}, true); return; }
  const details = el('div', { class: 'stack' });
  const error = alert();
  const move = button('Move', () => openMove());
  const reload = button('Reload', loadDetail);
  const controls = [button('Back', back), move];
  let issue = initial, comments = [], busy = false, loaded = false, replyPosted = false;
  main.append(heading(initial.title, `Boss · ${initial.ref}`, controls), error, details);

  function draw() {
    const card = loaded && !replyPosted ? currentCard(issue, comments) : null;
    const meta = el('div', { class: 'chips' }, badge(moveNames[issue.status] ?? 'Input needed', issue.status),
      issue.inProgress ? badge(`In progress · ${issue.claim}`, 'ready') : null,
      issue.tags.map(tag => badge(tag)));
    const body = collapsed('Task details', () => [
      issue.waitingOn ? el('p', {}, `Waiting on: ${issue.waitingOn}`) : null,
      issue.checkAfter ? el('p', {}, `Check after: ${issue.checkAfter}`) : null,
      rich(stripStatusLines(issue.body), id),
      issue.url ? externalLink(document, 'Open on GitHub', issue.url) : null,
    ]);
    const log = collapsed(`Log (${comments.length})`, () =>
      comments.map(c => el('article', { class: 'log-entry' }, el('strong', {}, c.author || 'GitHub user'),
        commentKind(c.body) !== 'log' ? badge(commentKind(c.body)) : null, rich(commentText(c.body), id))));
    details.replaceChildren(meta, card ? cardPanel(card) : '', body, loaded ? log : reload);
  }

  async function loadDetail() {
    loaded = false;
    reload.disabled = true;
    reload.textContent = 'Loading detail…';
    showError(error, '');
    draw();
    try {
      const detail = await backend.detail(initial);
      if (id !== generation) return;
      issue = detail.issue;
      comments = detail.comments;
      loaded = true;
      remember([issue]);
    } catch (e) { if (id === generation) showError(error, e.message); }
    finally {
      if (id === generation) { reload.disabled = false; reload.textContent = 'Reload'; draw(); }
    }
  }

  async function mutate(action, message, controls, onFailure = () => {}) {
    if (busy) return;
    busy = true;
    [move, ...controls].forEach(control => { control.disabled = true; });
    showError(error, '');
    try {
      const changed = await action();
      if (id !== generation) return;
      remember([changed]);
      for (const kind of Object.keys(caches)) caches[kind] = null;
      toast = message;
      back();
    } catch (e) {
      if (id !== generation) return;
      if (e.replyPosted) { replyPosted = true; draw(); }
      onFailure(e);
      showError(error, e.message);
    } finally {
      busy = false;
      [move, ...controls].forEach(control => { control.disabled = false; });
    }
  }

  function cardPanel(card) {
    const choices = card.options.length ? card.options : ['Yes', 'No'];
    const note = el('textarea', { rows: 3, placeholder: 'Add a note' });
    const send = primary('Send answer', { disabled: '' });
    const question = el('textarea', { rows: 3, required: '', placeholder: 'Write your question' });
    const ask = primary('Send question');
    let choice = null;
    const options = choices.map(option => button(option, event => {
      choice = choice === option ? null : option;
      for (const item of options) item.setAttribute('aria-pressed', String(item === event.target && Boolean(choice)));
      update();
    }, { 'aria-pressed': 'false' }));
    const update = () => { send.disabled = !(choice || note.value.trim()); };
    note.addEventListener('input', update);
    const answer = el('form', { class: 'stack', on: { submit: event => {
      event.preventDefault();
      if (!send.disabled) mutate(() => backend.reply(issue, answerComment(card.question, choice, note.value)),
        'Answer sent. Moved to Ready.', [send, ask, ...options]);
    } } }, el('div', { class: 'chips choices' }, options), formField('Your answer', note), send);
    const askForm = el('form', { class: 'stack', on: { submit: event => {
      event.preventDefault();
      if (question.value.trim()) mutate(() => backend.reply(issue, questionComment(card.question, question.value)),
        'Question sent. Moved to Ready.', [ask, ...options]);
    } } }, formField('Your question', question), ask);
    return el('section', { class: 'panel decision' }, el('div', { class: 'eyebrow' }, 'Decision card'),
      el('h2', {}, card.question), card.recommend ? el('p', { class: 'recommend' }, el('strong', {}, 'Recommendation: '), card.recommend) : null,
      rich(card.rest, id), answer, el('details', {}, el('summary', {}, 'Ask instead'), askForm));
  }

  function openMove() {
    const select = el('select', {}, MOVE_TO.map(value => el('option', { value }, moveNames[value])));
    select.value = MOVE_TO.find(value => value !== issue.status);
    const waiting = el('input', { value: issue.status === 'on-hold' ? issue.waitingOn : '', placeholder: 'What are we waiting on?' });
    const after = el('input', { value: issue.checkAfter, placeholder: 'YYYY-MM-DD' });
    const fields = el('div', { class: 'stack' }, formField('Waiting on', waiting), formField('Check after (optional)', after));
    const toggle = () => { fields.hidden = select.value !== 'on-hold'; waiting.required = !fields.hidden; };
    select.addEventListener('change', toggle);
    toggle();
    const save = primary('Move task');
    const dialogError = alert();
    const cancel = button('Cancel', () => dialog.close());
    const form = el('form', { class: 'stack', on: { submit: event => {
      event.preventDefault();
      showError(dialogError, '');
      if (select.value === 'on-hold' && !waiting.value.trim()) {
        showError(dialogError, 'Enter what the task is waiting on.');
        return;
      }
      if (select.value === 'on-hold' && after.value.trim() && !/^\d{4}-\d{2}-\d{2}$/.test(after.value.trim())) {
        showError(dialogError, 'Enter Check after as YYYY-MM-DD.');
        return;
      }
      mutate(() => backend.move(issue, select.value, fields.hidden ? '' : waiting.value, fields.hidden ? '' : after.value),
        `Moved to ${moveNames[select.value]}.`, [save, cancel, select], e => showError(dialogError, e.message));
    } } }, el('h2', { id: 'move-title' }, 'Move task'), formField('Status', select), fields, dialogError,
    el('div', { class: 'actions' }, cancel, save));
    const dialog = el('dialog', { 'aria-labelledby': 'move-title', on: { close: () => dialog.remove(),
      cancel: event => { if (busy) event.preventDefault(); } } }, form);
    main.append(dialog);
    dialog.showModal();
  }

  loadDetail();
}

navigate(backend ? 'queue' : 'settings', {}, true);
if ('serviceWorker' in navigator) {
  navigator.serviceWorker.register('./sw.js').then(() => navigator.serviceWorker.ready)
    .then(registration => registration.active?.postMessage('boss-refresh-shell')).catch(() => {});
}
