import XCTest
@testable import Boss

/// The task format cases shared with scripts/task.py and the Android app (tests/fixtures/*.json).
final class FixturesTests: XCTestCase {
    private func cases(_ name: String, _ key: String = "cases") throws -> [[String: Any]] {
        let dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent().appendingPathComponent("../../tests/fixtures")
        let o = try JSONSerialization.jsonObject(with: Data(contentsOf: dir.appendingPathComponent("\(name).json"))) as! [String: Any]
        return o[key] as! [[String: Any]]
    }
    private func issue(_ labels: [String], _ body: String = "", _ state: String = "open", _ reason: String = "") -> Issue {
        Issue(number: 1, title: "t", body: body, labels: labels, state: state, updatedAt: "", url: "", stateReason: reason)
    }

    func testCards() throws {
        for c in try cases("cards") {
            let name = c["name"] as! String
            let card = parseCard(Comment(id: 1, author: "a", body: CARD_MARK + "\n" + (c["card"] as! [String]).joined(separator: "\n"), createdAt: "", url: "").text)
            XCTAssertEqual(card.question, c["question"] as? String, name)
            XCTAssertEqual(card.options, c["options"] as? [String], name)
            XCTAssertEqual(card.recommend, c["recommend"] as? String, name)
        }
    }

    func testStatus() throws {
        for c in try cases("status") {
            XCTAssertEqual(issue(c["labels"] as! [String]).status, c["status"] as? String, "\(c["labels"]!)")
        }
    }

    func testFields() throws {
        for c in try cases("status", "fields") {
            let i = issue([], c["body"] as! String)
            XCTAssertEqual(i.waitingOn, c["waiting_on"] as? String, c["body"] as! String)
            XCTAssertEqual(i.checkAfter, c["check_after"] as? String, c["body"] as! String)
        }
    }

    func testTransitions() throws {
        for c in try cases("transitions") {
            let name = c["name"] as! String
            let b = c["before"] as! [String: Any]
            let before = issue(b["labels"] as! [String], b["body"] as! String, b["state"] as! String, b["state_reason"] as? String ?? "")
            let change = statusChange(before, c["to"] as! String, waitingOn: c["waiting_on"] as? String, checkAfter: c["check_after"] as? String)
            let want = c["after"] as! [String: Any]
            let state = change.state ?? before.state
            XCTAssertEqual(change.body, want["body"] as? String, name)
            XCTAssertEqual(change.labels, want["labels"] as? [String], name)
            XCTAssertEqual(state, want["state"] as? String, name)
            if state == "closed" { XCTAssertEqual(change.stateReason ?? before.stateReason, want["state_reason"] as? String, name) }
            let after = issue(change.labels, change.body, state, change.stateReason ?? before.stateReason)
            XCTAssertEqual(after.status, c["to"] as? String, name)
        }
    }

    func testClaims() throws {
        for c in try cases("claims") {
            XCTAssertEqual(claimHolder(c["subject"] as! String, ageHours: (c["age_hours"] as! NSNumber).doubleValue), c["holder"] as? String, c["subject"] as! String)
        }
    }

    func testFilterLines() throws {
        for c in try cases("filter", "lines") {
            let want = (c["terms"] as? [String]).map(Set.init)
            XCTAssertEqual(dispatcherFilter(c["yaml"] as! String), want, c["yaml"] as! String)
        }
    }

    func testFilterMatches() throws {
        for c in try cases("filter", "matches") {
            var i = issue(c["labels"] as! [String])
            if let repo = c["repo"] as? String { i.repo = "owner/\(repo)"; i.repoAlias = repo }
            XCTAssertEqual(i.matches(Set(c["filter"] as! [String])), c["keep"] as? Bool, "\(c)")
        }
    }

    func testFilterDescribe() throws {
        for c in try cases("filter", "describe") {
            XCTAssertEqual(describeFilter(Set(c["filter"] as! [String])), c["text"] as? String, "\(c["filter"]!)")
        }
    }
}
