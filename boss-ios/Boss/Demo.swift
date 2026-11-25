import Foundation

/// Sample tasks so the app can be tried without a token, and so the UI tests run without network.
final class DemoBackend: @unchecked Sendable {
    static let shared = DemoBackend()
    static let home = "timur/brain"
    static let site = "timur/site"
    static let me = "timur"
    private let lock = NSLock()
    private var issues: [Issue] = []
    private var comments: [String: [Comment]] = [:]
    private var nextId = 1000

    func reset() {
        lock.lock(); defer { lock.unlock() }
        let now = Date()
        func t(_ hours: Double) -> String { ISO8601DateFormatter().string(from: now.addingTimeInterval(-hours * 3600)) }
        func issue(_ n: Int, _ title: String, _ labels: [String], body: String = "", hours: Double, state: String = "open",
                   reason: String = "", repo: String = "", alias: String = "", assignees: [String] = []) -> Issue {
            Issue(number: n, title: title, body: body, labels: labels, state: state, updatedAt: t(hours),
                  url: "https://github.com/\(repo.isEmpty ? DemoBackend.home : repo)/issues/\(n)",
                  stateReason: reason, repo: repo, repoAlias: alias, assignees: assignees)
        }
        issues = [
            issue(70, "Pick the landing page palette", ["input-needed", "ws:site", "p1"], body: "Waiting on: palette choice\n\nThe new landing page needs one palette before the copy pass.", hours: 0.4),
            issue(64, "Send the onboarding email on Monday", ["input-needed", "ws:growth"], body: "Waiting on: go/no-go for Monday\n\nDraft is in docs/onboarding-email.md.", hours: 3),
            issue(58, "Port the weekly report to the new template", ["ready", "ws:ops"], body: "Use templates/weekly.md.", hours: 1),
            issue(55, "Fix the broken links in the docs index", ["ready", "p1", "ws:site"], hours: 5),
            issue(49, "Publish Boss to TestFlight", ["on-hold", "ws:app"], body: "Waiting on: Apple developer account\nCheck after: 2026-12-01\n\nNeeds a team ID and an App Store Connect record.", hours: 26),
            issue(41, "Try a dark theme for the reader", ["backlog", "ws:app", "p3"], hours: 72),
            issue(12, "Translate the pricing page", ["ready"], hours: 8, repo: DemoBackend.site, alias: "site", assignees: ["anna"]),
            issue(30, "Move the blog to the new domain", ["ws:site"], hours: 48, state: "closed", reason: "completed"),
            issue(27, "Rewrite the FAQ", [], hours: 96, state: "closed", reason: "not_planned"),
        ]
        comments = [
            "70": [
                Comment(id: 1, author: "brain-agent", body: "Built three variants of the hero section; screenshots in the PR.", createdAt: t(2), url: ""),
                Comment(id: 2, author: "brain-agent", body: """
                \(CARD_MARK)
                ### Which palette should the landing page use?
                Options: Pop | Classic | Mono
                Recommend: Pop, it reads best on phones and keeps the brand orange.

                - **Pop**: orange accents on white, highest contrast.
                - **Classic**: navy and cream, matches the old site.
                - **Mono**: greys only, calm but flat.

                Preview: [variants PR](https://github.com/timur/site/pull/31)
                """, createdAt: t(0.4), url: ""),
            ],
            "64": [
                Comment(id: 3, author: "brain-agent", body: "\(CARD_MARK)\n### Send the onboarding email to all new sign-ups on Monday?\nRecommend: Yes, the draft passed review and the list is clean.\n\nGoes to 412 sign-ups from the last 30 days.", createdAt: t(3), url: ""),
            ],
            "58": [Comment(id: 4, author: "worker-2", body: "Started; first section ported.", createdAt: t(1), url: "")],
        ]
    }

    private func k(_ repo: String, _ n: Int) -> String { taskKey(repo == DemoBackend.home ? "" : repo, n) }

    func list(_ repo: String) -> [Issue] {
        lock.lock(); defer { lock.unlock() }
        let tag = repo == DemoBackend.home ? "" : repo
        return issues.filter { $0.repo == tag }
    }
    func issue(_ repo: String, _ n: Int) throws -> Issue {
        guard let i = list(repo).first(where: { $0.number == n }) else { throw GitHubError(code: 404, message: "Not Found") }
        return i
    }
    func comments(_ repo: String, _ n: Int) -> [Comment] {
        lock.lock(); defer { lock.unlock() }
        return comments[k(repo, n)] ?? []
    }
    func comment(_ repo: String, _ n: Int, _ body: String) {
        lock.lock(); defer { lock.unlock() }
        nextId += 1
        comments[k(repo, n), default: []].append(Comment(id: nextId, author: DemoBackend.me, body: body, createdAt: ISO8601DateFormatter().string(from: Date()), url: ""))
    }
    func set(_ issue: Issue, _ status: String, waitingOn: String?, checkAfter: String?) {
        let c = statusChange(issue, status, waitingOn: waitingOn, checkAfter: checkAfter)
        lock.lock(); defer { lock.unlock() }
        guard let idx = issues.firstIndex(where: { $0.key == issue.key }) else { return }
        issues[idx].body = c.body
        issues[idx].labels = c.labels
        if let s = c.state { issues[idx].state = s }
        issues[idx].stateReason = c.stateReason ?? (c.state == "open" ? "" : issues[idx].stateReason)
        issues[idx].updatedAt = ISO8601DateFormatter().string(from: Date())
    }
}

struct DemoRepo: TaskSource {
    let repo: String
    var alias = ""
    private var b: DemoBackend { .shared }
    func check() async throws {}
    func openIssues() async throws -> [Issue] { b.list(repo).filter { $0.state == "open" } }
    func completedIssues() async throws -> [Issue] { b.list(repo).filter { $0.state == "closed" && $0.stateReason == "completed" } }
    func allIssues() async throws -> [Issue] { b.list(repo) }
    func claims(_ numbers: Set<Int>) async throws -> [Int: String] { repo == DemoBackend.home && numbers.contains(58) ? [58: "worker-2"] : [:] }
    func issue(_ n: Int) async throws -> Issue { try b.issue(repo, n) }
    func comments(_ n: Int) async throws -> [Comment] { b.comments(repo, n) }
    func comment(_ n: Int, body: String) async throws { b.comment(repo, n, body) }
    func setStatus(_ issue: Issue, _ status: String, waitingOn: String?, checkAfter: String?) async throws {
        b.set(issue, status, waitingOn: waitingOn, checkAfter: checkAfter)
    }
    func file(_ path: String) async throws -> RepoFile { RepoFile(text: "", sha: "") }
}
