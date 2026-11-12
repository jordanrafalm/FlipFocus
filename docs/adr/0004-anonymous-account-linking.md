# ADR 0004: Anonimowe konto z późniejszym linkowaniem poświadczeń

Status: accepted  
Date: 2026-09-28

## Context

Clan needs a stable user identity to associate a nickname, company membership and submitted focus points. The app already uses Firebase Anonymous Auth and stores the profile under `users/{uid}`. A user should be able to start without a registration form and optionally make the account recoverable later with a password or Google.

The current implementation only calls `signInAnonymously()`. It does not link credentials, acquire Google credentials on Android/iOS, or handle collisions with credentials already attached to another Firebase UID.

## Options

1. Require email/password or Google sign-in before creating a profile. This adds friction to first use and changes the current optional-account flow.
2. Keep the anonymous account permanently. This cannot recover the profile after the app installation is lost.
3. Start anonymously, then link credentials to the existing Firebase user when the user opts in.

## Decision

Choose option 3.

- On first use, sign in anonymously and use Firebase Auth `uid` as the canonical `userId`. Persist nickname, company assignment and private profile data under `users/{uid}`.
- Firebase's persisted native auth session provides automatic sign-in on the same app installation. Anonymous identity is not a recovery method across reinstall/device changes.
- When the user chooses to secure the account, link an Email/Password or Google credential to the currently signed-in anonymous Firebase user. Linking must preserve the UID and all existing profile, company and points data.
- Never store passwords, Google access tokens or ID tokens in Firestore or local app preferences. Use Firebase Auth for credential storage and verification.
- If a credential is already attached to a different UID, stop and present an explicit recovery/account-choice flow. Do not silently sign into the other UID or merge profile/company/points data.
- Anonymous use remains available without linking. Apple sign-in is not part of this implementation scope; it can be added by a later decision.

## Consequences

- The profile and company contribution schemas remain keyed by one stable UID before and after linking.
- Email/Password requires UI and handling for validation, verification and account recovery.
- Google requires native credential acquisition on Android and iOS in addition to enabling the Firebase provider. Android signing fingerprints and the iOS reversed client ID/URL scheme must be configured.
- Account collision, cancellation, offline failure and retry states need explicit UI and tests.
- A user who loses an unlinked anonymous installation cannot recover that anonymous UID; this limitation must be disclosed before account deletion/reinstall scenarios.

## Follow-ups

1. Enable Anonymous, Email/Password and Google providers in Firebase Authentication.
2. Add Android and iOS Google Sign-In credential acquisition and link the resulting Firebase credential to the current user. **Done** — see Configuration below.
3. Define the exact collision/recovery UX before implementing account linking. **Done** — `FirebaseSocialRepository.linkCredential` throws `credential_already_in_use` on `FirebaseAuthUserCollisionException`; no auto-merge, no silent UID switch. Surfaced via `SocialUiState.errorCode` in `ClanScreen`.
4. Test that linking preserves UID/profile, and that collisions never overwrite either account.
5. Keep account linking separate from company data-consent; linking identity does not grant consent to upload focus sessions.

## Configuration

### Android
- `GOOGLE_WEB_CLIENT_ID` (Firebase Authentication > Sign-in method > Google > Web SDK configuration > Web client ID) in gitignored `local.properties` or the `GOOGLE_WEB_CLIENT_ID` env var. Read in `composeApp/build.gradle.kts` into `BuildConfig`. Left unset, `googleSignIn()` no-ops (returns null) instead of crashing.
- Implementation: `AndroidFocusPlatform.googleSignIn()` uses Credential Manager (`GetGoogleIdOption`).

### iOS
- SPM package `GoogleSignIn-iOS` (`iosApp/project.yml`), alongside the existing Firebase packages.
- No separate OAuth client ID is needed in Swift: `GoogleSignInController` reads it from `FirebaseApp.app()?.options.clientID`, itself sourced from the already-configured `iosApp/iosApp/GoogleService-Info.plist`.
- The sign-in redirect still needs the app's reversed-client-ID registered as a URL scheme. This value is configuration-driven, not committed as a literal:
  - `iosApp/Secrets.xcconfig` (committed) sets `GOOGLE_REVERSED_CLIENT_ID` to an inert placeholder and optionally includes `iosApp/Secrets.local.xcconfig` (gitignored).
  - Copy `iosApp/Secrets.local.xcconfig.example` to `iosApp/Secrets.local.xcconfig` and set `GOOGLE_REVERSED_CLIENT_ID` to the `REVERSED_CLIENT_ID` entry from `iosApp/iosApp/GoogleService-Info.plist`, then run `xcodegen generate`.
  - `iosApp/iosApp/Info.plist` references it via `$(GOOGLE_REVERSED_CLIENT_ID)` in a second `CFBundleURLTypes` entry (Xcode substitutes build settings into Info.plist at build time regardless of `GENERATE_INFOPLIST_FILE`).
  - `FlipFocusApp.swift` routes incoming URLs to `GoogleSignInController.handle(url:)` first, falling back to the existing `flipfocus://` invitation-link handling.
- Bridge: `GoogleSignInController` (Swift, implements Kotlin's `GoogleSignInBridge`) registered once via `GoogleSignInBridgeKt.RegisterGoogleSignInBridge` in `FlipFocusApp.init()`, mirroring the `FocusLiveActivityBridge` interop pattern. Cancellation, missing config, and any other failure all resolve to a null token (`IosFocusPlatform.googleSignIn()` returns null); the account then simply stays unlinked and `ClanScreen` shows no change (no crash, no spurious error).
