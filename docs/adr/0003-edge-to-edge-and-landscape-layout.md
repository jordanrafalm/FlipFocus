# ADR 0003: Edge-to-edge and landscape layout

Status: accepted for implementation.

## Context

FlipFocus supports portrait and landscape on Android and iOS. Android already calls
`enableEdgeToEdge()`, while iOS hosts the Compose controller in a view that ignores
the SwiftUI safe area. The shared app root currently combines system safe-area padding
with the visual root and constrains the content column to `560.dp`.

The result is not a defined landscape experience. On a horizontal device, system insets
and content width can expose an unpainted area or leave a phone-width composition in a
wide viewport. The application must keep the background continuous across the whole
window and make the primary focus flow useful in landscape.

## Options

1. Lock the application to portrait.
2. Keep one vertically stacked layout and only remove the width constraint.
3. Separate full-window chrome from safe content and provide a landscape variant.

## Decision

Choose option 3.

- The root visual layer must use `fillMaxSize()` and paint `FocusColors.background`
  before any safe-area padding. It owns every pixel, including display cutouts and the
  system-bar regions.
- `safeDrawingPadding()` belongs to an inner content container only. It prevents
  controls from sitting under system UI without creating transparent side regions.
- The centered content container may retain a readable maximum width on wide screens,
  but it must use the same opaque background as the window. It must not be relied upon
  to paint the space outside its maximum width.
- A screen is landscape when its available width is greater than its available height.
  The focus home screen uses a two-column composition in that case: timer and session
  state in the primary column; heading, duration controls, and guidance in the secondary
  column. Each column scrolls only when its own content does not fit vertically.
- Completed, break, and abandoned states use a centered two-column grouping when
  landscape space allows it; history, settings, and clan retain their scrolling single
  column but receive a wider readable content limit.
- Portrait remains the current vertical flow. Do not infer orientation from platform
  APIs; derive it from Compose constraints so the behavior is shared by Android, iOS,
  desktop, resizable windows, and split-screen.
- Android continues to use `enableEdgeToEdge()`. The app theme supplies transparent
  system bars with icon appearance appropriate to `FocusColors.background`; no opaque
  black fallback is permitted. The iOS host continues to ignore the SwiftUI safe area,
  with the Compose inner content applying its own safe drawing padding.

## Consequences

- The background is continuous at left and right in landscape and beneath system bars.
- Landscape gains a deliberate composition rather than a stretched portrait screen.
- UI tests need both portrait and landscape constraints; fixed phone screenshots are no
  longer sufficient.
- Content composables should expose reusable sections (header, timer, controls, and
  guidance) rather than duplicating business state for each orientation.

## Implementation and Verification

1. Split the root in `App.kt` into a full-window background layer and an inset content
   layer. Keep the `560.dp` limit only on the content layer.
2. Refactor `FocusHomeScreen` into section composables, selected with
   `BoxWithConstraints` from width-versus-height, preserving the existing state and
   intent contracts.
3. Apply the same constraint-driven spacing policy to completed, break, and abandoned
   screens; retain scrolling as the overflow policy.
4. Build Android and iOS targets. Run on a landscape phone simulator and verify that
   both side edges and status/navigation-bar areas show `FocusColors.background`, with
   all interactive controls outside safe insets.
5. Add Compose UI tests for portrait and landscape constraints, including large text
   where available.