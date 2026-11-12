package pl.flipfocus.platform

/**
 * Implemented in Swift (app target) to drive Google Sign-In, mirroring the
 * [FocusLiveActivityBridge] interop pattern since Kotlin/Native cannot import the GoogleSignIn SDK
 * directly. See `iosApp/iosApp/GoogleSignInController.swift`.
 *
 * [signIn] presents the native Google account picker and invokes [callback] exactly once with the
 * resulting ID token (+ optional OAuth access token), or with a null token on cancellation/failure
 * (including missing/incomplete native configuration) — it never throws.
 */
interface GoogleSignInBridge {
    fun signIn(callback: (idToken: String?, accessToken: String?) -> Unit)
}

object GoogleSignInBridgeHolder {
    var bridge: GoogleSignInBridge? = null
}

/** Called once from Swift at launch: `GoogleSignInBridgeKt.RegisterGoogleSignInBridge(bridge: controller)`. */
fun RegisterGoogleSignInBridge(bridge: GoogleSignInBridge) {
    GoogleSignInBridgeHolder.bridge = bridge
}
