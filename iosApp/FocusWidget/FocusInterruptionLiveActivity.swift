import ActivityKit
import SwiftUI
import WidgetKit

/// Lock-screen and Dynamic Island rendering for `FocusInterruptionAttributes`. Shows the "Focus Clan"
/// header with a LIVE badge only when the state carries a `roomName` (i.e. the current opted-in, live
/// room) — otherwise falls back to the plain "FlipFocus" local variant. No fictitious data is rendered.
struct FocusInterruptionLiveActivity: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: FocusInterruptionAttributes.self) { context in
            LockScreenView(attributes: context.attributes, state: context.state)
                .activityBackgroundTint(Color.black)
                .activitySystemActionForegroundColor(Color.white)
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    HeaderView(state: context.state).padding(.leading, 4)
                }
                DynamicIslandExpandedRegion(.trailing) {
                    CountdownRing(state: context.state).frame(width: 44, height: 44)
                }
                DynamicIslandExpandedRegion(.bottom) {
                    MessageView(attributes: context.attributes).padding(.horizontal, 4)
                }
            } compactLeading: {
                Image(systemName: "iphone.gen3")
            } compactTrailing: {
                Text(timerInterval: context.state.countdownRange, countsDown: true, showsHours: false)
                    .monospacedDigit()
                    .frame(width: 40)
            } minimal: {
                CountdownRing(state: context.state)
            }
        }
    }
}

private struct HeaderView: View {
    let state: FocusInterruptionAttributes.ContentState

    var body: some View {
        HStack(spacing: 6) {
            if let roomName = state.roomName {
                Text(roomName).font(.headline).foregroundStyle(.white)
                Text("LIVE").font(.caption2.bold())
                    .padding(.horizontal, 6).padding(.vertical, 2)
                    .background(Color.red).foregroundStyle(.white).clipShape(Capsule())
            } else {
                Text("FlipFocus").font(.headline).foregroundStyle(.white)
            }
        }
    }
}

private struct MessageView: View {
    let attributes: FocusInterruptionAttributes

    private var isPolish: Bool { attributes.language == "pl" }

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(isPolish ? "Odłóż telefon ponownie" : "Put your phone face down again")
                .font(.subheadline.bold())
                .foregroundStyle(.white)
            Text(isPolish ? "Ekran ma być skierowany w dół." : "Screen should face down.")
                .font(.caption)
                .foregroundStyle(.white.opacity(0.7))
        }
    }
}

private struct CountdownRing: View {
    let state: FocusInterruptionAttributes.ContentState

    var body: some View {
        ZStack {
            Circle().stroke(Color.white.opacity(0.2), lineWidth: 3)
            Text(timerInterval: state.countdownRange, countsDown: true, showsHours: false)
                .monospacedDigit()
                .font(.caption2.bold())
                .foregroundStyle(.white)
                .multilineTextAlignment(.center)
        }
    }
}

private struct LockScreenView: View {
    let attributes: FocusInterruptionAttributes
    let state: FocusInterruptionAttributes.ContentState

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HeaderView(state: state)
            HStack(spacing: 16) {
                ZStack {
                    Circle().stroke(Color.white.opacity(0.2), lineWidth: 4).frame(width: 64, height: 64)
                    Text(timerInterval: state.countdownRange, countsDown: true, showsHours: false)
                        .monospacedDigit()
                        .font(.title2.bold())
                        .foregroundStyle(.white)
                        .frame(width: 60)
                }
                MessageView(attributes: attributes)
                if state.roomName != nil, state.memberCount > 0 {
                    Spacer()
                    VStack(alignment: .trailing) {
                        Text("\(state.memberCount)").font(.headline).foregroundStyle(.white)
                        Text(attributes.language == "pl" ? "osób" : "people")
                            .font(.caption2).foregroundStyle(.white.opacity(0.7))
                    }
                }
            }
        }
        .padding()
    }
}
