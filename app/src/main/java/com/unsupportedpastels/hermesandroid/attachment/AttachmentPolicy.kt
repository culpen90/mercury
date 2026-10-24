package com.unsupportedpastels.hermesandroid.attachment

/**
 * The attachment policy lives in the core.
 * These aliases keep existing call sites and imports unchanged; byte
 * streaming stays platform-local in [AttachmentIo].
 */
typealias AttachmentPolicy = com.unsupportedpastels.mercury.core.attachment.AttachmentPolicy
typealias AttachmentCandidate = com.unsupportedpastels.mercury.core.attachment.AttachmentCandidate
typealias AttachmentKind = com.unsupportedpastels.mercury.core.attachment.AttachmentKind
typealias AttachmentTooLargeException = com.unsupportedpastels.mercury.core.attachment.AttachmentTooLargeException
typealias StagedAttachments = com.unsupportedpastels.mercury.core.attachment.StagedAttachments
