import XCTest
import SwiftUI
import UIKit
@testable import Mercury

@MainActor
final class ChatListPresentationLifecycleTests: XCTestCase {
    func testNativeCoversPreserveAdmittedChatThroughActualAppearanceCallbacks() async throws {
        let socket = ConnectionTestSocket(frames: [])
        let connection = try ChatConnection(socket: socket)
        let state = ChatSessionState(sessionID: "stored", title: "Fixture", isNewSession: false, incomingShare: nil)
        state.connection = connection
        state.runtimeSessionID = "runtime"
        state.connectionState = .live
        state.didOpen = true
        let ownership = state.connectionOwnership.publish()
        let observer = Task { _ = try? await Task.sleep(for: .seconds(60)) }
        state.eventTask = observer
        let model = AppModel()
        // The host-file cover exercises the unavailable state without a server.
        model.setServerOrigin(nil)
        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
        let window = UIWindow(windowScene: scene)
        let host = UIHostingController(rootView: NavigationStack { ChatView(fixture: state).environment(model) })
        window.rootViewController = host
        window.makeKeyAndVisible()
        defer {
            window.isHidden = true
            window.rootViewController = nil
            observer.cancel()
        }
        try await waitForLifecycle("The admitted chat must appear") { state.isChatVisible }

        let presentations: [ReferenceWritableKeyPath<ChatSessionState, Bool>] = [
            \.showModelPicker, \.showContextSheet, \.showActivitySheet, \.showHostFiles,
        ]
        for (index, presentation) in presentations.enumerated() {
            state[keyPath: presentation] = true
            try await waitForLifecycle("Native cover \(index) must be presented") {
                host.presentedViewController != nil
            }
            let appearance = XCTAttachment(string: "Cover \(index): underlying chat visible callback state = \(state.isChatVisible)")
            appearance.name = "Native cover appearance callbacks"
            appearance.lifetime = .keepAlways
            add(appearance)
            XCTAssertTrue(state.connection === connection)
            XCTAssertFalse(observer.isCancelled)
            XCTAssertTrue(state.connectionOwnership.isCurrent(ownership))
            XCTAssertTrue(model.notificationVisibilityOwned(by: state.notificationVisibilityOwner))

            state[keyPath: presentation] = false
            try await waitForLifecycle("Dismissing the native cover must return to the same chat") {
                host.presentedViewController == nil && state.isChatVisible
            }
            await Task.yield()
            XCTAssertTrue(state.connection === connection)
            XCTAssertFalse(connection.isClosed)
            XCTAssertFalse(state.closedByUs)
            XCTAssertFalse(observer.isCancelled)
            XCTAssertTrue(state.connectionOwnership.isCurrent(ownership))
            XCTAssertTrue(model.notificationVisibilityOwned(by: state.notificationVisibilityOwner))
            XCTAssertTrue(state.didOpen)
            XCTAssertEqual(state.runtimeSessionID, "runtime")
            XCTAssertNil(socket.lastSent, "Cover return must not create, resume, or close a shared runtime")
        }

        window.rootViewController = UIViewController()
        try await waitForLifecycle("Actual navigation removal must release local ownership") {
            state.connection == nil && observer.isCancelled && connection.isClosed
        }
        XCTAssertTrue(state.closedByUs)
        XCTAssertFalse(state.connectionOwnership.isCurrent(ownership))
        XCTAssertFalse(model.notificationVisibilityOwned(by: state.notificationVisibilityOwner))
        XCTAssertNil(socket.lastSent)

        // Removing the actual presenting hierarchy while it is covered must
        // also deliver cleanup; invoking the callback manually cannot prove it.
        let coveredSocket = ConnectionTestSocket(frames: [])
        let coveredConnection = try ChatConnection(socket: coveredSocket)
        let covered = ChatSessionState(sessionID: "covered", title: "Fixture", isNewSession: false, incomingShare: nil)
        covered.connection = coveredConnection
        covered.runtimeSessionID = "covered-runtime"
        covered.connectionState = .live
        covered.didOpen = true
        let coveredObserver = Task { _ = try? await Task.sleep(for: .seconds(60)) }
        covered.eventTask = coveredObserver
        defer { coveredObserver.cancel() }
        let coveredHost = UIHostingController(rootView: NavigationStack { ChatView(fixture: covered).environment(model) })
        window.rootViewController = coveredHost
        try await waitForLifecycle("The replacement admitted chat must appear") { covered.isChatVisible }
        covered.showModelPicker = true
        try await waitForLifecycle("The replacement chat must present its native cover") {
            coveredHost.presentedViewController != nil
        }
        window.rootViewController = UIViewController()
        try await waitForLifecycle("Removing the covered chat must release its local ownership") {
            covered.connection == nil && coveredObserver.isCancelled && coveredConnection.isClosed
        }
        XCTAssertTrue(covered.closedByUs)
        XCTAssertFalse(model.notificationVisibilityOwned(by: covered.notificationVisibilityOwner))
        XCTAssertNil(coveredSocket.lastSent)
    }

    func testListPresentationsPreserveIdleConnectionObserverAndNotificationOwnership() async throws {
        let presentations: [ReferenceWritableKeyPath<ChatSessionState, Bool>] = [
            \.showModelPicker, \.showContextSheet, \.showActivitySheet, \.showHostFiles,
        ]
        for presentation in presentations {
            let socket = ConnectionTestSocket(frames: [])
            let connection = try ChatConnection(socket: socket)
            let state = ChatSessionState(sessionID: "stored", title: "Fixture", isNewSession: false, incomingShare: nil)
            state.connection = connection
            state.runtimeSessionID = "runtime"
            state.didOpen = true
            let ownership = state.connectionOwnership.publish()
            let observer = Task { _ = try? await Task.sleep(for: .seconds(60)) }
            state.eventTask = observer
            let model = AppModel()
            model.setServerOrigin("https://sheet-fixture.example")
            model.showNotificationSession("stored", scope: model.notificationSourceScope, owner: state.notificationVisibilityOwner)

            state[keyPath: presentation] = true
            ChatView(fixture: state).handleDisappearance(appModel: model, backgroundTaskScope: "fixture")

            XCTAssertTrue(state.connection === connection)
            XCTAssertFalse(state.closedByUs)
            XCTAssertTrue(state.connectionOwnership.isCurrent(ownership))
            XCTAssertNotNil(state.eventTask)
            XCTAssertFalse(observer.isCancelled)
            XCTAssertEqual(model.visibleSessionID, "stored")
            XCTAssertEqual(state.runtimeSessionID, "runtime")
            XCTAssertTrue(state.didOpen, "Dismissal must retain initial-open admission instead of resuming the runtime again")
            XCTAssertNil(socket.lastSent, "Presenting a list must never send session.close/create/resume")

            state[keyPath: presentation] = false
            state.isChatVisible = true
            await ChatView(fixture: state).handleListModalDismissal(appModel: model, backgroundTaskScope: "fixture")
            XCTAssertTrue(state.connection === connection)
            XCTAssertFalse(observer.isCancelled)
            XCTAssertTrue(state.connectionOwnership.isCurrent(ownership))
            XCTAssertEqual(model.visibleSessionID, "stored")
            XCTAssertTrue(state.didOpen)
            XCTAssertNil(socket.lastSent)

            observer.cancel()
            await connection.close()
        }
    }

    func testNavigationRemovedWhileCoveredStillReleasesLocalOwnershipAfterDismissal() async throws {
        let socket = ConnectionTestSocket(frames: [])
        let connection = try ChatConnection(socket: socket)
        let state = ChatSessionState(sessionID: "stored", title: "Fixture", isNewSession: false, incomingShare: nil)
        state.connection = connection
        state.showModelPicker = true
        let observer = Task { _ = try? await Task.sleep(for: .seconds(60)) }
        state.eventTask = observer
        let model = AppModel()
        let view = ChatView(fixture: state)
        view.handleDisappearance(appModel: model, backgroundTaskScope: "fixture")
        XCTAssertTrue(state.connection === connection)

        state.showModelPicker = false
        await view.handleListModalDismissal(appModel: model, backgroundTaskScope: "fixture")

        XCTAssertNil(state.connection)
        XCTAssertTrue(state.closedByUs)
        XCTAssertTrue(observer.isCancelled)
        XCTAssertNil(socket.lastSent)
    }

    func testActualIdleNavigationDepartureReleasesLocalOwnership() throws {
        let socket = ConnectionTestSocket(frames: [])
        let connection = try ChatConnection(socket: socket)
        let state = ChatSessionState(sessionID: "stored", title: "Fixture", isNewSession: false, incomingShare: nil)
        state.connection = connection
        let ownership = state.connectionOwnership.publish()
        let observer = Task { _ = try? await Task.sleep(for: .seconds(60)) }
        state.eventTask = observer

        ChatView(fixture: state).handleDisappearance(appModel: AppModel(), backgroundTaskScope: "fixture")

        XCTAssertNil(state.connection)
        XCTAssertTrue(state.closedByUs)
        XCTAssertFalse(state.connectionOwnership.isCurrent(ownership))
        XCTAssertNil(state.eventTask)
        XCTAssertTrue(observer.isCancelled)
        XCTAssertNil(socket.lastSent, "Releasing this client's local ownership must not close the shared runtime")
    }

    private func waitForLifecycle(_ message: String, condition: () -> Bool) async throws {
        let deadline = Date().addingTimeInterval(10)
        while !condition() && Date() < deadline { try await Task.sleep(for: .milliseconds(20)) }
        if !condition() {
            XCTFail(message)
            throw LifecycleTimeout()
        }
    }

    private struct LifecycleTimeout: Error {}
}
