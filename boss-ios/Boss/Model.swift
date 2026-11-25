import Foundation

// The task system of Brain Core (MANUAL.md, "Tasks"), mirroring boss/…/Model.kt and scripts/task.py.
// The cases in tests/fixtures are checked by FixturesTests, so a rule changed on one side only fails a test.

/// Open statuses, in display order. An open issue without one counts as ready.
let OPEN = ["input-needed", "ready", "on-hold", "backlog"]
/// `in-review` was renamed to `input-needed` (task.py's LEGACY).
let LEGACY = ["in-review": "input-needed"]
/// Statuses the app can move an issue to. Use task.sh or GitHub to set input-needed.
let MOVE_TO = ["ready", "on-hold", "backlog", "done", "dropped"]

let CARD_MARK = "<!-- card -->"
let ANSWER_MARK = "<!-- answer -->"
let QUESTION_MARK = "<!-- question -->"

struct Issue: Codable, Hashable, Identifiable {
    var number: Int
    var title: String
    var body: String
    var labels: [String]
    var state: String
    var updatedAt: String
    var url: String
    /// Who holds a fresh `claim/<n>` branch; empty when nobody is working on it.
    var claim: String = ""
    var stateReason: String = ""
    /// owner/name of the task repo; empty for the home repo.
    var repo: String = ""
    /// The repo's name in repos.yaml.
    var repoAlias: String = ""
    var assignees: [String] = []

    var id: String { key }
    var key: String { taskKey(repo, number) }
    var taskRepoName: String { repoAlias.isEmpty ? String(repo.split(separator: "/").last ?? "") : repoAlias }
    var ref: String { repo.isEmpty ? "#\(number)" : "\(taskRepoName)#\(number)" }
    var inProgress: Bool { state == "open" && !claim.isEmpty }
    var status: String {
        if state == "closed" { return stateReason == "not_planned" ? "dropped" : "done" }
        let names = labels.map { LEGACY[$0] ?? $0 }
        return OPEN.first { names.contains($0) } ?? "ready"
    }
    var waitingOn: String { field(body, "Waiting on") }
    var checkAfter: String { field(body, "Check after") }
    var priority: Int { (1...3).first { labels.contains("p\($0)") } ?? 2 }

    /// Unassigned counts as the owner's, as in task.py.
    func belongsTo(_ me: String) -> Bool {
        assignees.isEmpty || me.isEmpty || assignees.contains { $0.caseInsensitiveCompare(me) == .orderedSame }
    }

    /// Workstreams from `ws:<name>` labels, plus the one a task repo other than home stands for.
    var workstreams: [String] {
        let ws = labels.filter { $0.hasPrefix("ws:") }.map { String($0.dropFirst(3)) }
        let own = taskRepoName.lowercased()
        return repo.isEmpty || ws.contains(own) ? ws : ws + [own]
    }

    func matches(_ filter: Set<String>) -> Bool {
        let only = filter.filter { !$0.hasPrefix("-") }
        let hidden = Set(filter.filter { $0.hasPrefix("-") }.map { String($0.dropFirst()) })
        return (only.isEmpty || workstreams.contains { only.contains($0) }) && !workstreams.contains { hidden.contains($0) }
    }
}

struct Comment: Codable, Hashable, Identifiable {
    var id: Int
    var author: String
    var body: String
    var createdAt: String
    var url: String

    var kind: String {
        let t = body.trimmingLeading()
        if t.hasPrefix(CARD_MARK) { return "card" }
        if t.hasPrefix(ANSWER_MARK) { return "answer" }
        if t.hasPrefix(QUESTION_MARK) { return "question" }
        return "log"
    }

    var text: String {
        var t = body.trimmingLeading()
        for m in [CARD_MARK, ANSWER_MARK, QUESTION_MARK] where t.hasPrefix(m) { t = String(t.dropFirst(m.count)) }
        return t.trimmingCharacters(in: .whitespacesAndNewlines)
    }
}

/// A decision card: the question, the answers to pick from (empty = Yes / No), a recommendation, the rest as markdown.
struct Card: Equatable {
    var question: String
    var options: [String]
    var recommend: String
    var rest: String
    var choices: [String] { options.isEmpty ? ["Yes", "No"] : options }

    /// Whether `Recommend:` names this option ("Pop, it reads best" recommends "Pop").
    func recommends(_ option: String) -> Bool {
        let r = recommend.trimmingCharacters(in: .whitespaces)
        guard r.lowercased().hasPrefix(option.lowercased()) else { return false }
        if r.count == option.count { return true }
        let next = r[r.index(r.startIndex, offsetBy: option.count)]
        return !(next.isLetter || next.isNumber)
    }
}

extension String {
    func trimmingLeading() -> String { String(drop { $0.isWhitespace || $0.isNewline }) }
    var trimmed: String { trimmingCharacters(in: .whitespacesAndNewlines) }
}

private func lines(_ s: String) -> [String] { s.components(separatedBy: "\n") }

/// `Name: value` on a line of its own (not quoted), "" without one.
func field(_ body: String, _ name: String) -> String {
    for line in lines(body) where line.hasPrefix(name + ":") {
        let value = line.dropFirst(name.count + 1).trimmingCharacters(in: .whitespaces)
        if !value.isEmpty { return value }
    }
    return ""
}

func parseCard(_ text: String) -> Card {
    var question = "", recommend = ""
    var options: [String] = []
    var rest: [String] = []
    var body = text.trimmed
    if body.hasPrefix(CARD_MARK) { body = String(body.dropFirst(CARD_MARK.count)).trimmed }
    for line in lines(body) {
        let t = line.trimmingCharacters(in: .whitespaces)
        if question.isEmpty && t.hasPrefix("### ") {
            question = String(t.dropFirst(4)).trimmed
        } else if options.isEmpty && t.hasPrefix("Options:") {
            options = t.dropFirst("Options:".count).split(separator: "|", omittingEmptySubsequences: false)
                .map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
        } else if recommend.isEmpty && t.hasPrefix("Recommend:") {
            recommend = String(t.dropFirst("Recommend:".count)).trimmed
        } else {
            rest.append(line)
        }
    }
    return Card(question: question, options: options, recommend: recommend, rest: rest.joined(separator: "\n").trimmed)
}

/// The card the owner should answer now: the latest card, unless they already answered or asked after it.
func currentCard(_ issue: Issue, _ comments: [Comment]) -> Card? {
    guard issue.status == "input-needed" else { return nil }
    let card = comments.lastIndex { $0.kind == "card" } ?? -1
    let reply = comments.lastIndex { $0.kind == "answer" || $0.kind == "question" } ?? -1
    if card >= 0 && card > reply { return parseCard(comments[card].text) }
    return Card(question: issue.waitingOn.isEmpty ? issue.title : issue.waitingOn, options: [], recommend: "", rest: "")
}

func stripStatusLines(_ body: String) -> String {
    String(body.replacing(/(?m)^(?:Waiting on|Check after):[^\n]*\n?/, with: "").drop { $0 == "\n" })
}

/// The issue body after a status change, as `scripts/task.py set_status` writes it.
func bodyFor(_ body: String, waitingOn: String?, checkAfter: String?) -> String {
    let stripped = stripStatusLines(body)
    guard let w = waitingOn?.trimmed, !w.isEmpty else { return stripped }
    let ca = (checkAfter?.trimmed).flatMap { $0.isEmpty ? nil : "Check after: \($0)\n" } ?? ""
    return "Waiting on: \(w)\n\(ca)\n\(stripped)"
}

/// Labels after a status change: the old status label swapped for the new one (none when closing).
func labelsFor(_ labels: [String], _ status: String) -> [String] {
    let kept = labels.filter { !OPEN.contains($0) && LEGACY[$0] == nil }
    return (OPEN.contains(status) ? kept + [status] : kept).sorted()
}

struct StatusChange: Equatable {
    var body: String
    var labels: [String]
    var state: String? = nil
    var stateReason: String? = nil
}

/// done / dropped close as completed / not_planned, an open status reopens.
func statusChange(_ issue: Issue, _ status: String, waitingOn: String? = nil, checkAfter: String? = nil) -> StatusChange {
    let body = bodyFor(issue.body, waitingOn: waitingOn, checkAfter: checkAfter)
    let labels = labelsFor(issue.labels, status)
    if status == "done" || status == "dropped" {
        return issue.state == "open"
            ? StatusChange(body: body, labels: labels, state: "closed", stateReason: status == "done" ? "completed" : "not_planned")
            : StatusChange(body: body, labels: labels)
    }
    if issue.state == "closed" { return StatusChange(body: body, labels: labels, state: "open") }
    return StatusChange(body: body, labels: labels)
}

private func quote(_ q: String) -> String { lines(q).map { "> \($0)" }.joined(separator: "\n") }

func answerComment(_ question: String, choice: String?, note: String) -> String {
    var s = ANSWER_MARK + "\n" + quote(question) + "\n\n"
    if let c = choice, !c.trimmed.isEmpty { s += "**\(c)**\n\n" }
    if !note.trimmed.isEmpty { s += note.trimmed + "\n" }
    return s.trimmed
}

func questionComment(_ question: String, _ text: String) -> String { "\(QUESTION_MARK)\n\(quote(question))\n\n\(text.trimmed)" }

func taskKey(_ repo: String, _ number: Int) -> String { repo.isEmpty ? "\(number)" : "\(repo)#\(number)" }

func splitKey(_ key: String) -> (repo: String, number: Int) {
    guard let i = key.lastIndex(of: "#") else { return ("", Int(key) ?? 0) }
    return (String(key[..<i]), Int(key[key.index(after: i)...]) ?? 0)
}

let STALE_HOURS = 2.0

/// The holder named by a claim branch's tip commit ("claim #N by <id>"), or "" if released or stale.
func claimHolder(_ message: String, ageHours: Double) -> String {
    guard let m = message.firstMatch(of: /^claim #\d+ by (\S+)/) else { return "" }
    return ageHours > STALE_HOURS ? "" : String(m.1)
}

// MARK: workstream filter (same format as `filter:` in workers.yaml)

func workstreamsOf(_ issues: [Issue], _ filter: Set<String>) -> [String] {
    Array(Set(issues.flatMap(\.workstreams) + filter.map { $0.hasPrefix("-") ? String($0.dropFirst()) : $0 })).sorted()
}

enum Pick { case off, only, except }

func pickOf(_ filter: Set<String>, _ ws: String) -> Pick {
    filter.contains(ws) ? .only : filter.contains("-" + ws) ? .except : .off
}

/// A tap on a workstream chip: off -> only -> except -> off.
func cycle(_ filter: Set<String>, _ ws: String) -> Set<String> {
    switch pickOf(filter, ws) {
    case .off: return filter.union([ws])
    case .only: return filter.subtracting([ws]).union(["-" + ws])
    case .except: return filter.subtracting(["-" + ws])
    }
}

func describeFilter(_ filter: Set<String>) -> String {
    let only = filter.filter { !$0.hasPrefix("-") }.sorted()
    let hidden = filter.filter { $0.hasPrefix("-") }.map { String($0.dropFirst()) }.sorted()
    switch (only.isEmpty, hidden.isEmpty) {
    case (true, true): return "All"
    case (true, false): return "All except " + hidden.joined(separator: ", ")
    case (false, true): return only.joined(separator: ", ")
    default: return only.joined(separator: ", ") + ", not " + hidden.joined(separator: ", ")
    }
}

/// The dispatcher's filter from workers.yaml (`filter: [app, "-site"]`), or nil without that line.
func dispatcherFilter(_ yaml: String) -> Set<String>? {
    for line in lines(yaml) {
        guard let m = line.wholeMatch(of: /filter:[ \t]*\[([^\]\n]*)\][ \t]*(#.*)?/) else { continue }
        let terms = m.1.split(whereSeparator: { $0 == "," || $0.isWhitespace })
            .map { $0.trimmingCharacters(in: CharacterSet(charactersIn: "\"'")).lowercased() }.filter { !$0.isEmpty }
        return terms == ["all"] ? [] : Set(terms)
    }
    return nil
}

func visibleIssues(_ issues: [Issue], _ filter: Set<String>, me: String, showEveryone: Bool) -> [Issue] {
    issues.filter { $0.matches(filter) && (showEveryone || $0.belongsTo(me)) }
}

/// Issues grouped by status, most recently updated first. Someone else's input-needed card is listed under on-hold.
func grouped(_ issues: [Issue], me: String = "") -> [(String, [Issue])] {
    func section(_ i: Issue) -> String { i.status == "input-needed" && !i.belongsTo(me) ? "on-hold" : i.status }
    let sorted = issues.sorted {
        if $0.updatedAt != $1.updatedAt { return $0.updatedAt > $1.updatedAt }
        if $0.inProgress != $1.inProgress { return $0.inProgress }
        if $0.priority != $1.priority { return $0.priority < $1.priority }
        return $0.number < $1.number
    }
    return OPEN.map { st in (st, sorted.filter { section($0) == st }) }
}

/// Search every status and assignee: exact numbers first, then title, then body matches.
func searchIssues(_ issues: [Issue], _ query: String) -> [Issue] {
    let text = query.trimmed
    let qualified = text.wholeMatch(of: /([A-Za-z0-9._-]+(?:\/[A-Za-z0-9._-]+)?)#(\d+)/)
    let digitsPart = text.hasPrefix("#") ? String(text.dropFirst()) : text
    let digits: String? = !digitsPart.isEmpty && digitsPart.allSatisfy(\.isNumber) ? digitsPart : nil
    let numberOnly = text.hasPrefix("#") && digits != nil
    func rank(_ i: Issue) -> Int {
        if text.isEmpty { return 0 }
        if let q = qualified {
            let name = String(q.1)
            return i.number == Int(q.2) && !i.repo.isEmpty &&
                (i.taskRepoName.caseInsensitiveCompare(name) == .orderedSame || i.repo.caseInsensitiveCompare(name) == .orderedSame) ? 0 : 4
        }
        if let d = digits, String(i.number) == d { return 0 }
        if let d = digits, String(i.number).contains(d) { return 1 }
        if !numberOnly && i.title.localizedCaseInsensitiveContains(text) { return 2 }
        if !numberOnly && i.body.localizedCaseInsensitiveContains(text) { return 3 }
        return 4
    }
    var seen = Set<String>()
    return issues.filter { seen.insert($0.key).inserted }.map { ($0, rank($0)) }.filter { $0.1 < 4 }
        .sorted { a, b in
            if a.1 != b.1 { return a.1 < b.1 }
            if a.0.updatedAt != b.0.updatedAt { return a.0.updatedAt > b.0.updatedAt }
            return a.0.number > b.0.number
        }.map(\.0)
}

// MARK: registry files

/// The task repos besides home from repos.yaml: name -> owner/name, for entries with `tasks: true` or `tasks: assigned`.
/// A small reader for the registry's block-mapping layout (`repos:` → `<name>:` → `remote:` / `tasks:`).
func taskReposOf(_ yaml: String) -> [String: String] {
    var out: [String: String] = [:]
    var inRepos = false, reposIndent = -1, entryIndent = -1
    var name: String?, remote: String?, tasks: String?
    func flush() {
        if let n = name, let r = remote, tasks == "true" || tasks == "assigned",
           let m = r.firstMatch(of: /github\.com[:\/]([^\/]+\/[^\/]+?)(?:\.git)?\/?$/) {
            out[n] = String(m.1)
        }
        name = nil; remote = nil; tasks = nil
    }
    for raw in lines(yaml) {
        let noComment = raw.replacing(/\s+#.*$/, with: "")
        guard !noComment.trimmed.isEmpty, !noComment.trimmed.hasPrefix("#") else { continue }
        let indent = noComment.prefix { $0 == " " }.count
        let t = noComment.trimmed
        if indent == 0 {
            flush()
            inRepos = t == "repos:"
            reposIndent = -1; entryIndent = -1
            continue
        }
        guard inRepos, let colon = t.firstIndex(of: ":") else { continue }
        let k = String(t[..<colon]).trimmed.trimmingCharacters(in: CharacterSet(charactersIn: "\"'"))
        let v = String(t[t.index(after: colon)...]).trimmed.trimmingCharacters(in: CharacterSet(charactersIn: "\"'"))
        if reposIndent < 0 || indent <= reposIndent { flush(); reposIndent = indent; name = k; entryIndent = -1; continue }
        if entryIndent < 0 { entryIndent = indent }
        if indent == entryIndent {
            if k == "remote" { remote = v }
            if k == "tasks" { tasks = v.lowercased() }
        }
    }
    flush()
    return out
}

/// The owner's GitHub login from workspace.yaml (`github:`), "" without one.
func ownerOf(_ yaml: String) -> String {
    for line in lines(yaml) where line.hasPrefix("github:") {
        return String(line.dropFirst(7)).replacing(/\s+#.*$/, with: "").trimmed.trimmingCharacters(in: CharacterSet(charactersIn: "\"'"))
    }
    return ""
}
