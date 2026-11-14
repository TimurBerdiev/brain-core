# Brain Core

**The public core for your personal AI Brain.** One private Git repo (your **Brain**, or command center) that remembers for you, knows all your projects, and runs your coding agents as a team that comes to you only when a decision is really yours.

- **Completely free.** MIT-licensed. No subscription, no account, no server to host.
- **Pure Git.** Your notes, rules, task queue and agent claims are plain files, commits and GitHub issues. No database, no SaaS, no lock-in: clone the repo and you have everything.
- **Makes your life easier.** Stop re-explaining your setup in every session. Every agent reads the same manual and works from the same queue, and you get short decision cards you can answer at a glance, even from your phone.
- **Works with the agents you already use:** Claude Code, Codex, and any agent that reads `CLAUDE.md`/`AGENTS.md` and can run a shell.

## The idea

Your **Brain** (command center) is one private repository where everything comes together: part memory, part knowledge base, part project office. **Brain Core** is the shared, open-source engine mounted inside it at `core/`.

- **Just talk to it.** Open a session (Claude Code, Codex, ...) in your Brain and start on any of your projects right away. The agent has already read the manual and knows your repos, your rules and where things stand. You don't re-explain anything.
- **It keeps notes so you don't have to.** As you talk, the agent writes down what matters: decisions, your own takes, gotchas, notes on people, a daily log. You don't have to track every conversation, because the next session picks up from the files. What already lives in a ticket, PR or doc gets a link, not a copy.
- **The real work happens elsewhere.** Code stays in its own repos. Your Brain knows all of them, sends changes into them, hands tasks to agents and checks what comes back.
- **Dead simple.** Plain Markdown files, plain Git, plain GitHub issues. No app to install, nothing to buy, no extra service. If you can clone a repo, you have the whole thing.

On top of that sits the agent team: a shared task queue that agents pick up on their own, and short decision cards when they need you.

## What's inside

A personal Brain (command center) for working with coding agents (Claude Code, Codex, others): one private Git repo that sits above all your code repos and holds your notes, your repo registry, and a task queue that agents pick up on their own.

- **A manual for agents** ([MANUAL.md](MANUAL.md)): how your Brain is laid out, standing rules, how changes land in each repo.
- **A task queue on GitHub issues** (`scripts/task.sh`): statuses as labels, race-safe claims as commits on `claim/<n>` branches, decision cards when an agent needs your answer. One queue across your Brain repo and any repo marked `tasks: true`; in a repo shared with others (`tasks: assigned`) each person's agents take only the issues assigned to them.
- **Repo registry** (`repos.yaml` + `scripts/sync-repos.sh`): every repo with its working branch and `pr_policy`.
- **Skills**: `autopilot` (do the next task end to end, unattended) and `help-human` (turn a fuzzy idea into a plan, one question at a time).

Your Brain mounts this repo at `core/` as a [git subtree](https://github.com/git/git/blob/master/contrib/subtree/git-subtree.adoc), so every clone, cloud session and CI job has it with no extra step. Your own notes and rules stay in your Brain; `core/` stays shared.

## Start a workspace

**Guided:** tell your agent (Claude Code, Codex, ...)

> Set up Brain Core for me using https://raw.githubusercontent.com/rawmahn/brain-core/main/SETUP.md

It checks the prerequisites, runs every command, and sets up your rules, repos and a first task with you, one question at a time ([SETUP.md](SETUP.md)).

**By hand:** needs `git`, `python3` (PyYAML recommended), and [`gh`](https://cli.github.com) signed in.

```bash
mkdir my-brain && cd my-brain && git init -b main
git commit --allow-empty -m "Start brain"
git subtree add --prefix=core https://github.com/rawmahn/brain-core.git main --squash
core/init.sh --owner "Your Name" --github your-account
git add -A && git commit -m "Set up brain with Brain Core"
gh repo create my-brain --private --source . --push
```

`init.sh` creates `CLAUDE.md` (your own manual, which reads `core/MANUAL.md` first), `workspace.yaml`, `repos.yaml`, the folders, and links `scripts/` and `.claude/skills/` into `core/`. Then:

1. Add your repos to `repos.yaml` (`tasks: true` on the ones whose issues should be tasks) and run `scripts/sync-repos.sh`.
2. Create the task labels: `scripts/task.sh setup` (and `scripts/task.sh setup <repo>` for each task repo).
3. Open your Brain in your agent and try `scripts/task.sh new "First task"`, then `/autopilot`.

## Running agents autonomously

You don't need a custom orchestrator, database, or always-on server to run autonomous agents. Everything an agent needs — the manual, linked skills, `scripts/task.sh`, and `repos.yaml` — already lives inside your Brain repo.

**Cloud scheduled task (easiest, zero infrastructure):** point a **Claude Code scheduled task** (Claude Code on the web) or similar cloud agent routine at your Brain repo with `/autopilot` as the prompt (or `/autopilot <workstream>` to scope it). On each run, the agent:

1. Reads `CLAUDE.md` and `core/MANUAL.md`.
2. Syncs your code repos with `scripts/sync-repos.sh`.
3. Re-checks `on-hold` tasks (`scripts/task.sh holds`) and picks the next `ready` task it can finish in that session.
4. Claims the issue on a `claim/<n>` branch (`gh api` REST only — no GraphQL needed, and non-force pushes prevent two runs from colliding).
5. Implements, tests, pushes per `pr_policy`, logs what happened, and releases (`--done`, `--to in-review --card ...` for a decision card, or `--to on-hold`).

Your computer can be off, and multiple scheduled runs or interactive sessions won't step on each other.

**Good to know for cloud runs:**

- **Repo access:** connect any code repos from `repos.yaml` that the scheduled task should edit so the cloud environment can clone and push to them. If a repo or credential isn't reachable in a cloud session, `/autopilot` skips tasks that require it and moves to the next qualifying task.
- **Branch cleanup:** releasing never deletes `claim/<n>` branches (some cloud sandboxes block deleting refs); run `scripts/task.sh prune` occasionally from a local machine to clean up closed claims.
- **Local / VM option:** if a task needs local tools, private keys, or specific environments, you can run the exact same `/autopilot` prompt via `cron` / `launchd` (e.g. `claude -p "/autopilot"`) or on a dedicated VM.

## Updating

```bash
scripts/core.sh pull
```

Customize in your own `CLAUDE.md`, skills and scripts rather than inside `core/`, so pulls merge cleanly.

## Contributing

Fork this repo, port the change into a fresh branch of your fork (rather than pushing your Brain's history, which carries your private commits) and open a PR here. Maintainers who edit `core/` inside their Brain use `scripts/core.sh push`, described in [MANUAL.md](MANUAL.md#core).

Tests: `python3 -m unittest discover -s scripts -p 'test_*.py'`.

## License

[MIT](LICENSE)
