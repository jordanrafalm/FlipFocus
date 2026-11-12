package pl.flipfocus.platform

/**
 * Implemented in Swift (app target) to drive ActivityKit. Kotlin/Native cannot import ActivityKit
 * directly, so this interface is exported through the ComposeApp Objective-C header and a Swift
 * class conforms to it; see `iosApp/iosApp/FocusLiveActivityController.swift`.
 */
interface FocusLiveActivityBridge {
    fun start(
        sessionId: String,
        startedAtEpochMillis: Long,
        deadlineEpochMillis: Long,
        remainingMillis: Long,
        language: String,
        roomName: String?,
        memberCount: Long,
    )
    fun update(sessionId: String, deadlineEpochMillis: Long, remainingMillis: Long)
    fun end(sessionId: String)
}

object FocusLiveActivityBridgeHolder {
    var bridge: FocusLiveActivityBridge? = null
}

/** Called once from Swift at launch: `FocusLiveActivityBridgeKt.RegisterFocusLiveActivityBridge(bridge: controller)`. */
fun RegisterFocusLiveActivityBridge(bridge: FocusLiveActivityBridge) {
    FocusLiveActivityBridgeHolder.bridge = bridge
}
