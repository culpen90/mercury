import SwiftUI

extension ChatView {
    /// Local ownership follows navigation, while a native modal covers this chat.
    func handleDisappearance(appModel: AppModel, backgroundTaskScope: String) {
        // A full-screen list covers the chat without giving up its runtime,
        // observer, or notification visibility. Its binding is set before the
        // presentation begins, including the composer-owned host-file action.
        guard !state.isPresentingListModal else { return }
        // A transient SwiftUI disappearance must not interrupt an active
        // server-side turn. Android keeps its live controller in the
        // ViewModel; preserve the iOS connection while a turn is active so
        // the next foreground/open can resume the same runtime instead of
        // creating a second visible attempt.
        if ChatDisappearancePolicy.action(
            turnInFlight: state.turnInFlight,
            isSending: state.isSending
        ) == .preserveConnectionAndObserver {
            appModel.hideNotificationSession(owner: state.notificationVisibilityOwner)
            return
        }
        // Deliberate teardown for an idle session: no reconnect may fire
        // after dismissal.
        state.closedByUs = true
        appModel.hideNotificationSession(owner: state.notificationVisibilityOwner)
        state.reconnectTask?.cancel()
        state.reconnectTask = nil
        state.slashCompletionTask?.cancel()
        state.slashCompletionTask = nil
        state.dictation?.cancel()
        state.readAloud?.stop()
        state.eventTask?.cancel()
        state.eventTask = nil
        state.backgroundRegistryTask?.cancel()
        state.backgroundRegistryTask = nil
        let closingConnection = state.connection
        // A pooled relay reader outlives this screen and keeps applying
        // child evidence through the pool's task sink; only a direct
        // connection actually stops observing here.
        if closingConnection?.relaySocket == nil {
            var tasks = appModel.backgroundTasksBySession[backgroundTaskScope] ?? BackgroundTasks()
            tasks.markUnavailable()
            if !tasks.rows.isEmpty { appModel.backgroundTasksBySession[backgroundTaskScope] = tasks }
        }
        state.connectionOwnership.invalidate()
        state.connection = nil
        Task { if let closingConnection { await RelayConnectionPool.release(closingConnection) } }
    }

    func listModalDidDismiss() {
        let model = appModel
        let scope = backgroundTaskScope
        Task { await handleListModalDismissal(appModel: model, backgroundTaskScope: scope) }
    }

    func handleListModalDismissal(appModel: AppModel, backgroundTaskScope: String) async {
        // Let the uncovered chat deliver onAppear before checking navigation.
        // If its route was removed while covered, there is no return appearance
        // and its local ownership must still be released after the cover closes.
        await Task.yield()
        guard !state.isChatVisible else { return }
        handleDisappearance(appModel: appModel, backgroundTaskScope: backgroundTaskScope)
    }
}
