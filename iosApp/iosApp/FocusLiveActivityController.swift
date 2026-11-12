import ActivityKit
import ComposeApp
import Foundation

/// Bridges Kotlin's `FocusEffect.LockScreenInterruption` to an ActivityKit Live Activity.
/// Instantiated and registered once at launch via `FocusLiveActivityBridgeKt.RegisterFocusLiveActivityBridge`.
final class FocusLiveActivityController: FocusLiveActivityBridge {
    private var activity: Activity<FocusInterruptionAttributes>?

    func start(
        sessionId: String,
        startedAtEpochMillis: Int64,
        deadlineEpochMillis: Int64,
        remainingMillis: Int64,
        language: String,
        roomName: String?,
        memberCount: Int64
    ) {
        guard ActivityAuthorizationInfo().areActivitiesEnabled else { return }

        let deadline = Date(timeIntervalSince1970: TimeInterval(deadlineEpochMillis) / 1000)
        let state = FocusInterruptionAttributes.ContentState(
            deadline: deadline,
            remainingSeconds: Int((remainingMillis + 999) / 1000),
            roomName: roomName,
            memberCount: Int(memberCount)
        )

        if let activity, activity.attributes.sessionId == sessionId {
            Task { await activity.update(ActivityContent(state: state, staleDate: deadline)) }
            return
        }

        // A stale activity from a previous/different session must not linger on the lock screen.
        if let activity { Task { await activity.end(nil, dismissalPolicy: .immediate) } }

        let attributes = FocusInterruptionAttributes(sessionId: sessionId, language: language)
        do {
            activity = try Activity.request(
                attributes: attributes,
                content: ActivityContent(state: state, staleDate: deadline),
                pushType: nil
            )
        } catch {
            activity = nil
        }
    }

    func update(sessionId: String, deadlineEpochMillis: Int64, remainingMillis: Int64) {
        guard let activity, activity.attributes.sessionId == sessionId else { return }
        let deadline = Date(timeIntervalSince1970: TimeInterval(deadlineEpochMillis) / 1000)
        let state = FocusInterruptionAttributes.ContentState(
            deadline: deadline,
            remainingSeconds: Int((remainingMillis + 999) / 1000),
            roomName: activity.content.state.roomName,
            memberCount: activity.content.state.memberCount
        )
        Task { await activity.update(ActivityContent(state: state, staleDate: deadline)) }
    }

    func end(sessionId: String) {
        guard let activity, activity.attributes.sessionId == sessionId else { return }
        Task { await activity.end(nil, dismissalPolicy: .immediate) }
        self.activity = nil
    }
}
