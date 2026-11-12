import ActivityKit
import Foundation

/// Live Activity contract shared by the app (starts/updates/ends) and the widget extension (renders).
/// Carries only the countdown and, when the user is opted in and currently live, a room name/count —
/// never sensor readings, history or per-friend statistics.
struct FocusInterruptionAttributes: ActivityAttributes {
    struct ContentState: Codable, Hashable {
        var deadline: Date
        var remainingSeconds: Int
        var roomName: String?
        var memberCount: Int

        /// `Text(timerInterval:)` requires a non-inverted range; guard against an already-past deadline.
        var countdownRange: ClosedRange<Date> {
            let now = Date()
            return now <= deadline ? now...deadline : deadline...deadline
        }
    }

    var sessionId: String
    var language: String
}
