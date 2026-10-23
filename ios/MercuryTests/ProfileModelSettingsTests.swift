import Foundation
import XCTest
@testable import Mercury

private final class ProfileSettingsURLProtocol: URLProtocol {
    nonisolated(unsafe) static var handler: ((URLRequest) throws -> (HTTPURLResponse, Data))?

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        do {
            guard let handler = Self.handler else { throw URLError(.badServerResponse) }
            let (response, data) = try handler(request)
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: data)
            client?.urlProtocolDidFinishLoading(self)
        } catch {
            client?.urlProtocol(self, didFailWithError: error)
        }
    }

    override func stopLoading() {}
}

final class ProfileModelSettingsTests: XCTestCase {
    override func tearDown() {
        ProfileSettingsURLProtocol.handler = nil
        super.tearDown()
    }

    private func makeClient(profile: String = "work") throws -> ProfileModelSettingsClient {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [ProfileSettingsURLProtocol.self]
        let http = HermesHTTPClient(origin: "https://hermes.test", session: URLSession(configuration: configuration))
        http.bearerToken = "test-access"
        return try ProfileModelSettingsClient(client: http, profile: profile)
    }

    private func response(_ request: URLRequest, _ json: String, status: Int = 200) -> (HTTPURLResponse, Data) {
        (HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: nil,
                         headerFields: ["Content-Type": "application/json"])!, Data(json.utf8))
    }

    private func body(_ request: URLRequest) throws -> [String: Any] {
        let data: Data
        if let value = request.httpBody {
            data = value
        } else if let stream = request.httpBodyStream {
            stream.open()
            defer { stream.close() }
            var value = Data()
            var buffer = [UInt8](repeating: 0, count: 1024)
            while stream.hasBytesAvailable {
                let count = stream.read(&buffer, maxLength: buffer.count)
                guard count > 0 else { break }
                value.append(contentsOf: buffer.prefix(count))
            }
            data = value
        } else {
            throw URLError(.badServerResponse)
        }
        return try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
    }

    func testLoadUsesOnlyProfileManagementEndpointsAndSharedCatalogCapabilities() async throws {
        ProfileSettingsURLProtocol.handler = { request in
            XCTAssertEqual(request.httpMethod, "GET")
            XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer test-access")
            let components = try XCTUnwrap(URLComponents(url: request.url!, resolvingAgainstBaseURL: false))
            XCTAssertEqual(components.queryItems?.first { $0.name == "profile" }?.value, "work")
            switch components.path {
            case "/api/model/options":
                XCTAssertEqual(components.queryItems?.first { $0.name == "explicit_only" }?.value, "1")
                XCTAssertEqual(components.queryItems?.first { $0.name == "include_unconfigured" }?.value, "0")
                return self.response(request, #"{"provider":"openai","model":"example-model","providers":[{"slug":"openai","name":"OpenAI","authenticated":true,"models":["example-model"],"capabilities":{"example-model":{"fast":true}}}]}"#)
            case "/api/config":
                return self.response(request, #"{"agent":{"service_tier":"normal"}}"#)
            default:
                XCTFail("Loading defaults must not create, resume, or connect a runtime")
                return self.response(request, "{}", status: 404)
            }
        }

        let settings = try await makeClient().load()

        XCTAssertEqual(settings.speed, "normal")
        XCTAssertEqual(settings.options.current, ModelSelection(provider: "openai", model: "example-model"))
        XCTAssertEqual(SessionModelPickerPolicy.capabilities(in: settings.options, for: settings.options.current)?.fast, true)
    }

    func testNormalAndFastWritesCarryOnlyTheRequestedProfileSpeed() async throws {
        for speed in ["normal", "fast"] {
            ProfileSettingsURLProtocol.handler = { request in
                XCTAssertEqual(request.httpMethod, "PUT")
                XCTAssertEqual(request.url?.path, "/api/config")
                XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer test-access")
                XCTAssertEqual(URLComponents(url: request.url!, resolvingAgainstBaseURL: false)?.queryItems,
                               [URLQueryItem(name: "profile", value: "work")])
                let object = try self.body(request)
                let expected = ["config": ["agent": ["service_tier": speed]]]
                XCTAssertEqual(try JSONSerialization.data(withJSONObject: object, options: .sortedKeys),
                               try JSONSerialization.data(withJSONObject: expected, options: .sortedKeys))
                return self.response(request, #"{"ok":true}"#)
            }
            try await makeClient().setSpeed(speed)
        }
    }

    func testUnsupportedServerModeIsPreservedAndInvalidSpeedCannotBeWritten() async throws {
        ProfileSettingsURLProtocol.handler = { request in
            XCTAssertEqual(request.httpMethod, "GET")
            return self.response(request, #"{"agent":{"service_tier":"cold"}}"#)
        }
        let client = try makeClient()
        let speed = try await client.loadSpeed()
        XCTAssertNil(speed)

        do {
            try await client.setSpeed("cold")
            XCTFail("Unsupported client mode must not be sent")
        } catch let error as ProfileModelSettingsError {
            XCTAssertEqual(error, .invalidRequest)
        }
    }

    func testDefaultModelAssignmentIsProfileScopedAndPreservesConfirmation() async throws {
        ProfileSettingsURLProtocol.handler = { request in
            XCTAssertEqual(request.httpMethod, "POST")
            XCTAssertEqual(request.url?.path, "/api/model/set")
            XCTAssertEqual(URLComponents(url: request.url!, resolvingAgainstBaseURL: false)?.queryItems,
                           [URLQueryItem(name: "profile", value: "work")])
            let body = try self.body(request)
            XCTAssertEqual(body["scope"] as? String, "main")
            XCTAssertEqual(body["provider"] as? String, "openai")
            XCTAssertEqual(body["model"] as? String, "example-model")
            XCTAssertEqual(body["confirm_expensive_model"] as? Bool, false)
            XCTAssertNil(body["session_id"])
            return self.response(request, #"{"ok":false,"confirm_required":true,"confirm_message":"Confirm this selection"}"#)
        }

        let result = try await makeClient().setModel(ModelSelection(provider: "openai", model: "example-model"))

        XCTAssertFalse(result.ok)
        XCTAssertTrue(result.confirmationRequired)
        XCTAssertEqual(result.confirmationMessage, "Confirm this selection")
    }

    func testFailedAndMalformedResponsesDoNotLookSaved() async throws {
        let client = try makeClient()
        ProfileSettingsURLProtocol.handler = { request in self.response(request, #"{"ok":false}"#, status: 403) }
        do {
            try await client.setSpeed("normal")
            XCTFail("Rejected speed write must fail")
        } catch let error as ProfileModelSettingsError {
            XCTAssertEqual(error, .rejected(403))
        }
        ProfileSettingsURLProtocol.handler = { request in self.response(request, #"{"ok":false}"#) }
        do {
            try await client.setSpeed("normal")
            XCTFail("An unaccepted speed write must fail")
        } catch let error as ProfileModelSettingsError {
            XCTAssertEqual(error, .malformedResponse)
        }
        ProfileSettingsURLProtocol.handler = { request in self.response(request, #"{"ok":"yes"}"#) }
        do {
            _ = try await client.setModel(ModelSelection(provider: "openai", model: "example-model"))
            XCTFail("Malformed model assignment must fail")
        } catch let error as ProfileModelSettingsError {
            XCTAssertEqual(error, .malformedResponse)
        }
    }

    func testInvalidProfileCannotConstructAManagementClient() {
        XCTAssertThrowsError(try makeClient(profile: String(repeating: "a", count: 65)))
        XCTAssertThrowsError(try makeClient(profile: "  "))
    }
}
