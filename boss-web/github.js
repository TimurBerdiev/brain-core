import { load, CORE_SCHEMA } from './vendor/js-yaml.mjs';
import { apiImageUrl, belongsTo, claimHolder, issueModel, ownerOf, statusChange, taskReposOf } from './model.js';

const API = 'https://api.github.com';
export function isLoopback(location) {
  return location?.protocol === 'http:' && ['localhost', '127.0.0.1', '[::1]'].includes(location.hostname);
}

export const validRepo = value => /^[a-z\d][a-z\d-]*\/[a-z\d_.-]+$/i.test(value)
  && !['.', '..'].includes(value.split('/')[1]);

function fromApi(item, repo, repoAlias) {
  return issueModel({ number: item.number, title: item.title, body: item.body ?? '',
    labels: item.labels.map(label => label.name), state: item.state, stateReason: item.state_reason ?? '',
    updatedAt: item.updated_at, url: item.html_url, assignees: (item.assignees ?? []).map(a => a.login), repo, repoAlias });
}

export class GitHub {
  constructor(home, token, { localOrigin } = {}) {
    if (!validRepo(home)) throw new Error('Enter a GitHub repository as owner/name.');
    const origin = localOrigin ? new URL(localOrigin) : null;
    if (origin && (!isLoopback(origin) || token)) throw new Error('Local dogfood requires loopback and no GitHub token.');
    this.api = origin ? `${origin.origin}/api` : API;
    this.home = home;
    this.token = token;
    this.repos = new Map();
    this.owner = '';
  }

  async request(url, { method = 'GET', body, accept = 'application/vnd.github+json' } = {}) {
    const target = new URL(url);
    if (!target.href.startsWith(`${this.api}/`)) throw new Error('Invalid GitHub API address.');
    const headers = { Accept: accept, 'X-GitHub-Api-Version': '2022-11-28' };
    if (this.token) headers.Authorization = `Bearer ${this.token}`;
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    let response;
    try {
      response = await fetch(target, { method, body: body === undefined ? undefined : JSON.stringify(body),
        headers, credentials: 'omit', cache: 'no-store', referrerPolicy: 'no-referrer' });
    } catch {
      throw new Error('Could not reach GitHub. Check your connection and try again.');
    }
    if (!response.ok) {
      const data = await response.json().catch(() => ({}));
      const error = new Error(`GitHub ${response.status}${data.message ? `: ${data.message}` : ''}`);
      error.status = response.status;
      throw error;
    }
    return response;
  }

  async call(repo, path, options) {
    const response = await this.request(`${this.api}/repos/${repo || this.home}/${path}`, options);
    return response.json();
  }

  async pages(repo, path) {
    const items = [];
    for (let page = 1; ; page++) {
      const batch = await this.call(repo, `${path}${path.includes('?') ? '&' : '?'}per_page=100&page=${page}`);
      items.push(...batch);
      if (batch.length < 100) return items;
    }
  }

  async check() {
    await Promise.all([this.call('', 'issues?per_page=1'), this.call('', 'branches?per_page=1')]);
  }

  async file(path) {
    const data = await this.call('', `contents/${path}`);
    const bytes = Uint8Array.from(atob(data.content), char => char.charCodeAt(0));
    return load(new TextDecoder().decode(bytes), { schema: CORE_SCHEMA });
  }

  async refreshRegistry() {
    this.registryRequest ??= this.readRegistry().finally(() => { this.registryRequest = null; });
    return this.registryRequest;
  }

  async readRegistry() {
    const warnings = [];
    try {
      this.repos = new Map([...taskReposOf(await this.file('repos.yaml'))]
        .filter(([, repo]) => validRepo(repo) && repo.toLowerCase() !== this.home.toLowerCase()));
      this.registryLoaded = true;
    } catch (error) {
      if (error.status === 404 && !this.repos.size) this.registryLoaded = true;
      else warnings.push(`repos.yaml: ${error.message}`);
    }
    try {
      this.owner = ownerOf(await this.file('workspace.yaml'));
    } catch (error) {
      if (error.status !== 404) warnings.push(`workspace.yaml: ${error.message}`);
      else if (!this.owner) {
        try {
          const response = await this.request(`${this.api}/user`);
          this.owner = (await response.json()).login ?? '';
        } catch (identityError) {
          warnings.push(`Could not identify your GitHub account: ${identityError.message}`);
        }
      }
    }
    return warnings;
  }

  async list(kind, previous = []) {
    const warnings = kind === 'queue' || !this.registryLoaded || this.registryRequest
      ? await this.refreshRegistry() : [];
    const repos = [['', ''], ...[...this.repos].map(([alias, repo]) => [repo, alias])];
    const path = kind === 'queue' ? 'issues?state=open' :
      `issues?state=${kind === 'completed' ? 'closed' : 'all'}&sort=updated&direction=desc`;
    const results = await Promise.all(repos.map(async ([repo, alias]) => {
      try {
        const raw = await this.pages(repo, path);
        let issues = raw.filter(item => !('pull_request' in item))
          .filter(item => kind !== 'completed' || (item.state === 'closed' && item.state_reason === 'completed'))
          .map(item => fromApi(item, repo, alias));
        if (kind === 'queue') {
          const claims = await this.claims(repo, issues).catch(() => new Map());
          issues = await Promise.all(issues.map(async issue => {
            if (issue.status !== 'input-needed' || !belongsTo(issue, this.owner)) {
              return issueModel({ ...issue, claim: claims.get(issue.number) ?? '' });
            }
            const waitingSince = await this.waitingSince(issue).catch(() => '');
            return { ...issue, waitingSince };
          }));
        }
        return { issues };
      } catch (error) {
        if (!repo) throw error;
        return { issues: previous.filter(i => i.repo.toLowerCase() === repo.toLowerCase())
          .map(i => issueModel({ ...i, repo, repoAlias: alias })), warning: `${alias}: ${error.message}` };
      }
    }));
    return { issues: results.flatMap(r => r.issues), owner: this.owner, warnings: warnings.concat(results.flatMap(r => r.warning ?? [])) };
  }

  async waitingSince(issue) {
    const events = await this.pages(issue.repo, `issues/${issue.number}/events`);
    const isWait = e => ['input-needed', 'in-review'].includes(e.label?.name);
    return events.filter(e => e.event === 'labeled' && isWait(e))
      .reduce((latest, e) => e.created_at > latest ? e.created_at : latest, '');
  }

  async claims(repo, issues) {
    const ready = new Set(issues.filter(i => i.status === 'ready').map(i => i.number));
    if (!ready.size) return new Map();
    const refs = await this.call(repo, 'git/matching-refs/heads/claim');
    const holders = new Map();
    for (const ref of refs) {
      const number = Number(ref.ref.match(/^refs\/heads\/claim\/(\d+)$/)?.[1]);
      if (!ready.has(number)) continue;
      const commit = await this.call(repo, `git/commits/${ref.object.sha}`);
      const holder = claimHolder(commit.message, (Date.now() - (Date.parse(commit.committer.date) || 0)) / 3600000);
      if (holder) holders.set(number, holder);
    }
    return holders;
  }

  async detail(issue) {
    const [fresh, comments] = await Promise.all([this.fresh(issue), this.pages(issue.repo, `issues/${issue.number}/comments`)]);
    const claims = await this.claims(fresh.repo, [fresh]).catch(() => new Map());
    return { issue: issueModel({ ...fresh, claim: claims.get(fresh.number) ?? '' }),
      comments: comments.map(c => ({ body: c.body ?? '', author: c.user?.login ?? '', url: c.html_url })) };
  }

  async fresh(issue) {
    return fromApi(await this.call(issue.repo, `issues/${issue.number}`), issue.repo, issue.repoAlias);
  }

  async move(issue, status, waitingOn, checkAfter) {
    const fresh = await this.fresh(issue);
    return this.patch(fresh, status, waitingOn, checkAfter);
  }

  async patch(issue, status, waitingOn, checkAfter) {
    const body = statusChange(issue, status, waitingOn, checkAfter);
    return fromApi(await this.call(issue.repo, `issues/${issue.number}`, { method: 'PATCH', body }), issue.repo, issue.repoAlias);
  }

  async reply(issue, body) {
    const fresh = await this.fresh(issue);
    await this.call(issue.repo, `issues/${issue.number}/comments`, { method: 'POST', body: { body } });
    try {
      return await this.patch(fresh, 'ready');
    } catch (error) {
      const partial = new Error(`Your reply was posted, but the task could not move to Ready. Use Move to finish. ${error.message}`);
      partial.replyPosted = true;
      throw partial;
    }
  }

  async image(url) {
    const api = apiImageUrl(url);
    if (!api) return url;
    const response = await this.request(this.api + new URL(api).pathname + new URL(api).search,
      { accept: 'application/vnd.github.raw+json' });
    return URL.createObjectURL(await response.blob());
  }
}
