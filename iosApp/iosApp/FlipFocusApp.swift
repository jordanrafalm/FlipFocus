import SwiftUI
import ComposeApp

@main
struct FlipFocusApp: App {
    private let liveActivityController = FocusLiveActivityController()
    private let googleSignInController = GoogleSignInController()

    init() {
        FocusLiveActivityBridgeKt.RegisterFocusLiveActivityBridge(bridge: liveActivityController)
        GoogleSignInBridgeKt.RegisterGoogleSignInBridge(bridge: googleSignInController)
    }

    var body: some Scene {
        WindowGroup {
            ComposeView()
                .ignoresSafeArea()
                .onOpenURL { url in
                    if GoogleSignInController.handle(url) { return }
                    MainViewControllerKt.HandleFocusLink(url: url.absoluteString)
                }
        }
    }
}

private struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
