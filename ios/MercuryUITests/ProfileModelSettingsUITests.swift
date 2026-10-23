import Foundation
import XCTest

/// Opt-in interaction coverage against profile_model_settings_fake.py.
/// Uses only a loopback synthetic host and never opens a chat runtime.
/// Set TEST_RUNNER_MERCURY_MODEL_SETTINGS_FIXTURE_ORIGIN when invoking xcodebuild.
final class ProfileModelSettingsUITests: XCTestCase {
    func testNormalDefaultPersistsAndRemainsAvailableForAModelWithoutFast() throws {
        guard let origin = ProcessInfo.processInfo.environment["MERCURY_MODEL_SETTINGS_FIXTURE_ORIGIN"],
              let url = URL(string: origin), url.host == "127.0.0.1", url.scheme == "http", url.port != nil else {
            throw XCTSkip("Set MERCURY_MODEL_SETTINGS_FIXTURE_ORIGIN to the loopback profile-model fixture")
        }
        continueAfterFailure = false
        XCTAssertEqual(try state(origin)["synthetic"] as? Bool, true)
        let app = XCUIApplication()
        app.launchArguments = ["-uitest-reset-local-state", "-uitest-probe", origin]
        app.launch()

        XCTAssertTrue(app.staticTexts["E2E Session"].waitForExistence(timeout: 30))
        let settingsButton = app.buttons["Settings"]
        XCTAssertTrue(settingsButton.waitForExistence(timeout: 15))
        settingsButton.tap()
        let defaultModel = app.staticTexts["Default model"]
        XCTAssertTrue(defaultModel.waitForExistence(timeout: 15))
        defaultModel.tap()

        let normal = app.buttons["Set default speed to normal"]
        let fast = app.buttons["Set default speed to fast"]
        XCTAssertTrue(normal.waitForExistence(timeout: 15))
        XCTAssertTrue(fast.isEnabled)
        assertSelected(fast)
        normal.tap()
        assertSelected(normal)
        XCTAssertEqual((try state(origin)["speeds"] as? [String: String])?["default"], "normal")
        capture("default-speed-normal", app)

        // Reopening requires a new profile read, proving the saved default is
        // not just the optimistic SwiftUI state from the tap.
        app.navigationBars["Default model"].buttons["Settings"].tap()
        app.staticTexts["Default model"].tap()
        XCTAssertTrue(normal.waitForExistence(timeout: 15))
        assertSelected(normal)

        app.buttons["Change default model"].tap()
        let standardModel = app.buttons["Select Fake normal-example"]
        XCTAssertTrue(standardModel.waitForExistence(timeout: 10))
        standardModel.tap()
        let pickerClosed = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: app.buttons["Done"])
        XCTAssertEqual(XCTWaiter.wait(for: [pickerClosed], timeout: 15), .completed)
        XCTAssertTrue(app.staticTexts["normal-example"].waitForExistence(timeout: 15))
        let normalEnabled = XCTNSPredicateExpectation(predicate: NSPredicate(format: "enabled == true"), object: normal)
        XCTAssertEqual(XCTWaiter.wait(for: [normalEnabled], timeout: 15), .completed)
        let saved = XCTNSPredicateExpectation(predicate: NSPredicate(format: "enabled == false"), object: fast)
        XCTAssertEqual(XCTWaiter.wait(for: [saved], timeout: 15), .completed)
        XCTAssertTrue(normal.isEnabled)
        normal.tap()
        assertSelected(normal)
        capture("normal-without-fast-capability", app)
        let receipt = try state(origin)
        XCTAssertEqual((receipt["models"] as? [String: String])?["default"], "normal-example")
        XCTAssertEqual((receipt["speeds"] as? [String: String])?["work"], "normal")
        XCTAssertEqual(receipt["runtime_requests"] as? Int, 0,
                       "Changing defaults must not create or resume a session")
    }

    private func assertSelected(_ element: XCUIElement) {
        let selected = XCTNSPredicateExpectation(predicate: NSPredicate(format: "value == %@", "Selected"), object: element)
        XCTAssertEqual(XCTWaiter.wait(for: [selected], timeout: 15), .completed)
    }

    private func capture(_ name: String, _ app: XCUIApplication) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    private func state(_ origin: String) throws -> [String: Any] {
        let semaphore = DispatchSemaphore(value: 0)
        var result: Result<Data, Error>?
        URLSession.shared.dataTask(with: URL(string: origin + "/__test__/model-settings")!) { data, _, error in
            if let error { result = .failure(error) }
            else if let data { result = .success(data) }
            semaphore.signal()
        }.resume()
        guard semaphore.wait(timeout: .now() + 10) == .success, let result else {
            throw URLError(.timedOut)
        }
        return try XCTUnwrap(JSONSerialization.jsonObject(with: result.get()) as? [String: Any])
    }
}
