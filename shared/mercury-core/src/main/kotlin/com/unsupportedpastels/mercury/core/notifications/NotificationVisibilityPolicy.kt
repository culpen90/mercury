package com.unsupportedpastels.mercury.core.notifications

/**
 * What the user can currently see, as policy input. [windowFocused] accounts
 * for multi-window focus; callers without a separate focus observation can
 * use the default `true` to preserve foreground/session visibility semantics.
 */
data class SessionNotificationVisibility(
    val appForeground: Boolean = false,
    val windowFocused: Boolean = true,
    val visibleSessionId: String? = null,
)

object NotificationVisibilityPolicy {
    /**
     * Post unless the user is actively looking at this session: app in the
     * foreground, window focused, and the same durable session on screen.
     */
    fun shouldPost(sessionId: String, visibility: SessionNotificationVisibility): Boolean = !(
        visibility.appForeground &&
            visibility.windowFocused &&
            visibility.visibleSessionId == sessionId
        )
}
