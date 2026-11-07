package cc.chenhe.qqnotifyevo.service

/**
 * Probe builds disable the Nevo component in the manifest and never process notifications.
 * This type-only stub keeps shared settings UI source-compatible without loading the retired SDK.
 */
class NevoDecorator {
    companion object {
        fun isRunning(): Boolean = false
    }
}
