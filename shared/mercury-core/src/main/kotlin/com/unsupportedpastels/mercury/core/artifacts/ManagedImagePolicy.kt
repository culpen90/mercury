package com.unsupportedpastels.mercury.core.artifacts

/** Transport-free managed-image path and native-format rules. */
object ManagedImagePolicy {
    private val extensions = setOf("bmp", "gif", "jpeg", "jpg", "png", "webp")

    fun isCanonicalManagedPath(path: String): Boolean =
        path.length in 2..4096 && path.startsWith('/') && !path.startsWith("//") &&
            path.none { it.isISOControl() || it == '\\' } &&
            path.split('/').drop(1).none { it.isEmpty() || it == "." || it == ".." }

    fun isManagedImagePath(path: String): Boolean =
        isCanonicalManagedPath(path) && path.substringAfterLast('.', "").lowercase() in extensions
}
