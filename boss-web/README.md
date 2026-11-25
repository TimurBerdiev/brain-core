# Boss for iPhone

1. Open [Boss](https://timurberdiev.github.io/brain-core/) in Safari. Tap **Share → Add to Home Screen**, then **Add** (leave **Open as Web App** on if Safari shows it). Open Boss from the new icon. There is no App Store install, signing fee, or seven-day reinstall.
2. [Create a fine-grained GitHub personal access token](https://github.com/settings/personal-access-tokens/new). Select your Brain repo and all its task repositories, and grant repository permissions **Issues: Read and write** and **Contents: Read-only**. Choose an expiration allowed by the account policy; if the token expires or is revoked, replace it in Settings. This does not affect the home-screen app itself.
3. Enter the Brain repo as `owner/name` and paste the token into Boss. The token is saved in local storage on this browser origin and is sent only to GitHub. Only use an origin you trust, and use **Settings → Forget this workspace** to clear the token. Saved browser data may be cleared by iOS. Boss needs GitHub access to read and update real tasks.

Use **Try with sample tasks** to try the queue, decision cards, Move, and search without connecting to GitHub.

## Development and deployment

Serve the repository root with `python3 -m http.server 4173` and open `http://localhost:4173/boss-web/`. There is no app build. Run `npm test` in `boss-web/` for the same `tests/fixtures/` cases as Android and Python. For iPhone-sized WebKit checks, run `npm ci`, `npx playwright install --with-deps webkit`, and `npm run test:ui`. Screenshots are written to `boss-web/screenshots/`.

The app is served from GitHub Pages by `boss-web.yml`. Before the first deployment, a repo admin must choose **Settings → Pages → Build and deployment → Source → GitHub Actions**. Then merge to `main` or run the **Boss web** workflow on `main`.

The service worker caches only the static app shell, never GitHub responses. The vendored browser module is js-yaml 4.2.0 (MIT; see `vendor/LICENSE`).
