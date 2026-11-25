# Boss for iPhone

The iOS version of [Boss](../boss/README.md): the same Brain Core task queue and decision cards, in SwiftUI.
Like the Android app it has no server: it reads and writes GitHub Issues directly with your own fine-grained token
(kept in the iOS Keychain), using the same rules (`Boss/Model.swift` mirrors `boss/…/Model.kt` and `scripts/task.py`,
and is checked against the shared cases in `tests/fixtures/`).

## What it does

- **Tasks**: the queue from your Brain repo and every task repo `repos.yaml` lists (`tasks: true` / `assigned`),
  grouped as For you (input-needed), Agents (ready, with the agent holding a fresh claim), On hold, Backlog and Completed.
  Workstream chips (tap: only → except → off), "Show everyone's tasks", pull to refresh.
- **Decision cards**: the latest unanswered `<!-- card -->` with its options (Yes / No when none), the recommended one starred,
  the evidence as markdown; send an answer (an option and/or a note) or ask a question instead. Both comment the way
  `task.sh answer` does and move the task to ready.
- **Move**: ready, on hold (Waiting on / Check after), backlog, done, dropped, as `task.sh set`.
- **Search** across every status, assignee and task repo: `#70`, `site#38` or words.
- **Sample tasks**: "Try with sample tasks" on the first screen shows the whole flow without a token; nothing is sent.

Not ported yet: the Activity, Runs and Usage tabs, the home-screen widget and background refresh.

## Setup

Settings → your Brain repo (`owner/name`) and a fine-grained token covering it and its task repos, with
**Issues: read and write** and **Contents: read**.

## Build and test

Needs Xcode 16+ and [XcodeGen](https://github.com/yonaskolb/XcodeGen) (`brew install xcodegen`); the `.xcodeproj` is generated, not committed.

```bash
cd boss-ios
xcodegen generate
xcodebuild -project Boss.xcodeproj -scheme Boss -destination 'platform=iOS Simulator,name=iPhone 17' test
```

`BossTests` covers the task rules (the shared fixtures plus `ModelTests`); `BossUITests` drives the app on the sample tasks
(queue, filter, answering a card, asking instead, moving, search, first run) and keeps a screenshot of each main screen
in the test results.

## Installing on an iPhone

An iPhone runs only apps signed by an Apple developer team. Either:

1. **From Xcode (free Apple ID, 7-day signature)**: open `Boss.xcodeproj`, pick your team under
   Signing & Capabilities for the Boss target (change the bundle id if `dev.braincore.boss` is taken), plug in the
   iPhone, enable Developer Mode on it (Settings → Privacy & Security), and Run.
2. **TestFlight (Apple Developer Program)**: create the app `dev.braincore.boss` in App Store Connect, then
   `xcodebuild archive` with `DEVELOPMENT_TEAM=<team id>` and upload it (Xcode Organizer → Distribute → TestFlight),
   and add yourself as a tester. `.github/workflows/boss-ios.yml` runs the tests on every PR touching `boss-ios/`.
