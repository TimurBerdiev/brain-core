import Foundation

struct GitHubError: LocalizedError {
    var code: Int
    var message: String
    var rateLimited = false
    var errorDescription: String? { "GitHub \(code)\(message.isEmpty ? "" : ": \(message)")" }
}

struct RepoFile { var text: String; var sha: String }

/// What the app needs from GitHub for one task repo. `GitHubRepo` talks to the REST API; `DemoRepo` serves sample tasks.
protocol TaskSource: Sendable {
    var repo: String { get }
    func check() async throws
    func openIssues() async throws -> [Issue]
    func completedIssues() async throws -> [Issue]
    func allIssues() async throws -> [Issue]
    func claims(_ numbers: Set<Int>) async throws -> [Int: String]
    func issue(_ n: Int) async throws -> Issue
    func comments(_ n: Int) async throws -> [Comment]
    func comment(_ n: Int, body: String) async throws
    func setStatus(_ issue: Issue, _ status: String, waitingOn: String?, checkAfter: String?) async throws
    func file(_ path: String) async throws -> RepoFile
}

/// GitHub REST for one repo with a fine-grained token (Issues: read/write; Contents: read).
/// `tag` is what its issues carry as `Issue.repo`: empty for home, owner/name for another task repo.
struct GitHubRepo: TaskSource {
    let token: String
    let repo: String
    var tag = ""
    var alias = ""
    var session: URLSession = .shared

    private func call(_ method: String, _ path: String, body: [String: Any]? = nil) async throws -> Data {
        let url = URL(string: path.hasPrefix("https://") ? path : "https://api.github.com/repos/\(repo)/\(path)")!
        var req = URLRequest(url: url, timeoutInterval: 30)
        req.httpMethod = method
        req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        req.setValue("application/vnd.github+json", forHTTPHeaderField: "Accept")
        req.setValue("2022-11-28", forHTTPHeaderField: "X-GitHub-Api-Version")
        if let body {
            req.httpBody = try JSONSerialization.data(withJSONObject: body)
            req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }
        let (data, response) = try await session.data(for: req)
        let http = response as! HTTPURLResponse
        guard (200..<300).contains(http.statusCode) else {
            let msg = ((try? JSONSerialization.jsonObject(with: data)) as? [String: Any])?["message"] as? String ?? ""
            let signal = http.value(forHTTPHeaderField: "X-RateLimit-Remaining") == "0" ||
                http.value(forHTTPHeaderField: "Retry-After") != nil || msg.localizedCaseInsensitiveContains("rate limit")
            throw GitHubError(code: http.statusCode, message: msg, rateLimited: http.statusCode == 429 || (http.statusCode == 403 && signal))
        }
        return data
    }

    private func pages(_ path: String) async throws -> [[String: Any]] {
        var out: [[String: Any]] = []
        for page in 1... {
            let sep = path.contains("?") ? "&" : "?"
            let arr = try JSONSerialization.jsonObject(with: try await call("GET", "\(path)\(sep)per_page=100&page=\(page)")) as? [[String: Any]] ?? []
            out += arr
            if arr.count < 100 { break }
        }
        return out
    }

    private func tagged(_ o: [String: Any]) -> Issue {
        var i = GitHubRepo.issue(o)
        i.repo = tag; i.repoAlias = alias
        return i
    }

    func check() async throws { _ = try await call("GET", "issues?per_page=1") }

    func openIssues() async throws -> [Issue] {
        try await pages("issues?state=open").filter { $0["pull_request"] == nil }.map(tagged)
    }

    func completedIssues() async throws -> [Issue] {
        try await pages("issues?state=closed&sort=updated&direction=desc")
            .filter { $0["pull_request"] == nil && $0["state_reason"] as? String == "completed" }.map(tagged)
    }

    func allIssues() async throws -> [Issue] {
        try await pages("issues?state=all&sort=updated&direction=desc").filter { $0["pull_request"] == nil }.map(tagged)
    }

    func claims(_ numbers: Set<Int>) async throws -> [Int: String] {
        let refs = try JSONSerialization.jsonObject(with: try await call("GET", "git/matching-refs/heads/claim")) as? [[String: Any]] ?? []
        var out: [Int: String] = [:]
        for ref in refs {
            guard let name = ref["ref"] as? String, let n = Int(name.split(separator: "/").last ?? ""), numbers.contains(n),
                  let sha = (ref["object"] as? [String: Any])?["sha"] as? String else { continue }
            let commit = try JSONSerialization.jsonObject(with: try await call("GET", "git/commits/\(sha)")) as? [String: Any] ?? [:]
            let date = ((commit["committer"] as? [String: Any])?["date"] as? String).flatMap(parseDate) ?? .distantPast
            let holder = claimHolder(commit["message"] as? String ?? "", ageHours: Date().timeIntervalSince(date) / 3600)
            if !holder.isEmpty { out[n] = holder }
        }
        return out
    }

    func issue(_ n: Int) async throws -> Issue {
        tagged(try JSONSerialization.jsonObject(with: try await call("GET", "issues/\(n)")) as? [String: Any] ?? [:])
    }

    func comments(_ n: Int) async throws -> [Comment] {
        try await pages("issues/\(n)/comments").map {
            Comment(id: $0["id"] as? Int ?? 0, author: ($0["user"] as? [String: Any])?["login"] as? String ?? "",
                    body: $0["body"] as? String ?? "", createdAt: $0["created_at"] as? String ?? "", url: $0["html_url"] as? String ?? "")
        }
    }

    func comment(_ n: Int, body: String) async throws { _ = try await call("POST", "issues/\(n)/comments", body: ["body": body]) }

    /// Same effect as `scripts/task.sh set <n> <status>`.
    func setStatus(_ issue: Issue, _ status: String, waitingOn: String?, checkAfter: String?) async throws {
        // Start from GitHub's current body and labels so edits made since the screen loaded are kept,
        // and leave a task someone closed meanwhile closed rather than reopening it.
        let current = try await self.issue(issue.number)
        if current.state == "closed" && issue.state == "open" && OPEN.contains(status) {
            throw GitHubError(code: 409, message: "#\(issue.number) was closed on GitHub meanwhile, so it stays closed. Refresh to see it.")
        }
        let change = statusChange(current, status, waitingOn: waitingOn, checkAfter: checkAfter)
        var patch: [String: Any] = ["body": change.body, "labels": change.labels]
        if let s = change.state { patch["state"] = s }
        if let r = change.stateReason { patch["state_reason"] = r }
        _ = try await call("PATCH", "issues/\(issue.number)", body: patch)
    }

    func file(_ path: String) async throws -> RepoFile {
        let o = try JSONSerialization.jsonObject(with: try await call("GET", "contents/\(path)")) as? [String: Any] ?? [:]
        let b64 = (o["content"] as? String ?? "").replacingOccurrences(of: "\n", with: "")
        return RepoFile(text: String(decoding: Data(base64Encoded: b64) ?? Data(), as: UTF8.self), sha: o["sha"] as? String ?? "")
    }

    static func issue(_ o: [String: Any]) -> Issue {
        func s(_ k: String) -> String { o[k] as? String ?? "" }
        return Issue(
            number: o["number"] as? Int ?? 0, title: s("title"), body: s("body"),
            labels: (o["labels"] as? [[String: Any]] ?? []).compactMap { $0["name"] as? String },
            state: s("state"), updatedAt: s("updated_at"), url: s("html_url"), stateReason: s("state_reason"),
            assignees: (o["assignees"] as? [[String: Any]] ?? []).compactMap { $0["login"] as? String }.filter { !$0.isEmpty })
    }
}

func parseDate(_ iso: String) -> Date? {
    let f = ISO8601DateFormatter()
    if let d = f.date(from: iso) { return d }
    f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
    return f.date(from: iso)
}

func ago(_ iso: String) -> String {
    guard let d = parseDate(iso) else { return iso }
    let f = RelativeDateTimeFormatter()
    f.unitsStyle = .short
    return f.localizedString(for: d, relativeTo: Date())
}

/// A short reason for a failure: "offline" for network trouble rather than a raw URL error.
func describe(_ e: Error) -> String {
    if let u = e as? URLError, [.notConnectedToInternet, .cannotFindHost, .cannotConnectToHost, .timedOut, .networkConnectionLost, .dnsLookupFailed].contains(u.code) {
        return "offline"
    }
    return (e as? LocalizedError)?.errorDescription ?? e.localizedDescription
}
