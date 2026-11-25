import SwiftUI

@main
struct BossApp: App {
    @StateObject private var model: AppModel = {
        let args = ProcessInfo.processInfo.arguments
        if args.contains("-uiTesting") {
            let d = UserDefaults(suiteName: "boss-ui-tests")!
            d.removePersistentDomain(forName: "boss-ui-tests")
            return AppModel(defaults: d, demo: args.contains("-demo"))
        }
        return AppModel()
    }()

    var body: some Scene {
        WindowGroup {
            RootView().environmentObject(model).tint(Theme.primary)
        }
    }
}

enum Theme {
    static let primary = Color(light: 0x2B4C7E, dark: 0xA9C7FF)
    static let decision = Color(light: 0xB45309, dark: 0xFFB77C)
    static func status(_ s: String) -> Color {
        switch s {
        case "input-needed": return decision
        case "ready": return primary
        case "on-hold": return .purple
        case "backlog": return .gray
        case "done": return .green
        default: return .secondary
        }
    }
    static func statusName(_ s: String) -> String {
        ["input-needed": "Input needed", "ready": "Ready", "on-hold": "On hold", "backlog": "Backlog", "done": "Done", "dropped": "Dropped"][s] ?? s
    }
}

extension Color {
    init(light: UInt32, dark: UInt32) {
        func c(_ v: UInt32) -> UIColor {
            UIColor(red: CGFloat((v >> 16) & 0xFF) / 255, green: CGFloat((v >> 8) & 0xFF) / 255, blue: CGFloat(v & 0xFF) / 255, alpha: 1)
        }
        self = Color(UIColor { $0.userInterfaceStyle == .dark ? c(dark) : c(light) })
    }
}

/// GitHub-flavoured markdown, enough for issue bodies and cards: headings, lists, quotes, code blocks and inline styles.
struct MarkdownText: View {
    let text: String
    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            ForEach(Array(blocks.enumerated()), id: \.offset) { _, b in b }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var blocks: [AnyView] {
        var out: [AnyView] = []
        var code: [String]? = nil
        for line in text.components(separatedBy: "\n") {
            let t = line.trimmingCharacters(in: .whitespaces)
            if t.hasPrefix("```") {
                if let c = code {
                    out.append(AnyView(Text(c.joined(separator: "\n")).font(.system(.footnote, design: .monospaced))
                        .padding(8).frame(maxWidth: .infinity, alignment: .leading).background(Color.secondary.opacity(0.12)).clipShape(RoundedRectangle(cornerRadius: 6))))
                    code = nil
                } else { code = [] }
                continue
            }
            if code != nil { code!.append(line); continue }
            if t.isEmpty || t.hasPrefix("<!--") { continue }
            if let m = t.wholeMatch(of: /(#{1,6})\s+(.*)/) {
                out.append(AnyView(inline(String(m.2)).font(m.1.count <= 2 ? .title3.bold() : .headline)))
            } else if let m = t.wholeMatch(of: /[-*+]\s+(.*)/) {
                out.append(AnyView(HStack(alignment: .firstTextBaseline, spacing: 6) { Text("•"); inline(String(m.1)) }))
            } else if let m = t.wholeMatch(of: /(\d+)[.)]\s+(.*)/) {
                out.append(AnyView(HStack(alignment: .firstTextBaseline, spacing: 6) { Text("\(m.1)."); inline(String(m.2)) }))
            } else if t.hasPrefix(">") {
                out.append(AnyView(HStack(spacing: 8) {
                    Rectangle().fill(Color.secondary.opacity(0.4)).frame(width: 3)
                    inline(String(t.dropFirst()).trimmed).foregroundStyle(.secondary)
                }))
            } else if t.wholeMatch(of: /(-{3,}|\*{3,}|_{3,})/) != nil {
                out.append(AnyView(Divider()))
            } else {
                out.append(AnyView(inline(t)))
            }
        }
        if let c = code { out.append(AnyView(Text(c.joined(separator: "\n")).font(.system(.footnote, design: .monospaced)))) }
        return out
    }

    private func inline(_ s: String) -> Text {
        let linked = s.replacing(/(^|[^(<\]])(https?:\/\/[^\s)>]+)/) { m in "\(m.1)[\(m.2)](\(m.2))" }
        let a = (try? AttributedString(markdown: linked, options: .init(interpretedSyntax: .inlineOnlyPreservingWhitespace))) ?? AttributedString(s)
        return Text(a)
    }
}
