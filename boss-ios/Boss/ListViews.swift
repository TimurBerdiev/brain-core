import SwiftUI

struct RootView: View {
    @EnvironmentObject var model: AppModel
    var body: some View {
        NavigationStack {
            if model.configured { ListScreen() } else { SettingsScreen(firstRun: true) }
        }
    }
}

struct ListScreen: View {
    @EnvironmentObject var model: AppModel
    @State private var showCompleted = false
    @State private var showOnHold = true
    @State private var showBacklog = false

    private var visible: [Issue] { visibleIssues(model.issues, model.filter, me: model.me, showEveryone: model.showEveryone) }
    private var groups: [String: [Issue]] { Dictionary(uniqueKeysWithValues: grouped(visible, me: model.me)) }
    private var decisions: Int { groups["input-needed"]?.count ?? 0 }

    var body: some View {
        List {
            Section {
                Text(decisions == 0 ? "Nothing waits on you" : decisions == 1 ? "1 decision waits on you" : "\(decisions) decisions wait on you")
                    .font(.headline).foregroundStyle(decisions > 0 ? Theme.decision : .secondary)
                    .accessibilityIdentifier("decisionCount")
                if let e = model.error { Text(e).font(.footnote).foregroundStyle(.red).accessibilityIdentifier("syncError") }
                FilterChips()
                Toggle("Show everyone's tasks", isOn: $model.showEveryone).accessibilityIdentifier("showEveryone")
            }
            TaskSection(title: "For you", issues: groups["input-needed"] ?? [], empty: "No decisions waiting")
            TaskSection(title: "Agents", issues: groups["ready"] ?? [], empty: "No tasks ready for agents")
            CollapsibleSection(title: "On hold", issues: groups["on-hold"] ?? [], expanded: $showOnHold)
            CollapsibleSection(title: "Backlog", issues: groups["backlog"] ?? [], expanded: $showBacklog)
            CollapsibleSection(title: "Completed", issues: model.completed.filter { $0.matches(model.filter) && (model.showEveryone || $0.belongsTo(model.me)) }, expanded: $showCompleted)
        }
        .listStyle(.insetGrouped)
        .navigationTitle("Boss")
        .navigationDestination(for: String.self) { DetailScreen(key: $0) }
        .toolbar {
            ToolbarItemGroup(placement: .topBarTrailing) {
                NavigationLink { SearchScreen() } label: { Image(systemName: "magnifyingglass") }.accessibilityIdentifier("search")
                Button { Task { await model.refresh() } } label: {
                    if model.loading { ProgressView() } else { Image(systemName: "arrow.clockwise") }
                }.accessibilityIdentifier("refresh")
                NavigationLink { SettingsScreen(firstRun: false) } label: { Image(systemName: "gearshape") }.accessibilityIdentifier("settings")
            }
        }
        .refreshable { await model.refresh() }
        .task { await model.refresh() }
    }
}

struct FilterChips: View {
    @EnvironmentObject var model: AppModel
    var body: some View {
        let all = workstreamsOf(model.issues, model.filter)
        if !all.isEmpty {
            VStack(alignment: .leading, spacing: 6) {
                Text("Workstreams: \(describeFilter(model.filter))").font(.caption).foregroundStyle(.secondary).accessibilityIdentifier("filterSummary")
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack {
                        ForEach(all, id: \.self) { ws in
                            let pick = pickOf(model.filter, ws)
                            Button { model.filter = cycle(model.filter, ws) } label: {
                                Label(ws, systemImage: pick == .only ? "checkmark" : pick == .except ? "minus" : "circle.dashed")
                                    .font(.subheadline).strikethrough(pick == .except)
                            }
                            .buttonStyle(.bordered).tint(pick == .only ? Theme.primary : pick == .except ? .red : .secondary)
                            .accessibilityIdentifier("chip-\(ws)")
                        }
                    }
                }
            }
        }
    }
}

struct TaskSection: View {
    let title: String
    let issues: [Issue]
    let empty: String
    var body: some View {
        Section {
            if issues.isEmpty { Text(empty).foregroundStyle(.secondary) }
            ForEach(issues) { TaskRow(issue: $0) }
        } header: { Text("\(title) · \(issues.count)") }
    }
}

struct CollapsibleSection: View {
    let title: String
    let issues: [Issue]
    @Binding var expanded: Bool
    var body: some View {
        if !issues.isEmpty {
            Section(isExpanded: $expanded) {
                ForEach(issues) { TaskRow(issue: $0) }
            } header: { Text("\(title) · \(issues.count)") }
        }
    }
}

struct TaskRow: View {
    let issue: Issue
    var body: some View {
        NavigationLink(value: issue.key) {
            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 6) {
                    Circle().fill(Theme.status(issue.status)).frame(width: 8, height: 8)
                    Text(issue.title).font(.body).lineLimit(2)
                }
                HStack(spacing: 8) {
                    Text(issue.ref).monospacedDigit()
                    if issue.priority != 2 { Text("P\(issue.priority)").bold().foregroundStyle(issue.priority == 1 ? .red : .secondary) }
                    if issue.inProgress { Label(issue.claim, systemImage: "bolt.fill").foregroundStyle(Theme.primary) }
                    if !issue.waitingOn.isEmpty && issue.status != "input-needed" { Text(issue.waitingOn).lineLimit(1) }
                    Spacer()
                    Text(ago(issue.updatedAt))
                }
                .font(.caption).foregroundStyle(.secondary)
            }
        }
        .accessibilityIdentifier("task-\(issue.key)")
    }
}

struct SearchScreen: View {
    @EnvironmentObject var model: AppModel
    @State private var query = ""
    @State private var all: [Issue] = []
    @State private var error: String?
    @State private var loading = true

    var body: some View {
        List {
            if let error { Text(error).foregroundStyle(.red) }
            if loading && all.isEmpty { ProgressView() }
            let found = searchIssues(all, query)
            if !query.trimmed.isEmpty && found.isEmpty && !loading { Text("No matches").foregroundStyle(.secondary) }
            ForEach(query.trimmed.isEmpty ? [] : found) { i in
                VStack(alignment: .leading) {
                    TaskRow(issue: i)
                    Text(Theme.statusName(i.status)).font(.caption2).foregroundStyle(Theme.status(i.status))
                }
            }
        }
        .navigationTitle("Search")
        .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always), prompt: "#70, site#38 or words")
        .task {
            do { all = try await model.allIssues() } catch { self.error = describe(error); all = model.issues + model.completed }
            loading = false
        }
    }
}

struct SettingsScreen: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) private var dismiss
    let firstRun: Bool
    @State private var repo = ""
    @State private var token = ""
    @State private var checking = false
    @State private var result: String?

    var body: some View {
        Form {
            Section {
                Text("Boss reads tasks from your Brain repository and the task repositories it lists in repos.yaml.")
                TextField("owner/brain", text: $repo).textInputAutocapitalization(.never).autocorrectionDisabled().accessibilityIdentifier("repoField")
                SecureField("GitHub token", text: $token).accessibilityIdentifier("tokenField")
                Text("Create a fine-grained token at github.com/settings/personal-access-tokens covering the workspace and its task repositories, with Issues: read and write, and Contents: read.")
                    .font(.footnote).foregroundStyle(.secondary)
            } header: { Text("Brain repository") }
            Section {
                Button("Save") {
                    model.save(repo: repo, token: token)
                    Task { await model.refresh() }
                    if !firstRun { dismiss() }
                }.disabled(repo.trimmed.isEmpty || token.trimmed.isEmpty).accessibilityIdentifier("save")
                Button(checking ? "Checking…" : "Check access") {
                    checking = true
                    Task {
                        do { try await GitHubRepo(token: token.trimmed, repo: repo.trimmed).check(); result = "Issues: OK" }
                        catch { result = "No access: \(describe(error))" }
                        checking = false
                    }
                }.disabled(repo.trimmed.isEmpty || token.trimmed.isEmpty || checking)
                if let result { Text(result).font(.footnote) }
            }
            Section {
                Button("Try with sample tasks") { model.useDemo(); if !firstRun { dismiss() } }.accessibilityIdentifier("tryDemo")
                if model.configured && !firstRun { Button("Sign out", role: .destructive) { model.signOut() } }
            } footer: {
                Text(model.demo ? "Showing sample tasks; nothing is sent to GitHub." : "Sample tasks stay on this phone; nothing is sent to GitHub.")
            }
        }
        .navigationTitle(firstRun ? "Welcome to Boss" : "Settings")
        .onAppear { repo = model.repo; token = model.token }
    }
}
