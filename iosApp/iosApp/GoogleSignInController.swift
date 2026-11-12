import ComposeApp
import FirebaseCore
import GoogleSignIn
import UIKit

/// Bridges Kotlin's `GoogleSignInBridge` to the native GoogleSignIn SDK, mirroring the
/// `FocusLiveActivityController` interop pattern. Instantiated and registered once at launch via
/// `GoogleSignInBridgeKt.RegisterGoogleSignInBridge`.
///
/// Uses the Firebase app's OAuth client ID (from `GoogleService-Info.plist`, already configured
/// via `Firebase.initialize()`) so no client ID needs to be hardcoded here. The reversed-client-ID
/// URL scheme used for the sign-in redirect is configured separately in Info.plist/Secrets.xcconfig.
final class GoogleSignInController: GoogleSignInBridge {
    func signIn(callback: @escaping (String?, String?) -> Void) {
        guard let clientID = FirebaseApp.app()?.options.clientID else {
            callback(nil, nil)
            return
        }
        guard let presenter = Self.topViewController() else {
            callback(nil, nil)
            return
        }
        GIDSignIn.sharedInstance.configuration = GIDConfiguration(clientID: clientID)
        GIDSignIn.sharedInstance.signIn(withPresenting: presenter) { result, error in
            guard let result, error == nil else {
                // Cancellation, missing native config, or any other failure all resolve to a null
                // token — the caller (Kotlin) treats every case as "sign-in unavailable/aborted".
                callback(nil, nil)
                return
            }
            callback(result.user.idToken?.tokenString, result.user.accessToken.tokenString)
        }
    }

    /// Handles the reversed-client-ID redirect URL. Returns true if GoogleSignIn consumed it.
    static func handle(_ url: URL) -> Bool {
        GIDSignIn.sharedInstance.handle(url)
    }

    private static func topViewController() -> UIViewController? {
        var controller = UIApplication.shared.connectedScenes
            .compactMap { ($0 as? UIWindowScene)?.keyWindow }
            .first?.rootViewController
        while let presented = controller?.presentedViewController { controller = presented }
        return controller
    }
}
