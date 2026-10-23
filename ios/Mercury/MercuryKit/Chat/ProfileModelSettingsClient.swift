import Foundation
import MercuryCore

struct ProfileModelSettings: Sendable, Equatable {
    let options: ModelOptions
    /// Nil preserves a server mode that this two-choice control cannot represent.
    let speed: String?
}

enum ProfileModelSettingsError: Error, Equatable {
    case invalidRequest
    case rejected(Int)
    case malformedResponse
}

/// Official profile management endpoints only; no session is created or resumed.
/// The captured HTTP client and profile keep pending work on its original scope.
struct ProfileModelSettingsClient {
    private let client: HermesHTTPClient
    let profile: String

    init(client: HermesHTTPClient, profile: String) throws {
        guard let bounded = ProfileCatalogPolicy.shared.sanitizeRestNames(names: [profile]).first else {
            throw ProfileModelSettingsError.invalidRequest
        }
        self.client = client
        self.profile = bounded
    }

    private var profileQuery: [URLQueryItem] {
        [URLQueryItem(name: "profile", value: profile)]
    }

    func load() async throws -> ProfileModelSettings {
        async let options = loadOptions()
        async let speed = loadSpeed()
        return try await ProfileModelSettings(options: options, speed: speed)
    }

    func loadOptions() async throws -> ModelOptions {
        let (data, response) = try await client.get(
            path: "/api/model/options",
            queryItems: profileQuery + [
                URLQueryItem(name: "explicit_only", value: "1"),
                URLQueryItem(name: "include_unconfigured", value: "0"),
            ],
            maximumResponseBytes: 1024 * 1024
        )
        let json = try checkedJSON(data, response: response)
        return ModelOptions(shared: RpcResultDecoder.shared.modelOptions(resultJson: json))
    }

    func loadSpeed() async throws -> String? {
        let (data, response) = try await client.get(path: "/api/config", queryItems: profileQuery)
        return ProfileSpeedPolicy.shared.selectedSpeed(configJson: try checkedJSON(data, response: response))
    }

    func setSpeed(_ speed: String) async throws {
        guard let json = ProfileSpeedPolicy.shared.configUpdate(speed: speed) else {
            throw ProfileModelSettingsError.invalidRequest
        }
        let (data, response) = try await client.put(
            path: "/api/config", queryItems: profileQuery, jsonData: Data(json.utf8)
        )
        guard ProfileModelPolicy.shared.setResult(resultJson: try checkedJSON(data, response: response))?.ok == true else {
            throw ProfileModelSettingsError.malformedResponse
        }
    }

    func setModel(_ selection: ModelSelection, confirmed: Bool = false) async throws -> ProfileModelSetResult {
        guard !selection.provider.isEmpty, selection.provider.utf16.count <= maxModelProviderChars,
              !selection.model.isEmpty, selection.model.utf16.count <= maxModelIDChars else {
            throw ProfileModelSettingsError.invalidRequest
        }
        struct Assignment: Encodable {
            let scope = "main"
            let provider: String
            let model: String
            let confirm_expensive_model: Bool
        }
        let (data, response) = try await client.post(
            path: "/api/model/set", queryItems: profileQuery,
            jsonBody: Assignment(provider: selection.provider, model: selection.model, confirm_expensive_model: confirmed)
        )
        guard let result = ProfileModelPolicy.shared.setResult(resultJson: try checkedJSON(data, response: response)) else {
            throw ProfileModelSettingsError.malformedResponse
        }
        return result
    }

    private func checkedJSON(_ data: Data, response: HTTPURLResponse) throws -> String {
        guard (200..<300).contains(response.statusCode) else {
            throw ProfileModelSettingsError.rejected(response.statusCode)
        }
        guard let json = String(data: data, encoding: .utf8),
              (try? JSONSerialization.jsonObject(with: data)) is [String: Any] else {
            throw ProfileModelSettingsError.malformedResponse
        }
        return json
    }
}
