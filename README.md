# FlipFocus

FlipFocus is a cross-platform focus timer built with Kotlin Multiplatform and
Compose Multiplatform. To start a session, choose a duration and place your
phone face-down. Picking it up pauses the session and starts a short return
window.

> This is an early release (version 0.1.1), not a final App Store release.

## Features

- Focus sessions from 1 to 180 minutes, with 15-, 25- and 45-minute presets.
- Face-down start and lift-to-pause behavior, using the device motion sensors.
- A return window to resume an interrupted session.
- On-device session history, daily progress, streaks and weekly insights.
- Optional breaks, reminders, sounds and haptic feedback.
- Polish and English interface, following the system language by default.
- Optional community features: company directory, clans and rooms, invitations,
  consent-based live status and rankings, abuse reporting, account export and
  account deletion.
- A lock-screen interruption surface: Android notification and iOS Live Activity.

The focus timer and personal history work without signing in. Community data is
optional and uses Firebase Authentication and Cloud Firestore. The company
directory is bundled locally. Personal focus history is stored on the device.

## Platforms and requirements

- Android: minimum API 26; build with Android Studio and JDK 17 or newer.
- iOS: deployment target 16.2; build with Xcode, XcodeGen and JDK 17 or newer.
- Desktop JVM target is included for development and tests.

## Firebase configuration

Firebase is used by the optional community features. To build and run with those
features:

1. Create or select a Firebase project and register the Android and iOS apps.
2. Place the Android configuration at `composeApp/google-services.json`.
3. Place the iOS configuration at `iosApp/iosApp/GoogleService-Info.plist`.
4. Enable Anonymous Authentication and configure Cloud Firestore security rules
   from `firestore.rules`.

These configuration files and local OAuth settings are not included here. Keep
private configuration out of version control. Google account linking is
optional; its client ID is read from the ignored `local.properties` file or the
`GOOGLE_WEB_CLIENT_ID` environment variable. See
[`docs/adr/0004-anonymous-account-linking.md`](docs/adr/0004-anonymous-account-linking.md)
for the iOS Google Sign-In setup.

## Build and test

Run the shared and desktop test suites:

```shell
./gradlew test
```

Build the Android debug app:

```shell
./gradlew :composeApp:assembleDebug
```

Generate the Xcode project and open it:

```shell
cd iosApp
xcodegen generate
open iosApp.xcodeproj
```

An iOS build also requires the Firebase configuration above and the Xcode
command-line tools.

## Project structure

- `shared/domain` — focus session rules, progress calculations and social models.
- `shared/data` — local persistence and Firebase repository implementations.
- `shared/presentation` — shared state holders and application intents.
- `composeApp` — shared Compose UI and Android/Desktop/iOS platform adapters.
- `iosApp` — iOS app host, Google Sign-In bridge and Live Activity integration.
- `firestore.rules` — Firestore access rules for the community features.
- `docs/adr` — architecture decision records.
