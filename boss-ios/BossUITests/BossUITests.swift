import XCTest

/// End-to-end flows against the built-in sample tasks (no network): the queue, a decision card, a move, search and settings.
/// Screenshots are attached to the test results (kept) so a run doubles as evidence of the main screens.
final class BossUITests: XCTestCase {
    private var app: XCUIApplication!

    override func setUp() {
        continueAfterFailure = false
        app = XCUIApplication()
        app.launchArguments = ["-uiTesting", "-demo"]
        app.launch()
    }

    private func shot(_ name: String) {
        let a = XCTAttachment(screenshot: app.screenshot())
        a.name = name
        a.lifetime = .keepAlways
        add(a)
    }

    private func row(_ key: String) -> XCUIElement { app.buttons["task-\(key)"].firstMatch }

    func testQueueShowsDecisionsAndSections() {
        XCTAssertTrue(app.staticTexts["2 decisions wait on you"].waitForExistence(timeout: 10))
        XCTAssertTrue(row("70").exists)
        XCTAssertTrue(row("64").exists)
        XCTAssertTrue(row("58").exists, "ready task listed under Agents")
        XCTAssertFalse(row("timur/site#12").exists, "someone else's task hidden by default")
        shot("01-queue")

        let toggle = app.switches["showEveryone"].firstMatch
        toggle.coordinate(withNormalizedOffset: CGVector(dx: 0.93, dy: 0.5)).tap()
        XCTAssertTrue(row("timur/site#12").waitForExistence(timeout: 5), "shown with everyone's tasks")
    }

    func testCompletedSectionExpands() {
        let header = app.buttons["section-Completed"].firstMatch
        for _ in 0..<6 where !header.isHittable { app.swipeUp() }
        XCTAssertTrue(header.waitForExistence(timeout: 10))
        XCTAssertFalse(row("30").exists, "completed tasks start collapsed")
        header.tap()
        XCTAssertTrue(row("30").waitForExistence(timeout: 5), "tapping the header reveals completed tasks")
        header.tap()
        XCTAssertFalse(row("30").waitForExistence(timeout: 2))
    }

    func testWorkstreamFilter() {
        XCTAssertTrue(row("64").waitForExistence(timeout: 10))
        app.buttons["chip-site"].firstMatch.tap()
        XCTAssertTrue(app.staticTexts["Workstreams: site"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["1 decision waits on you"].exists)
        XCTAssertFalse(row("64").exists)
        app.buttons["chip-site"].firstMatch.tap()
        XCTAssertTrue(app.staticTexts["Workstreams: All except site"].waitForExistence(timeout: 5))
        XCTAssertFalse(row("70").exists)
    }

    func testAnswerDecisionCard() {
        XCTAssertTrue(row("70").waitForExistence(timeout: 10))
        row("70").tap()
        XCTAssertTrue(app.staticTexts["Which palette should the landing page use?"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["detailStatus"].exists)
        let send = app.buttons["sendAnswer"]
        XCTAssertFalse(send.isEnabled, "nothing picked yet")
        app.buttons["choice-Pop"].tap()
        XCTAssertTrue(app.buttons["Send “Pop”"].exists)
        shot("02-decision-card")
        send.tap()
        XCTAssertTrue(app.staticTexts["Answer sent"].waitForExistence(timeout: 5) || app.otherElements["sentBanner"].exists || app.images["sentBanner"].exists || app.descendants(matching: .any)["sentBanner"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.otherElements["decisionCard"].exists, "answered card is gone")
        XCTAssertTrue(app.staticTexts["Ready"].exists)
        shot("03-answered")
        app.navigationBars.buttons.element(boundBy: 0).tap()
        XCTAssertTrue(app.staticTexts["1 decision waits on you"].waitForExistence(timeout: 5))
    }

    func testAskQuestionInstead() {
        XCTAssertTrue(row("64").waitForExistence(timeout: 10))
        row("64").tap()
        XCTAssertTrue(app.buttons["choice-Yes"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["choice-No"].exists)
        XCTAssertFalse(app.buttons["askInstead"].isEnabled)
        let note = app.textFields["noteField"]
        note.tap()
        note.typeText("Which list does it go to?")
        app.buttons["askInstead"].tap()
        XCTAssertTrue(app.descendants(matching: .any)["sentBanner"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["Ready"].exists)
    }

    func testMoveToOnHold() {
        XCTAssertTrue(row("58").waitForExistence(timeout: 10))
        row("58").tap()
        XCTAssertTrue(app.buttons["move"].waitForExistence(timeout: 5))
        app.buttons["move"].tap()
        app.buttons["On hold"].firstMatch.tap()
        let waiting = app.textFields["waitingField"]
        XCTAssertTrue(waiting.waitForExistence(timeout: 5))
        waiting.tap()
        waiting.typeText("the new template")
        shot("04-move")
        app.buttons["confirmMove"].tap()
        XCTAssertTrue(app.staticTexts["Waiting on: the new template"].waitForExistence(timeout: 5))
        XCTAssertEqual(app.staticTexts["detailStatus"].label, "On hold")
    }

    func testSearch() {
        XCTAssertTrue(app.buttons["search"].waitForExistence(timeout: 10))
        app.buttons["search"].tap()
        let field = app.searchFields.firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 5))
        field.tap()
        field.typeText("blog")
        XCTAssertTrue(row("30").waitForExistence(timeout: 5), "search covers completed tasks")
        shot("05-search")
    }

    func testSettingsAndFirstRun() {
        app.terminate()
        app.launchArguments = ["-uiTesting"]
        app.launch()
        XCTAssertTrue(app.navigationBars["Welcome to Boss"].waitForExistence(timeout: 10))
        XCTAssertFalse(app.buttons["save"].isEnabled)
        shot("06-settings")
        app.buttons["tryDemo"].tap()
        XCTAssertTrue(app.staticTexts["2 decisions wait on you"].waitForExistence(timeout: 10))
    }
}
