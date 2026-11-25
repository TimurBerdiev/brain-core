import XCTest
@testable import Boss

final class ModelTests: XCTestCase {
    private func issue(_ n: Int, _ title: String, labels: [String] = [], body: String = "", updated: String = "2026-01-01T00:00:00Z", repo: String = "", alias: String = "", assignees: [String] = []) -> Issue {
        Issue(number: n, title: title, body: body, labels: labels, state: "open", updatedAt: updated, url: "", repo: repo, repoAlias: alias, assignees: assignees)
    }
    private func comment(_ body: String) -> Comment { Comment(id: 0, author: "a", body: body, createdAt: "", url: "") }

    func testTaskReposFromRegistry() {
        let yaml = """
        defaults:
          account: timur
        repos:
          site:
            remote: https://github.com/timur/site.git
            tasks: true   # its issues are tasks
          shared:
            remote: git@github.com:team/shared.git
            tasks: assigned
          lib:
            remote: https://github.com/timur/lib.git
            notes: "tasks: true is not this"
        """
        XCTAssertEqual(taskReposOf(yaml), ["site": "timur/site", "shared": "team/shared"])
        XCTAssertEqual(taskReposOf("repos: {}\n"), [:])
    }

    func testOwner() {
        XCTAssertEqual(ownerOf("owner: Timur\ngithub: TimurBerdiev   # login\n"), "TimurBerdiev")
        XCTAssertEqual(ownerOf("owner: x\n"), "")
    }

    func testCurrentCardFollowsLatestReply() {
        let i = issue(1, "Pick", labels: ["input-needed"], body: "Waiting on: a palette\n")
        let card = comment("\(CARD_MARK)\n### Which one?\nOptions: A | B\nRecommend: B")
        XCTAssertEqual(currentCard(i, [card])?.question, "Which one?")
        XCTAssertEqual(currentCard(i, [card])?.choices, ["A", "B"])
        XCTAssertEqual(currentCard(i, [card, comment("\(ANSWER_MARK)\nA")])?.question, "a palette")
        XCTAssertNil(currentCard(issue(1, "x", labels: ["ready"]), [card]))
        XCTAssertEqual(parseCard("### Go?").choices, ["Yes", "No"])
    }

    func testRecommends() {
        let c = Card(question: "", options: ["Pop", "Popular"], recommend: "Pop, it reads best", rest: "")
        XCTAssertTrue(c.recommends("Pop"))
        XCTAssertFalse(c.recommends("Popular"))
    }

    func testAnswerAndQuestionComments() {
        XCTAssertEqual(answerComment("Which?\nReally?", choice: "B", note: " soon "), "\(ANSWER_MARK)\n> Which?\n> Really?\n\n**B**\n\nsoon")
        XCTAssertEqual(answerComment("Which?", choice: nil, note: "neither"), "\(ANSWER_MARK)\n> Which?\n\nneither")
        XCTAssertEqual(questionComment("Which?", "why B?"), "\(QUESTION_MARK)\n> Which?\n\nwhy B?")
    }

    func testGroupingAndOwnership() {
        let mine = issue(1, "a", labels: ["input-needed"])
        let theirs = issue(2, "b", labels: ["input-needed"], assignees: ["anna"])
        let g = Dictionary(uniqueKeysWithValues: grouped([mine, theirs], me: "timur"))
        XCTAssertEqual(g["input-needed"]?.map(\.number), [1])
        XCTAssertEqual(g["on-hold"]?.map(\.number), [2])
        XCTAssertEqual(visibleIssues([mine, theirs], [], me: "timur", showEveryone: false).map(\.number), [1])
    }

    func testSearch() {
        let all = [issue(70, "Palette", updated: "2026-01-02T00:00:00Z"), issue(7, "Docs", body: "palette notes"),
                   issue(38, "Pricing", repo: "timur/site", alias: "site")]
        XCTAssertEqual(searchIssues(all, "#70").map(\.number), [70])
        XCTAssertEqual(searchIssues(all, "palette").map(\.number), [70, 7])
        XCTAssertEqual(searchIssues(all, "site#38").map(\.number), [38])
        XCTAssertEqual(searchIssues(all, "7").map(\.number), [7, 70])
    }

    func testCycle() {
        var f: Set<String> = []
        f = cycle(f, "app"); XCTAssertEqual(f, ["app"])
        f = cycle(f, "app"); XCTAssertEqual(f, ["-app"])
        f = cycle(f, "app"); XCTAssertEqual(f, [])
    }
}
