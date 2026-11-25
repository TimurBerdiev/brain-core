import Foundation
import Security
import SwiftUI

/// The token lives in the Keychain, never in UserDefaults or a backup in plain text.
enum Keychain {
    private static let service = "dev.braincore.boss.token"
    static func get() -> String {
        var out: CFTypeRef?
        let q: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecReturnData as String: true]
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let d = out as? Data else { return "" }
        return String(decoding: d, as: UTF8.self)
    }
    static func set(_ token: String) {
        let q: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service]
        SecItemDelete(q as CFDictionary)
        guard !token.isEmpty else { return }
        var add = q
        add[kSecValueData as String] = Data(token.utf8)
        add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
        SecItemAdd(add as CFDictionary, nil)
    }
}

/// App state, the iOS counterpart of boss/…/Store.kt: settings, the cached queue and the GitHub writes.
@MainActor
final class AppModel: ObservableObject {
    private let defaults: UserDefaults
    @Published var repo: String
    @Published var token: String
    @Published var demo: Bool
    @Published var me: String
    @Published var filter: Set<String> { didSet { defaults.set(Array(filter), forKey: "filter") } }
    @Published var showEveryone: Bool { didSet { defaults.set(showEveryone, forKey: "showEveryone") } }
    @Published var issues: [Issue] = []
    @Published var completed: [Issue] = []
    @Published var loading = false
    @Published var error: String?
    @Published var lastSync: Date?
    private var taskRepos: [String: String] = [:]

    init(defaults: UserDefaults = .standard, demo forceDemo: Bool = false) {
        self.defaults = defaults
        repo = defaults.string(forKey: "repo") ?? ""
        token = forceDemo ? "" : Keychain.get()
        demo = forceDemo || defaults.bool(forKey: "demo")
        me = defaults.string(forKey: "me") ?? ""
        filter = Set(defaults.stringArray(forKey: "filter") ?? [])
        showEveryone = defaults.bool(forKey: "showEveryone")
        taskRepos = defaults.dictionary(forKey: "taskRepos") as? [String: String] ?? [:]
        if forceDemo { DemoBackend.shared.reset() }
        if let d = defaults.data(forKey: "issues"), let cached = try? JSONDecoder().decode([Issue].self, from: d), !forceDemo { issues = cached }
    }

    var configured: Bool { demo || (!repo.isEmpty && !token.isEmpty) }

    var sources: [TaskSource] {
        if demo { return [DemoRepo(repo: DemoBackend.home), DemoRepo(repo: DemoBackend.site, alias: "site")] }
        return [GitHubRepo(token: token, repo: repo)] + taskRepos.sorted { $0.key < $1.key }
            .filter { $0.value.caseInsensitiveCompare(repo) != .orderedSame }
            .map { GitHubRepo(token: token, repo: $0.value, tag: $0.value, alias: $0.key) }
    }

    func source(for issue: Issue) -> TaskSource { source(repo: issue.repo) }
    func source(repo tag: String) -> TaskSource {
        let all = sources
        return tag.isEmpty ? all[0] : all.first { $0.repo == tag } ?? all[0]
    }

    func save(repo: String, token: String) {
        self.repo = repo.trimmed.replacing(/^https:\/\/github\.com\//, with: "").replacing(/\.git$|\/$/, with: "")
        self.token = token.trimmed
        demo = false
        defaults.set(self.repo, forKey: "repo")
        defaults.set(false, forKey: "demo")
        Keychain.set(self.token)
        issues = []; completed = []; taskRepos = [:]
    }

    func useDemo() {
        DemoBackend.shared.reset()
        demo = true
        defaults.set(true, forKey: "demo")
        issues = []; completed = []
    }

    func signOut() {
        demo = false; token = ""; repo = ""; issues = []; completed = []
        defaults.set(false, forKey: "demo"); defaults.set("", forKey: "repo")
        Keychain.set("")
    }

    /// Reads workspace.yaml and repos.yaml from home, then every task repo's open issues and claims.
    func refresh() async {
        guard configured, !loading else { return }
        loading = true
        defer { loading = false }
        do {
            if !demo {
                let home = GitHubRepo(token: token, repo: repo)
                if let ws = try? await home.file("workspace.yaml") { me = ownerOf(ws.text); defaults.set(me, forKey: "me") }
                if let reg = try? await home.file("repos.yaml") {
                    taskRepos = taskReposOf(reg.text)
                    defaults.set(taskRepos, forKey: "taskRepos")
                }
            } else { me = DemoBackend.me }
            var open: [Issue] = [], done: [Issue] = []
            var failures: [String] = []
            for (i, src) in sources.enumerated() {
                do {
                    var list = try await src.openIssues()
                    let claims = (try? await src.claims(Set(list.map(\.number)))) ?? [:]
                    for j in list.indices { list[j].claim = claims[list[j].number] ?? "" }
                    open += list
                    done += (try? await src.completedIssues()) ?? []
                } catch {
                    if i == 0 { throw error }
                    failures.append("\(src.repo): \(describe(error))")
                }
            }
            issues = open
            completed = done.sorted { $0.updatedAt > $1.updatedAt }
            error = failures.isEmpty ? nil : "Skipped " + failures.joined(separator: "; ")
            lastSync = Date()
            if !demo { defaults.set(try? JSONEncoder().encode(open), forKey: "issues") }
        } catch {
            self.error = "Couldn't refresh: \(describe(error))"
        }
    }

    /// Every issue in every task repo, for search.
    func allIssues() async throws -> [Issue] {
        var out: [Issue] = []
        for src in sources { out += try await src.allIssues() }
        return out
    }

    func load(_ key: String) async throws -> (Issue, [Comment]) {
        let (tag, n) = splitKey(key)
        let src = source(repo: tag)
        async let i = src.issue(n)
        async let c = src.comments(n)
        var issue = try await i
        issue.claim = issues.first { $0.key == key }?.claim ?? ""
        return (issue, try await c)
    }

    func answer(_ issue: Issue, card: Card, choice: String?, note: String) async throws {
        try await source(for: issue).comment(issue.number, body: answerComment(card.question, choice: choice, note: note))
        try await source(for: issue).setStatus(issue, "ready", waitingOn: nil, checkAfter: nil)
        await refresh()
    }

    func ask(_ issue: Issue, card: Card, text: String) async throws {
        try await source(for: issue).comment(issue.number, body: questionComment(card.question, text))
        try await source(for: issue).setStatus(issue, "ready", waitingOn: nil, checkAfter: nil)
        await refresh()
    }

    func move(_ issue: Issue, to status: String, waitingOn: String?, checkAfter: String?) async throws {
        try await source(for: issue).setStatus(issue, status, waitingOn: waitingOn, checkAfter: checkAfter)
        await refresh()
    }
}
