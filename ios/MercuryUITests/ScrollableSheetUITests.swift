import XCTest

/// Real native gestures against production sheets, with synthetic list contents.
final class ScrollableSheetUITests: XCTestCase {
    override func setUpWithError() throws { continueAfterFailure = false }

    func testModelListScrollsBothWaysWithoutMovingOrDismissingSheet() {
        let app = launch()
        app.buttons["Open model list"].tap()
        let title = app.staticTexts["Choose model"]
        XCTAssertTrue(title.waitForExistence(timeout: 15))
        let initialTop = title.frame.minY
        let list = app.scrollViews["Available models"]
        XCTAssertTrue(list.exists)
        let firstModel = app.buttons["Select Fixture Fixture model 01"]
        let lastModel = app.buttons["Select Fixture Fixture model 40"]
        XCTAssertTrue(firstModel.isHittable)
        capture("ios-model-sheet-top")
        for _ in 0..<3 { drag(list, from: 0.2, to: 0.85) }
        XCTAssertTrue(title.exists, "Pulling beyond the first model must keep the chooser open")
        XCTAssertEqual(title.frame.minY, initialTop, accuracy: 4)
        drag(list, from: 0.8, to: 0.2)
        XCTAssertFalse(firstModel.isHittable)
        XCTAssertEqual(title.frame.minY, initialTop, accuracy: 4, "Scrolling must not expand or shrink the sheet")
        capture("ios-model-sheet-scrolled")
        for _ in 0..<12 {
            if lastModel.isHittable { break }
            drag(list, from: 0.8, to: 0.2)
        }
        XCTAssertTrue(lastModel.isHittable, "The final model must remain reachable")
        for _ in 0..<3 { drag(list, from: 0.8, to: 0.2) }
        XCTAssertTrue(lastModel.isHittable)
        XCTAssertEqual(title.frame.minY, initialTop, accuracy: 4)
        capture("ios-model-sheet-bottom")
        for _ in 0..<12 {
            if firstModel.isHittable { break }
            drag(list, from: 0.2, to: 0.8)
        }
        XCTAssertTrue(firstModel.isHittable, "Returning from the end must scroll the list to its first model")
        for _ in 0..<3 { drag(list, from: 0.2, to: 0.85) }
        XCTAssertTrue(title.exists, "Pulling beyond the first model must not dismiss the chooser")
        XCTAssertEqual(title.frame.minY, initialTop, accuracy: 4)
        app.buttons["Done"].tap()
        waitUntilHittable(app.buttons["Open model list"])
    }

    func testModelStillSelectsAfterScrolling() {
        let app = launch()
        app.buttons["Open model list"].tap()
        let list = app.scrollViews["Available models"]
        XCTAssertTrue(list.waitForExistence(timeout: 5))
        let target = app.buttons["Select Fixture Fixture model 20"]
        for _ in 0..<8 {
            if target.isHittable { break }
            drag(list, from: 0.8, to: 0.25)
        }
        XCTAssertTrue(target.isHittable)
        target.tap()
        XCTAssertTrue(app.staticTexts["Selected Fixture model 20"].waitForExistence(timeout: 5))
    }

    func testContextListScrollsAndKeepsExplicitDismissal() {
        let app = launch()
        app.buttons["Open context list"].tap()
        let bar = app.navigationBars["Context"]
        XCTAssertTrue(bar.waitForExistence(timeout: 5))
        let initialTop = bar.frame.minY
        let list = app.collectionViews.firstMatch
        XCTAssertTrue(list.exists)
        drag(list, from: 0.8, to: 0.2)
        XCTAssertFalse(app.staticTexts["Fixture category 01"].isHittable)
        XCTAssertEqual(bar.frame.minY, initialTop, accuracy: 4)
        capture("ios-context-sheet-scrolled")
        drag(list, from: 0.2, to: 0.8)
        XCTAssertTrue(app.staticTexts["Fixture category 01"].isHittable)
        for _ in 0..<3 { drag(list, from: 0.2, to: 0.85) }
        XCTAssertTrue(bar.exists)
        XCTAssertEqual(bar.frame.minY, initialTop, accuracy: 4)
        bar.buttons["Done"].tap()
        waitUntilHittable(app.buttons["Open context list"])
    }

    private func launch() -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-uitest-scrollable-sheets"]
        app.launch()
        XCTAssertTrue(app.buttons["Open model list"].waitForExistence(timeout: 10))
        waitUntilHittable(app.buttons["Open model list"])
        return app
    }

    private func drag(_ list: XCUIElement, from start: CGFloat, to end: CGFloat) {
        list.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: start))
            .press(
                forDuration: 0.05,
                thenDragTo: list.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: end)),
                withVelocity: .slow,
                thenHoldForDuration: 0.2
            )
    }

    private func capture(_ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    private func waitUntilHittable(_ element: XCUIElement) {
        let ready = XCTNSPredicateExpectation(predicate: NSPredicate(format: "hittable == true"), object: element)
        XCTAssertEqual(XCTWaiter.wait(for: [ready], timeout: 10), .completed)
    }
}
