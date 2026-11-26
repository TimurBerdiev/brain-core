# Boss for iPhone

1. Open [Boss](https://timurberdiev.github.io/brain-core/) in Safari. Tap **Share → Add to Home Screen**, then **Add** (leave **Open as Web App** on if Safari shows it). Open Boss from the new icon. There is no App Store install, signing fee, or seven-day reinstall.
2. [Create a fine-grained GitHub personal access token](https://github.com/settings/personal-access-tokens/new). Select your Brain repo and all its task repositories, and grant repository permissions **Issues: Read and write** and **Contents: Read-only**. Choose an expiration allowed by the account policy; if the token expires or is revoked, replace it in Settings. This does not affect the home-screen app itself.
3. Enter the Brain repo as `owner/name` and paste the token into Boss. The token is saved in local storage on this browser origin and is sent only to GitHub. Only use an origin you trust, and use **Settings → Forget this workspace** to clear the token. Saved browser data may be cleared by iOS. Boss needs GitHub access to read and update real tasks.

Use **Try with sample tasks** to try the queue, decision cards, Move, and search without connecting to GitHub.
The sample workspace uses Boss-building tasks; **For you** puts P1, P2, and P3 decisions first, then unlabeled decisions.
Within each priority it shows the longest-waiting first, using the GitHub status-label event (or the issue's last update if unavailable).

## Use Boss to build Boss

Use an Issues-enabled shared repository both people can access. Add it to each Brain's `repos.yaml` as `tasks: assigned`.
For the Boss dogfood repo, you can also connect the PWA directly to `TimurBerdiev/brain-core` using your own token.
With no Brain workspace files in that repo, Boss uses the signed-in GitHub account to identify your tasks.
After checking the GitHub logins and assignee access, preview and run the same command without `--dry-run`:

```sh
python3 scripts/dogfood.py OWNER/REPO --timur TIMUR_LOGIN --roman ROMAN_LOGIN --dry-run
```

Run this from the core checkout with `gh` signed in and permission to edit issues and labels. Re-running keeps existing issues and decisions.
If Roman cannot yet be assigned issues, use `--roman none` and assign his tasks in GitHub once he has access.
After answering in Boss, run scoped `/autopilot <task-repo-alias>` in the assignee's Brain (or wait for their configured runner).
The worker reads the answer and resumes from **Next**; Boss itself doesn't start a worker.

### Run the whole loop locally

From the core checkout, run `python3 tests/boss_dogfood_server.py` and open
[the local dogfood workspace](http://127.0.0.1:4174/boss-web/?local-dogfood) in Chrome. No token is required and no data is sent to GitHub.
Open the P1 card **Resume Boss work after a decision**, pick **Run on demand**, and send the answer.
Then start the test worker:

```sh
curl -fsS -H 'Content-Type: application/json' -d '{}' http://127.0.0.1:4174/worker/take
```

Refresh Boss to see **In progress**, then open the task's **Log** for the worker's record of the answer and Next.
The server uses in-memory issues and a disposable local Git remote for the real `task.py` claim.
Restart it to reset the sample. The same path is exercised automatically by `npm test` (requires Python 3 and Git).

## Development and deployment

Serve the repository root with `python3 -m http.server 4173` and open `http://localhost:4173/boss-web/`. There is no app build. Run `npm test` in `boss-web/` for the same `tests/fixtures/` cases as Android and Python. For iPhone-sized WebKit checks, run `npm ci`, `npx playwright install --with-deps webkit`, and `npm run test:ui`. Screenshots are written to `boss-web/screenshots/`.

The app is served from GitHub Pages by `boss-web.yml`. Before the first deployment, a repo admin must choose **Settings → Pages → Build and deployment → Source → GitHub Actions**. Then merge to `main` or run the **Boss web** workflow on `main`.

The service worker caches only the static app shell, never GitHub responses. The vendored browser module is js-yaml 4.2.0 (MIT; see `vendor/LICENSE`).
