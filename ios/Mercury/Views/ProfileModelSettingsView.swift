import SwiftUI
import MercuryCore

/// Profile settings use management REST calls without taking over a chat runtime.
struct ProfileModelSettingsView: View {
    @Environment(AppModel.self) private var appModel
    @State private var settings: ProfileModelSettings?
    @State private var client: ProfileModelSettingsClient?
    @State private var isLoading = false
    @State private var isApplying = false
    @State private var errorMessage: String?
    @State private var sheet: Sheet?
    @State private var confirmation: ModelSelection?
    @State private var confirmationMessage: String?
    @State private var mutation: Task<Void, Never>?

    private enum Sheet: String, Identifiable {
        case models
        var id: String { rawValue }
    }

    private struct Scope: Equatable, Hashable {
        let origin: String?
        let profile: String
        let relayID: UUID?
    }

    private var scope: Scope {
        Scope(origin: appModel.serverOrigin, profile: appModel.activeProfile,
              relayID: (appModel.activeRelayTarget ?? appModel.selectedRelayTarget)?.id)
    }

    private var fastSupported: Bool {
        SessionModelPickerPolicy.capabilities(in: settings?.options, for: settings?.options.current)?.fast == true
    }

    var body: some View {
        Form {
            if let errorMessage {
                Section {
                    Label(errorMessage, systemImage: "exclamationmark.triangle.fill")
                        .foregroundStyle(Color.statusAlert)
                    Button("Try Again") { Task { await load(scope: scope) } }
                        .disabled(isApplying)
                }
            }
            if isLoading && settings == nil {
                ProgressView("Loading model settings…")
            } else if let settings {
                Section {
                    if let current = settings.options.current {
                        VStack(alignment: .leading, spacing: 4) {
                            Text(current.model)
                            Text(settings.options.providers.first { $0.slug == current.provider }?.name ?? current.provider)
                                .font(.footnote)
                                .foregroundStyle(Color.secondary)
                        }
                    } else {
                        Text("No default model selected")
                            .foregroundStyle(Color.secondary)
                    }
                    Button("Change model") { sheet = .models }
                        .disabled(isApplying || settings.options.providers.isEmpty)
                        .accessibilityIdentifier("Change default model")
                } header: {
                    Text("Default model for new chats")
                } footer: {
                    Text("Profile: \(scope.profile)")
                }
                Section {
                    ForEach(ProfileSpeedPolicy.shared.speeds, id: \.self) { speed in
                        Button {
                            applySpeed(speed)
                        } label: {
                            HStack {
                                Text(speed.capitalized)
                                    .foregroundStyle(Color.primary)
                                Spacer()
                                if settings.speed == speed {
                                    Image(systemName: "checkmark")
                                        .foregroundStyle(Color.accentPrimary)
                                }
                            }
                        }
                        .disabled(isApplying || !ProfileSpeedPolicy.shared.isSelectable(speed: speed, fastSupported: fastSupported))
                        .accessibilityLabel("Set default speed to \(speed)")
                        .accessibilityValue(settings.speed == speed ? "Selected" : "")
                    }
                } header: {
                    Text("Speed for new chats")
                } footer: {
                    if settings.speed == nil {
                        Text("This profile uses another speed mode. Choose Normal or Fast to replace it.")
                    } else if !fastSupported {
                        Text("Fast is unavailable for this model. Normal is always available.")
                    } else {
                        Text("Applies to new chats. Existing chats keep their speed.")
                    }
                }
                if isApplying { ProgressView("Saving…") }
            } else if scope.relayID != nil {
                Text("Default model settings are unavailable through this Relay connection.")
                    .foregroundStyle(Color.secondary)
            } else if scope.origin == nil {
                Text("Connect to a Hermes server to choose a default model and speed.")
                    .foregroundStyle(Color.secondary)
            }
        }
        .navigationTitle("Default model")
        .navigationBarTitleDisplayMode(.inline)
        .scrollContentBackground(.hidden)
        .amoledScreen()
        .task(id: scope) { await load(scope: scope) }
        .onDisappear { mutation?.cancel() }
        .sheet(item: $sheet) { _ in
            ModelPickerSheet(
                options: settings?.options, selection: settings?.options.current,
                isLoading: isLoading, isApplying: isApplying, errorMessage: errorMessage,
                onRetry: { Task { await load(scope: scope) } },
                onSelectModel: { applyModel($0) },
                title: "Default model", subtitle: "For new chats in \(scope.profile)"
            )
            .presentationDetents([.medium, .large])
        }
        .alert("Use this model?", isPresented: Binding(
            get: { confirmation != nil },
            set: { if !$0 { confirmation = nil } }
        ), presenting: confirmation) { selection in
            Button("Use Model") { applyModel(selection, confirmed: true) }
            Button("Cancel", role: .cancel) { confirmation = nil }
        } message: { _ in
            Text(confirmationMessage ?? "Confirm this model selection.")
        }
    }

    @MainActor
    private func load(scope requestedScope: Scope) async {
        guard !Task.isCancelled, scope == requestedScope else { return }
        mutation?.cancel()
        settings = nil
        client = nil
        isApplying = false
        errorMessage = nil
        confirmation = nil
        sheet = nil
        guard requestedScope.relayID == nil, let origin = requestedScope.origin,
              let scopedClient = try? ProfileModelSettingsClient(
                client: HermesHTTPClient.makeAuthenticated(origin: origin), profile: requestedScope.profile
              ) else { isLoading = false; return }
        isLoading = true
        do {
            let loaded = try await scopedClient.load()
            guard !Task.isCancelled, scope == requestedScope else { return }
            settings = loaded
            client = scopedClient
            isLoading = false
        } catch {
            guard !Task.isCancelled, scope == requestedScope else { return }
            isLoading = false
            errorMessage = "Could not load default model settings."
        }
    }

    @MainActor
    private func applySpeed(_ speed: String) {
        guard !isApplying, let client, let settings,
              ProfileSpeedPolicy.shared.isSelectable(speed: speed, fastSupported: fastSupported) else { return }
        let requestedScope = scope
        isApplying = true
        errorMessage = nil
        mutation = Task {
            defer { if scope == requestedScope { isApplying = false } }
            do {
                try await client.setSpeed(speed)
                guard !Task.isCancelled, scope == requestedScope else { return }
                self.settings = ProfileModelSettings(options: settings.options, speed: speed)
            } catch {
                guard !Task.isCancelled, scope == requestedScope else { return }
                errorMessage = "Could not save the default speed."
            }
        }
    }

    @MainActor
    private func applyModel(_ selection: ModelSelection, confirmed: Bool = false) {
        guard !isApplying, let client else { return }
        let requestedScope = scope
        confirmation = nil
        isApplying = true
        errorMessage = nil
        mutation = Task {
            defer { if scope == requestedScope { isApplying = false } }
            do {
                let result = try await client.setModel(selection, confirmed: confirmed)
                guard !Task.isCancelled, scope == requestedScope else { return }
                if result.confirmationRequired {
                    confirmationMessage = result.confirmationMessage
                    confirmation = selection
                    sheet = nil
                } else if result.ok {
                    let loaded = try await client.load()
                    guard !Task.isCancelled, scope == requestedScope else { return }
                    settings = loaded
                    sheet = nil
                } else {
                    errorMessage = "The server did not accept this model."
                }
            } catch {
                guard !Task.isCancelled, scope == requestedScope else { return }
                errorMessage = "Could not save the default model."
            }
        }
    }
}
