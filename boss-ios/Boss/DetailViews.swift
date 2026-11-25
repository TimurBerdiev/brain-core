import SwiftUI

struct DetailScreen: View {
    @EnvironmentObject var model: AppModel
    let key: String
    @State private var issue: Issue?
    @State private var comments: [Comment] = []
    @State private var error: String?
    @State private var busy = false
    @State private var moving = false
    @State private var sent: String?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                if let error { Text(error).foregroundStyle(.red).accessibilityIdentifier("detailError") }
                if let issue {
                    header(issue)
                    if let sent { Label(sent, systemImage: "checkmark.circle.fill").foregroundStyle(.green).accessibilityIdentifier("sentBanner") }
                    if let card = currentCard(issue, comments) {
                        DecisionCard(card: card, busy: busy,
                                     onAnswer: { choice, note in act("Answer sent") { try await model.answer(issue, card: card, choice: choice, note: note) } },
                                     onAsk: { text in act("Question sent") { try await model.ask(issue, card: card, text: text) } })
                        .id(card)
                    }
                    if !issue.body.trimmed.isEmpty {
                        Text("Details").font(.headline)
                        MarkdownText(text: stripStatusLines(issue.body))
                    }
                    if !comments.isEmpty {
                        Text("Activity").font(.headline)
                        ForEach(comments.reversed()) { CommentRow(comment: $0) }
                    }
                } else if error == nil { ProgressView().frame(maxWidth: .infinity) }
            }
            .padding()
        }
        .navigationTitle(issue?.ref ?? "")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if let issue {
                ToolbarItemGroup(placement: .topBarTrailing) {
                    if let u = URL(string: issue.url), !model.demo { Link(destination: u) { Image(systemName: "safari") } }
                    Button("Move") { moving = true }.disabled(busy).accessibilityIdentifier("move")
                }
            }
        }
        .sheet(isPresented: $moving) {
            if let issue {
                MoveSheet(issue: issue) { status, waiting, check in
                    moving = false
                    act("Moved to \(Theme.statusName(status))") { try await model.move(issue, to: status, waitingOn: waiting, checkAfter: check) }
                }
            }
        }
        .refreshable { await load() }
        .task { await load() }
    }

    private func header(_ i: Issue) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(i.title).font(.title2.bold()).accessibilityIdentifier("detailTitle")
            HStack(spacing: 8) {
                Text(Theme.statusName(i.status)).font(.caption.bold()).padding(.horizontal, 8).padding(.vertical, 3)
                    .background(Theme.status(i.status).opacity(0.18)).foregroundStyle(Theme.status(i.status)).clipShape(Capsule())
                    .accessibilityIdentifier("detailStatus")
                Text("P\(i.priority)").font(.caption)
                ForEach(i.workstreams, id: \.self) { Text($0).font(.caption).padding(.horizontal, 6).padding(.vertical, 2).background(Color.secondary.opacity(0.12)).clipShape(Capsule()) }
            }
            if !i.assignees.isEmpty { Label(i.assignees.joined(separator: ", "), systemImage: "person").font(.caption).foregroundStyle(.secondary) }
            if i.inProgress { Label("\(i.claim) is working on it", systemImage: "bolt.fill").font(.caption).foregroundStyle(Theme.primary) }
            if !i.waitingOn.isEmpty && i.status != "input-needed" { Label("Waiting on: \(i.waitingOn)", systemImage: "hourglass").font(.caption) }
            if !i.checkAfter.isEmpty { Label("Check after: \(i.checkAfter)", systemImage: "calendar").font(.caption) }
        }
    }

    private func load() async {
        do { (issue, comments) = try await model.load(key); error = nil } catch { self.error = "Couldn't load: \(describe(error))" }
    }

    private func act(_ done: String, _ work: @escaping () async throws -> Void) {
        busy = true
        Task {
            var failure: String?
            do { try await work(); sent = done } catch { failure = describe(error) }
            await load()
            if let failure { self.error = failure }
            busy = false
        }
    }
}

struct CommentRow: View {
    let comment: Comment
    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(comment.author).font(.caption.bold())
                if comment.kind != "log" { Text(comment.kind).font(.caption2).foregroundStyle(comment.kind == "card" ? Theme.decision : Theme.primary) }
                Spacer()
                Text(ago(comment.createdAt)).font(.caption).foregroundStyle(.secondary)
            }
            MarkdownText(text: comment.text).font(.callout)
        }
        .padding(10).background(Color.secondary.opacity(0.08)).clipShape(RoundedRectangle(cornerRadius: 10))
    }
}

struct DecisionCard: View {
    let card: Card
    let busy: Bool
    let onAnswer: (String?, String) -> Void
    let onAsk: (String) -> Void
    @State private var choice: String?
    @State private var note = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Label("Decision", systemImage: "questionmark.bubble.fill").font(.caption.bold()).foregroundStyle(Theme.decision)
            Text(card.question).font(.title3.bold()).accessibilityIdentifier("cardQuestion")
            if !card.recommend.isEmpty {
                Label { Text("Recommend: ").bold() + Text(card.recommend) } icon: { Image(systemName: "star.fill").foregroundStyle(Theme.decision) }
                    .font(.callout).accessibilityIdentifier("cardRecommend")
            }
            if !card.rest.isEmpty { MarkdownText(text: card.rest).font(.callout) }
            FlowChoices(choices: card.choices, selected: $choice, recommended: card.recommends)
            TextField("Add a note (optional)", text: $note, axis: .vertical).textFieldStyle(.roundedBorder).lineLimit(1...4)
                .accessibilityIdentifier("noteField")
            Button { onAnswer(choice, note) } label: {
                HStack {
                    if busy { ProgressView() } else { Image(systemName: "paperplane.fill") }
                    Text(choice.map { "Send “\($0)”" } ?? "Send answer")
                }.frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent).tint(Theme.decision)
            .disabled(busy || (choice == nil && note.trimmed.isEmpty)).accessibilityIdentifier("sendAnswer")
            Button("Ask a question instead") { onAsk(note) }.disabled(busy || note.trimmed.isEmpty).accessibilityIdentifier("askInstead")
        }
        .padding()
        .background(Theme.decision.opacity(0.08))
        .overlay(RoundedRectangle(cornerRadius: 16).stroke(Theme.decision.opacity(0.4)))
        .clipShape(RoundedRectangle(cornerRadius: 16))
        .accessibilityElement(children: .contain).accessibilityIdentifier("decisionCard")
    }
}

struct FlowChoices: View {
    let choices: [String]
    @Binding var selected: String?
    let recommended: (String) -> Bool
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            ForEach(choices, id: \.self) { c in
                Button { selected = selected == c ? nil : c } label: {
                    HStack {
                        Image(systemName: selected == c ? "largecircle.fill.circle" : "circle")
                        Text(c)
                        if recommended(c) { Image(systemName: "star.fill").font(.caption).foregroundStyle(Theme.decision) }
                        Spacer()
                    }.padding(.vertical, 6)
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("choice-\(c)")
                .accessibilityAddTraits(selected == c ? .isSelected : [])
            }
        }
    }
}

struct MoveSheet: View {
    let issue: Issue
    let onMove: (String, String?, String?) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var status = "ready"
    @State private var waiting = ""
    @State private var check = ""

    var body: some View {
        NavigationStack {
            Form {
                Picker("Move to", selection: $status) {
                    ForEach(MOVE_TO, id: \.self) { Text(Theme.statusName($0)).tag($0) }
                }
                .pickerStyle(.inline).accessibilityIdentifier("movePicker")
                if status == "on-hold" {
                    Section("On hold") {
                        TextField("Waiting on", text: $waiting).accessibilityIdentifier("waitingField")
                        TextField("Check after (YYYY-MM-DD)", text: $check).accessibilityIdentifier("checkAfterField")
                    }
                }
            }
            .navigationTitle("Move \(issue.ref)")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Move") { onMove(status, status == "on-hold" ? waiting : nil, status == "on-hold" ? check : nil) }
                        .disabled(status == "on-hold" && waiting.trimmed.isEmpty).accessibilityIdentifier("confirmMove")
                }
            }
            .onAppear {
                status = MOVE_TO.contains(issue.status) ? issue.status : "ready"
                waiting = issue.waitingOn; check = issue.checkAfter
            }
        }
        .presentationDetents([.medium, .large])
    }
}
