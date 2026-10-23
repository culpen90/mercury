import SwiftUI

#if DEBUG
/// Hermetic gesture coverage for the production sheets; never opens a connection.
struct ScrollableSheetFixtureView: View {
    private enum Sheet: String, Identifiable {
        case models, context
        var id: Self { self }
    }

    @State private var presentedSheet: Sheet?
    @State private var selection: ModelSelection?

    private let models = (1...40).map { String(format: "Fixture model %02d", $0) }

    var body: some View {
        VStack(spacing: 24) {
            Text("Synthetic sheet fixture")
            Button("Open model list") { presentedSheet = .models }
                .buttonStyle(.bordered)
                .controlSize(.large)
            Button("Open context list") { presentedSheet = .context }
                .buttonStyle(.bordered)
                .controlSize(.large)
            if let selection { Text("Selected \(selection.model)") }
        }
        .fullScreenCover(item: $presentedSheet) { sheet in
            switch sheet {
            case .models:
                ModelPickerSheet(
                    options: ModelOptions(current: selection, providers: [
                        ModelProviderOption(slug: "fixture", name: "Fixture", models: models, capabilities: [:]),
                    ]),
                    selection: selection,
                    isLoading: false,
                    isApplying: false,
                    errorMessage: nil,
                    onRetry: {},
                    onSelectModel: { selection = $0; presentedSheet = nil }
                )
            case .context:
                ContextSheet(
                    usage: nil,
                    breakdown: SessionContextBreakdown(
                        categories: (1...40).map {
                            ContextBreakdownCategory(name: String(format: "Fixture category %02d", $0), tokens: Int64($0), percent: nil)
                        },
                        usedTokens: nil, maxTokens: nil, percent: nil
                    ),
                    isLoading: false, isBusy: false, isIdle: true,
                    statusMessage: nil, errorMessage: nil,
                    compressSupported: false, undoSupported: false, branchSupported: false,
                    onRefresh: {}, onCompress: {}, onUndo: {}, onBranch: { _ in }
                )
            }
        }
    }
}
#endif
