# Boss

Android app and home-screen widget for your Brain's task queue: GitHub issues with status labels in your Brain repo and its task repos ([MANUAL.md](../MANUAL.md), "Tasks"). Answer decision cards, change statuses and watch your agents from the phone. No server: Boss connects directly to GitHub through a GitHub App.

- **Tabs:** Tasks keeps the queue and decision cards; Activity shows recent changes; Runs lists agent runs like CI builds; Usage shows agent subscription limits.
- **Ask AI:** Tasks, task details, Activity and Usage offer Claude / ChatGPT. The chooser previews a short
  task or screen reference (title, repository and GitHub source link when available). It shares text to the
  selected official Android app. If sharing is unavailable, Boss copies the reference and opens that app,
  or its official website if the app cannot open. Paste it there and choose what to ask. Boss never sends
  a prompt automatically; issue bodies, logs, usage measurements and credentials stay out of the handoff.
- **Activity:** rolling Last hour / 24 hours / 3 days views combine default-branch commits, task and pull-request events,
  comments, and agent-claim pushes. Every row names its repository and links to GitHub. Author/coauthor names are shown
  only when supplied by GitHub; an account name alone does not prove which engine did the work.
  Edit the watched repositories in Activity (candidates come from `repos.yaml`; grant the app access to each selected repo).
  The default is home plus task repos. Activity has its own saved cache; unavailable repos keep their saved entries with a warning.
  GitHub task events may be delayed and are capped at 300 per repo; commit loading is capped at 1,000 in three days.
- **Runs:** agent runs (a nightly release, a test run, ...) newest first, from `runs/index.json` on the `runs` branch
  of home and every task repo (format below, "Run records").
  Each row: repo, kind, when, status (Running, Passed, Failed, Blocked, Nothing to do, Error, Timed out, Stopped), what it tested
  (tag or commit, environment), its one-line summary and how long it took. A run still running ten minutes past its deadline shows as
  Lost. Tap a run to load its record: one line per check (✓ / ✗ / ! blocked / – skipped) with the deciding number, filed issues, and links
  to the log entry, the evidence and the record itself. A repo without a `runs` branch simply has no runs; one that fails to load keeps
  its saved runs with a warning. Same Contents permission as Usage; nothing is written.
- **Usage:** Antigravity / Claude Code / Codex cards read a sanitized `usage.json` from the home repo's `agent-state` branch
  (format below) with the same Contents permission. Something on your agent machine writes it (a scheduled collector);
  Refresh in the app re-reads that snapshot and never polls providers from the phone. Each window shows the remaining
  percentage, its reset in the phone's time zone and the measurement's age. After 30 minutes, a passed reset, incomplete
  data or a collection error, the card marks the measurement out of date. Missing quotas stay unknown, never 0% used.
  Without the branch the tab says so and nothing else changes.

- **Task repos:** the Brain repo in Settings (home) plus every `repos.yaml` entry there with
  `tasks: true` or `tasks: assigned`. The app re-reads that list on each sync and shows one queue.
  Tasks outside home use the name from `repos.yaml` in references such as `site#38` and in workstream filters. If a task repo
  is unavailable, Boss keeps its saved tasks and names the repo in the sync details. The home repo must answer to refresh the queue.
- **Check access:** Settings → Task repositories shows whether Boss can read issues and contents for each
  repository. It checks automatically on open and can check a personal token before you save it. If the registry
  can't be read, the screen marks the previously known list as cached. The check makes no writes.

- **Statuses** are labels: `backlog`, `ready` (agents), `input-needed` (someone has to answer: the assignee, else the owner;
  the old label `in-review` reads the same), `on-hold`; closed = done. A task an
  agent is working on (a fresh `claim/<n>` branch in the task's repo) shows as "in progress".
- **Whose tasks:** the queue and Completed section show tasks assigned to you (the `github:` login in
  home's `workspace.yaml`) or unassigned by default, including unassigned tasks in shared repos.
  Tasks → **Show everyone's tasks** includes collaborators' tasks and saves the choice for the widget too.
  Their `@login` labels stay visible; their decision cards appear under On hold. Until the owner login
  is known, all tasks stay visible. This view setting does not change which tasks agents pick.
- **Completed tasks:** a collapsible section below Backlog, newest updates first. Open a task to read its
  details and activity. It follows the workstream filter and excludes tasks closed as not planned.
  History loads when opened and has its own cache, so it doesn't delay the widget's open-task sync.
- **Quick search:** tap the search icon and type a number (`70` or `#70`), an exact task reference (`site#38` or
  `owner/site#38`), or part of a title or description.
  Results update as you type, across every assignee, workstream and status, including completed and dropped tasks.
  Search ignores the queue filters so collaborators' tasks remain discoverable.
  Exact issue numbers come first. Tap a result to open it; Back returns to the same search.
  All issue pages load when search opens, independently of widget sync; typing makes no new requests.
  Saved results remain searchable offline. Use the search refresh button to update the full history.
- **Widget:** what waits on you (`input-needed`), one line per task, plus counts of the other statuses.
- **Filter by workstream** (`ws:<name>` labels): chips above the list and in Settings. A tap cycles a chip:
  **only** this workstream (✓), **all except** it (✗, struck through), off. Several can be on at once, "All"
  resets. "Only" hides issues without a `ws:` label; "except" keeps them. The widget follows the same filter
  and names it in its header ("All except site").
- **Agents pick** (Settings): the same chips decide which ready tasks the agents take (`task.sh next`/`take`
  apply it, so Autopilot and any dispatcher follow it).
  It is the `filter:` line in the Brain's `workers.yaml` (same format: `name` / `-name`); Save commits it,
  so GitHub access needs Contents: read and write for this one. If the file changed meanwhile, the app reloads
  it and keeps your edit to save again.
  Tap a task to open it or ↻ to request a refresh. Android runs the refresh as background work and schedules
  automatic refreshes about every 15 minutes. Temporary failures get up to three attempts for a manual refresh,
  including when only one task repo is unavailable. App and widget syncs run one at a time. The widget keeps
  showing the last full sync time alongside any pending refresh, retry, or GitHub access message.
- **Task screen:** the decision card (question, recommendation, evidence with images and links, answer
  buttons from its `Options:` line, Yes / No without one), a comment box, then the issue body and the Log,
  folded.
  - **Send** posts `<!-- answer -->` with the question quoted and moves the issue to `ready`.
  - **Ask instead** posts `<!-- question -->`; the agent answers with a new card and sets `input-needed` again.
  - **Move to…** (menu) changes the status to ready, on-hold (with Waiting on / Check after), backlog, done,
    or dropped. Use `task.sh` or GitHub to mark an issue `input-needed`. For a question without a card, Boss
    shows the `Waiting on:` line or the issue title and accepts a written answer.

## Install

1. Open the releases page for the Brain Core repository publishing your build, download `boss.apk` on the phone,
   and install it. Install new releases over the old one.
2. In Boss, enter your Brain repository (`owner/name`) and tap **Connect with GitHub**. Copy the one-time code
   using the button in Boss, paste it on GitHub, approve access, and return to Boss. If your repo isn't granted yet,
   tap **Install GitHub App on your repositories**, select the Brain repo and its task repos on GitHub, then return
   and tap **Check installation and connect**. An organization may require an owner to approve the installation.
3. Long-press the home screen → Widgets → Boss.

Boss stores GitHub App credentials in Android Keystore-encrypted storage and refreshes them for the app and widget.
If access is revoked, reconnect in Settings. You can revoke Boss under [Authorized GitHub Apps](https://github.com/settings/apps/authorizations).
Existing personal access tokens keep working until you switch. Builds without a registered App can still use a
[fine-grained token](https://github.com/settings/personal-access-tokens/new) with the same repository access.

### Register the GitHub App for a Boss release

1. [Create a GitHub App](https://github.com/settings/apps/new). Use the Brain Core repository URL as its homepage.
   Enable **Device Flow**. Leave **Request user authorization (OAuth) during installation** unchecked and disable
   webhooks; no callback URL, webhook server, client secret or private key is needed for Boss. Keep user-to-server
   token expiration enabled. Choose **Any account** if users need to install it outside the owner's account.
2. Set repository permissions: **Issues: Read and write**, **Contents: Read and write** (write is only needed
   for the Agents pick filter), and the default **Metadata: Read-only**. Install on selected repositories, not all
   repositories, unless you intend to grant all. Include the workspace and every task or watched repository you use.
3. Under the publishing repository's **Settings → Secrets and variables → Actions → Variables**, set
   `BOSS_GITHUB_APP_CLIENT_ID` to the App's **Client ID** (not its numeric App ID) and
   `BOSS_GITHUB_APP_SLUG` to the name in its public link (`github.com/apps/<slug>`). Both values are public.
   The release workflow embeds them in the APK and refuses to publish a signed release without them. For a local
   build, pass them as environment variables or Gradle `-P` properties with the same names.

## Formats the app reads

Besides the task system itself (labels, `Waiting on:` / `Check after:` lines, cards, `<!-- answer -->` /
`<!-- question -->` comments, `claim/<n>` branches, `repos.yaml`, `workspace.yaml`, the `filter:` line of
`workers.yaml`; all in [MANUAL.md](../MANUAL.md) and [`scripts/task.py`](../scripts/task.py)), two optional files:

### Run records

`runs/index.json` on the `runs` branch of any task repo, newest first, and one `runs/<id>.json` per run with the same
fields plus `checks`, `links` and `issues`. The agents own that branch; nobody builds on it.

```json
{"v": 1, "runs": [{"id": "release-20261122-024000-ab12", "kind": "release", "status": "passed",
  "started": "2026-11-22T02:40:00Z", "ended": "2026-11-22T03:31:00Z", "deadline": "2026-11-22T06:40:00Z",
  "summary": "one line a person reads in the list", "engine": "codex",
  "target": {"ref": "v1.4", "sha": "<40-char sha>", "env": "staging"},
  "checks": [{"name": "export", "result": "pass", "note": "the deciding number"}],
  "links": [{"label": "Log", "url": "https://..."}], "issues": ["site#90"]}]}
```

- `id`: lowercase letters, digits and dashes. `status`: `running`, `passed`, `failed`, `blocked`, `skipped`, `done`,
  `error`, `timeout` or `stopped`; a `running` run ten minutes past its `deadline` shows as lost.
- Check `result`: `pass`, `fail`, `blocked` or `skip`. Only `https://` links are shown.
- Never put logs, tokens or user data in a record: link to them.

### Usage snapshot

`usage.json` on the home repo's `agent-state` branch:

```json
{"schema_version": 1, "providers": [{"key": "codex", "observed_at": "2026-11-22T10:00:00Z", "source": "cli",
  "auth_state": "ok", "error": "", "quota_reached": false, "credits": null,
  "windows": [{"name": "Five-hour", "used_percent": 42.0, "window_minutes": 300, "resets_at": "2026-11-22T13:00:00Z"}]}]}
```

Provider keys: `antigravity`, `claude-code`, `codex`. `auth_state` `not_authenticated` shows "Sign-in required";
`used_percent: null` stays unknown.

## Develop

- Kotlin, Jetpack Compose (own palette and type scale in `Theme.kt`; the screens are stateless in `Screens.kt`), Glance
  for the widget, WorkManager for the background refresh, OkHttp against the GitHub REST API.
- In `boss/`: `./gradlew testDebugUnitTest assembleRelease` (JDK 17–21; an Android SDK through `local.properties`
  `sdk.dir` or `ANDROID_HOME`). The unit tests cover the card / status logic and the markdown parser; `ScreenshotTest`
  renders every screen, light and dark, and `WidgetRenderTest` the widget, to `app/build/screens/` (Robolectric, no
  device). Look at them for any UI change and add a case for a new screen. CI uploads them as the `screens` artifact.
- Dependencies come through Google's mirror of Maven Central first (`settings.gradle.kts`): Maven Central
  rate-limits shared cloud IPs with 429s.
- **The apps mirror `scripts/task.py`** (`Model.kt` and `boss-web/model.js`: statuses, the card format, markers, task
  repos, the workstream filter's `matches`). Change the implementations in the same commit. The cases in
  [`tests/fixtures/`](../tests/fixtures) are run by Python, Android, and Boss web; add a case there for a new rule.
- **Signing:** [`.github/workflows/boss.yml`](../.github/workflows/boss.yml) signs release APKs with the key in the repo
  secrets `BOSS_KEYSTORE_B64` (base64 of the keystore, alias `boss`) and `BOSS_KEYSTORE_PASSWORD`, so each release
  installs over the last. Without them (a fork, a PR, a local build) the APK is debug-signed and nothing is published.
  To ship your own build, set your own pair of secrets; a phone can't update between builds signed with different keys.
