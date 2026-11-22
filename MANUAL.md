# Brain Core manual

The base manual for a personal Brain (command-center workspace): one repo that sits above all of a person's code repos. **Notes live in the workspace; code lives in the repos.** This file comes from [Brain Core](README.md), mounted in the workspace at `core/`. The Brain's own `CLAUDE.md` names the owner, adds their rules and **wins wherever the two disagree**.

A Brain workspace has three jobs:

1. **Drive changes across many repos from one place.** Each repo has its own rules (account, working branch, how changes land) in `repos.yaml`.
2. **Hand off work** to agents or teammates, then check the result yourself (diff, CI, the running thing) rather than trusting a summary.
3. **Keep what isn't written down anywhere else:** private context, the owner's own takes, decisions made in calls, gotchas, notes on people. Anything already in a chat thread, ticket, PR or doc gets a link at most, never a retelling.

"The owner" below is the person the workspace belongs to (`workspace.yaml`, `owner`).

## Layout

| Path | Role |
|---|---|
| `CLAUDE.md` | The Brain's own manual: points here first, then the owner's rules, workstreams and recipes. `AGENTS.md` is a symlink to it |
| `core/` | This core (a `git subtree` of Brain Core). Shared by everyone who uses it; see **Core** below before editing it |
| `workspace.yaml` | Owner name, GitHub account, where core comes from |
| `repos.yaml` | Registry of every repo: remote, local path, account, working branch, `pr_policy`. **Read it before any git work.** |
| `workers.yaml` | *(optional)* Execution engines for a dispatcher; its `workers:` keys are the allowed `worker:<engine>` pins, and its `filter:` line says which workstreams the developer agents pick from (see **Tasks**) |
| `repos/` | *(gitignored)* Clones, filled by `scripts/sync-repos.sh` |
| `work/` | *(gitignored)* Worktrees for in-flight changes, named `<repo>--<branch>/` |
| `workstreams/<area>/` | Long-lived areas of work: `README.md` (goal, status, links) + `notes.md` |
| `people/` | One file per person: role, context, how to work with them |
| `career/` | Brag log, goals, CV |
| `notes/` | `inbox.md` for quick capture, `YYYY/YYYY-MM-DD.md` daily logs |
| `gdrive/` | *(gitignored, optional)* Local copy of a Google Drive folder, synced with `scripts/sync-gdrive.sh` (rclone). Binaries, scans and sensitive documents go here, never in git |
| `scripts/` | Symlinks into `core/scripts/` (`task.sh`, `sync-repos.sh`, `sync-gdrive.sh`, `core.sh`) plus the workspace's own scripts |
| `.claude/skills/` | Symlinks into `core/skills/` (`autopilot`, `help-human`) plus the workspace's own skills |

## Standing rules

- **Be proactive, but confirm first.** Propose restructuring (stale notes, misfiled content, duplicates) and apply it only after a yes.
- **Capture as you go.** When a session turns up a durable fact, write it to the right file and say so in one line.
- **Don't duplicate the paper trail.** Link to it instead.
- **Add skills sparingly.** A few lines in the manual beat a new skill; add one only for a workflow that repeats often and has non-obvious steps.
- **Work autonomously, but never speak for the owner.** Code, PRs and notes are fine on a claimed task. Messages to other people need the owner's OK; messaging the owner when blocked is fine in a session they are in, but unattended runs never notify them: the issue left `input-needed` is the message.
- **Commit the workspace's own changes yourself,** straight to `main`, and push, without asking. origin/main is the shared state. Commit coarse-grained, when a logical chunk is done. If the push is rejected, `git pull --rebase` and push again.
- **Repo-specific instructions win.** Read a repo's own `CLAUDE.md`/`AGENTS.md` before working in it.
- **External repos: ask first.** Any PR, issue or comment on a repo outside the owner's account needs the owner's OK. Own private repos: no need to ask.
- **English in the repos and tasks.** Everything written down in any repo (files, commit messages, code comments) and in tasks (issue titles, bodies, comments, decision cards) is in English, whatever language the session is in. Only verbatim text meant for someone else (a letter draft, a quoted message, an official name) stays in its own language.
- **Portable links.** Relative paths only in markdown, never absolute paths or `file://` URIs.
- **Archive, don't delete.** Old material goes to an `Archive/` folder inside its area.
- **Keep structure simple.** Don't add folders or files the layout above doesn't need.

## Repos and `pr_policy`

Defined at the top of `repos.yaml`. In short: `push-main` (own private repos: commit to the working branch and push), `pr-self-merge` (own public repos), `pr` (the owner reviews and merges), `push-branch`, `fork-only` (external: ask before any upstream PR), `read-only`. A repo's `notes` say what deploys where; read them before pushing.

## Tasks

Tasks are **GitHub issues in the task repos**: the workspace repo itself (the home repo, `origin`) plus every `repos.yaml` entry with `tasks: true` or `tasks: assigned`. A task is addressed by its ref: `81` for the home repo, `<repo>#38` for another task repo (`<repo>` = its `repos.yaml` name); every `task.sh` command takes either, and `list`/`next`/`take`/`holds` serve one queue across all task repos. **Everything about a task lives in its own repo** (the issue, its claim branch, its card images), so collaborators on that repo see the whole task without access to the private workspace; in a task repo other than home, the repo itself stands for its workstream (no `ws:` label). `task.sh new ... --ws <repo>` (or `--repo <repo>`) files the task there; `task.sh setup <repo>` creates the labels in a new task repo. An issue is the handoff between many short, cheap sessions, so it stays coarse: **Goal**, **Done when**, **Plan**, **Next** in the body; the **Log** (decisions and gotchas only, link out for detail) goes in comments.

- **Assignee = whose task it is.** An issue assigned to someone on GitHub is theirs: their agents work on it, and when it is `input-needed` it waits on them. An unassigned issue is the owner's in the home repo and in `tasks: true` repos. A repo shared with collaborators gets `tasks: assigned`: there an unassigned issue is nobody's yet and no agent picks it up until someone assigns it (whoever distributes the work, e.g. the owner as product manager). So `next`, `take`, `holds` and a dispatcher serve only the owner's issues (assigned to the `github` login in `workspace.yaml`, or unassigned where that counts as theirs), and never touch an issue assigned to someone else. `task.sh assignee <n> me|<login>|none` changes it; `task.sh new` in a `tasks: assigned` repo assigns the owner unless `--assignee <login>` says otherwise; `task.sh list` shows `@login` (and `unassigned` where an issue is nobody's yet). Everyone working in a shared repo runs the same core in their own workspace, so the rule holds for all of them. **Handing off between people:** when you need an answer from a teammate, put the question on the issue (a card, or a plain question for something quick), set `input-needed` and assign it to them; they answer, assign it back and move it to `ready` when work can go on.
- **Labels:** exactly one status label (below), optionally `p1` / `p3` (urgent / low; no label means p2, `p2` also works), `ws:<workstream>`, `agent:pm`, and at most one `worker:<engine>` (engines: `workers.yaml`). No other labels. Roles and execution engines are independent. Worker tags are strict pins: an unavailable pinned engine means wait. `task.sh worker <n> <engine>|auto` changes only this pin; `new --worker <name>` sets it at creation.
- **Which workstreams agents pick:** `filter:` in `workers.yaml`, e.g. `filter: [app, "-site"]`: `name` = only these workstreams, `-name` = all except these (issues with no workstream stay), `[]` or no line = all. A task's workstreams are its `ws:` labels plus, outside the home repo, its repo's name. `task.sh next`, `take` and `holds` apply it to the developer's queue, so every agent (autopilot, a dispatcher) follows the same line; `--ws <terms>` overrides it for one run (`--ws all` lifts it, `--ws=-name` for an exclusion), and `list` always shows everything. A phone app or other tool that edits the filter writes this same line.
- **Agents:** a `ready` issue is for one of two agents. No agent label: the **developer** ([autopilot](skills/autopilot/SKILL.md)): code, tests, landing changes. `agent:pm`: a **product manager** (a workspace skill, if the workspace has one): product questions answered from user data and feedback, research, options and specs; it writes no code and ends in a decision card or in developer tasks. `task.sh next/take/holds` serve the developer's queue; `--agent pm` serves the PM's. `task.sh new ... --agent pm` makes a PM task, `task.sh assign <n> dev|pm` hands one over.
- **Statuses:** every open issue carries exactly one status label; closing the issue means done. "In progress" is not a status: it is a claim (below), and `task.sh list` shows it.

  | Status | Meaning | Agents |
  |---|---|---|
  | `backlog` | An idea, or not yet specified enough to start | never pick up |
  | `ready` | An agent's turn: Goal and Done when are clear (or **Next** says what to do) | pick up (`take`), by priority, then issue number |
  | `input-needed` | Someone has to answer: the owner, or the assignee if it is assigned to someone else. The work can't go on without an answer, a decision or an input only they can give. `Waiting on:` line at the top of the body says what. Never for finished work (see **Done = landed**) | never; whoever answers flips it back to `ready` |
  | `on-hold` | Waits for a condition: another issue, an event, a date. `Waiting on:` line states the condition, optional `Check after: YYYY-MM-DD` | re-check the condition every run (`holds`); when it's met, move to `ready` |
  | closed `completed` / `not_planned` | done / dropped | |

  An issue with no status label (e.g. made in the GitHub UI) counts as `ready`. `input-needed` used to be called `in-review`: `task.sh` still reads the old label and keeps writing it in a repo that has it, so workspaces on an older core keep working. Once everyone working in a repo runs this core, `task.sh setup <repo> --rename-in-review` renames the label (it stays on every issue). Issues with an active claim are never picked up; claims older than 2 h without refresh are eligible again.
- **Done = landed.** A task is done when its change is on the repo's working branch per `pr_policy` and verified there (tests, CI, staging). Prod deploys are not part of tasks: they happen separately, on their own track. So Done when never includes a prod deploy or a check on prod, and a finished task asks the owner nothing: no "OK to deploy?", no "check it on prod", no "have a look".
- **Questions and decision cards.** A quick question is one the `Waiting on:` line fully states, answered in a word or a fact with no context needed ("Which Stripe account, personal or company?"): `task.sh set <n> input-needed --waiting-on "<question>"`. Anything else gets a card: `--card <file>`, for options, a recommendation, or context the answer depends on. The script refuses a card over 120 words or 5 bullets, and its question becomes the `Waiting on:` line by default.
  ```markdown
  ### <the question, one line>
  Options: Pop | Glide | Reveal
  Recommend: Pop, it reads best on a phone

  - up to 5 one-line bullets: what is on the table, the trade-off, what each answer leads to
  ![](<image url>)

  [Log](<link>) · [diff](<link>)
  ```
  `Options:` is optional (without it the answer is Yes / No), so is `Recommend:`.
  **Easy on the head.** Write it the way you'd brief a manager: lead with the outcome and what changes for the owner or the user ("the video starts faster"), then the trade-off, in short everyday words, one idea per bullet. No dense jargon, stacked abbreviations or clever phrasing; a technical term is fine when it's the shortest clear way to say it. Code names, file paths and internals go behind the Log link. If a line needs a second read, rewrite it.
  **The card is all they read.** The owner answers from the card alone, on a phone, without opening anything else. So never send them elsewhere for the question: no "answer the open questions in the spec", "see the doc", "details in the Log". Bring each question onto the card in plain words with what it changes, the options and your recommendation, and put the question itself in the `Waiting on:` line, never where to find it. Links are for a closer look, never required reading.
  **Several questions: one card.** When a piece of work raises several decisions, put them on one card that asks "go with these?": one bullet per decision (the call you recommend in bold, then the trade-off or the alternative), `Options: All | Change some`; the owner's note overrides any single bullet. If they don't fit, decide the low-stakes ones yourself (say so on the card) or ask the one that blocks the work first and leave the rest for later cards.
  **Show the options.** Add a sample alongside an option when it helps the owner judge it. Link the diff, staging preview, or other evidence when available. `scripts/task.sh upload <n> <file>...` stores images on the `cards` branch of the task's repo and prints URLs to embed as `![](<url>)`.
  The owner answers with a comment that starts `<!-- answer -->` (the chosen option or Yes/No, plus an optional note) and moves the issue to `ready`. A comment that starts `<!-- question -->` is a question instead: answer it with a new card and put the issue back to `input-needed`. Log comments are for agents: decisions and gotchas, a few lines each, detail behind links.
- **Claims:** a claim says who holds an issue right now; it is separate from the status. It is a commit on branch `claim/<number>` of the issue's own repo, written through `scripts/task.sh`. Each claim/release is a new commit on top of the tip it read, pushed without force, so when two agents race GitHub accepts one push and rejects the other. The branch is never rewritten. Never work on an issue you haven't claimed. Claiming makes the issue `ready`.
- **Stale claims:** a claim older than 2 h with no new commit is stale. `next`, `take` and `holds` include expired claims; listing is read-only. `claim <n>` and `take` take over an expired claim automatically (non-force push, so only one worker wins). `--steal` is accepted for compatibility and never overrides a fresh claim. Re-running `claim` with your own id refreshes it; a worker lasting more than 2 h must refresh before expiry. Always release with `--as <claim-id>` so an old worker cannot release a successor's claim.
- **Scheduled runs & dispatchers (optional):** pointing a cloud scheduled task (such as a Claude Code scheduled task) or local cron job at the Brain repo with `/autopilot` works out of the box with no extra runner. When a dispatcher is used instead, `AGENT_TASK_NUMBER`, `AGENT_CLAIM_ID` and `AGENT_WORKER` are set to assign that one issue to the worker; the worker claims it itself and must not pick another (see the autopilot skill, "Assigned task mode").
- **Moving between statuses:** `release <n> --to <status>` when you let go (no `--to` keeps `ready`, for a partial chunk); `set <n> <status>` changes it without a claim. When you let go of a task: `--done` once the change is landed and verified; `--to input-needed` with `--waiting-on "<question>"` or `--card <file>` when someone needs to answer; `--to on-hold --waiting-on "<condition>"` if it now waits on something else.
- **One session = one chunk.** Do one reasonable piece, rewrite **Next** in the issue body so a fresh session can start from it, add a Log comment if something was decided, release.
- **Long remote waits:** if the next step waits on something elsewhere (CI, deploy, another issue) for more than ~30 min, release `--to on-hold --waiting-on ... [--check-after YYYY-MM-DD]` instead of holding the claim.
- **Tooling:** the script uses `gh api` (REST) and needs `gh` signed in. Where GraphQL is blocked (some cloud sandboxes), `gh issue ...` does not work; use `gh api`. `scripts/task.sh prune` (deletes claim branches of closed issues) needs a machine that may delete branches.

### Decisions before implementation

Before implementing a task, check for any unresolved architectural, security, or product choice that materially affects how the system works, what risk it accepts, or what users get. For a material choice, get the owner's decision before implementing the part that depends on it. Proceed directly on routine fixes and choices already settled in the issue or its answers. Apply the same check if a new material choice comes up during the work.

1. Investigate the issue, relevant code, and available evidence. Compare two or three viable approaches. Use a small, reversible experiment if it will clarify a trade-off.
2. Present the decision on one card. State what the owner is deciding, what changes with each option, and its main trade-off. Recommend an approach and give the reason. If a visual makes the choice easier, embed a phone-readable diagram, mockup, or a short set of slides as images. Keep the question, options, and recommendation readable in the card text.
3. Record in **Next** where to resume after the decision and release the claim with `--to input-needed --card <file>`. The owner can choose an option or leave a note. Read their answer before resuming the work that depends on it.

## Recipes

**Pick up the next task**
```bash
git pull --rebase --autostash
scripts/task.sh list                 # open issues by status, with claims; input-needed (needs an answer) on top
scripts/task.sh holds                # on-hold issues to re-check; condition met -> `set <n> ready`
scripts/task.sh take                 # claims the next ready one; prints "<number> <claim id> <title>"
# or: scripts/task.sh next --all      # the whole pickup queue, to look before claiming
# ... work; update the issue's Next, add a Log comment ...
scripts/task.sh release <n> --as <claim id> [--done | --to input-needed --card <file> | --to on-hold --waiting-on "..."]
```
Or pick one yourself: `scripts/task.sh claim <n>` (prints your claim id).

**Create a task:** `scripts/task.sh new "<title>" [--priority 1|2|3] [--ws <workstream>] [--backlog]` prints the issue number; it starts as `ready` (`backlog` with `--backlog`, for ideas). Fill in Goal/Done when/Plan/Next right away; if you can't yet, put it in the backlog. Then claim it if you're starting now.

**Change a status by hand:** `scripts/task.sh set <n> <status> [--waiting-on ...] [--check-after ...]`, e.g. `set 7 ready` after answering an `input-needed` question.

**Change code in a repo**
1. Look the repo up in `repos.yaml` (branch, `pr_policy`, notes); read its own `CLAUDE.md`/`AGENTS.md`.
2. `scripts/sync-repos.sh <name>` to make sure it's cloned and fetched.
3. For anything beyond a trivial edit, use a worktree: `git -C repos/<name> worktree add ../../work/<name>--<branch> -b <branch> origin/<working-branch>`.
4. Land it per `pr_policy`, then `git -C repos/<name> worktree remove ../../work/<name>--<branch>`.

**Hand off and verify:** put the brief in the issue (Goal, Done when). When the result comes back, check the diff/PR/CI yourself before marking anything done; note what you verified in the Log.

**Add a repo:** add an entry to `repos.yaml`, run `scripts/sync-repos.sh <name>`, commit. Add `tasks: true` to make its issues part of the queue (`tasks: assigned` for a repo shared with collaborators: only the issues assigned to you), then `scripts/task.sh setup <name>` once.

**Sync Google Drive:** `scripts/sync-gdrive.sh` (remote set in `workspace.yaml`, `gdrive`).

## Core

`core/` is shared code. Most workspaces only **use** it:

- **Update:** `scripts/core.sh pull` merges the latest Brain Core into `core/` (one squashed commit), then re-links new scripts and skills. Commit nothing else in that commit.
- **Customize in your own layer, not in `core/`.** Your `CLAUDE.md` overrides this manual; your own skills and scripts sit next to the linked ones. Edits inside `core/` will conflict on the next pull.
- **Contribute:** port the change into a fresh branch of the Brain Core repo (not your workspace history, so no private commits or messages travel) and open a PR there. Ask the owner first, as with any external repo.

A workspace whose `workspace.yaml` sets `core.maintainer: true` also **pushes** core from inside the workspace:

- Commits that touch `core/` touch nothing else, and their messages are safe to publish: they become public commits in Brain Core.
- `scripts/core.sh push` splits `core/` out of the workspace history, scans every outgoing commit message and diff for the terms in `.core-denylist` (private, one regex per line), and pushes to the `sync/<workspace>` branch of Brain Core, opening a PR. `scripts/core.sh diff` shows what is not upstream yet.
- Merge those PRs with a **merge commit** (not squash or rebase), or the next push re-sends the same commits.
