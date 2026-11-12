# FlipFocus Cloud Functions (deferred — requires Blaze, not used by the app)

**Status: unused.** The shipped app runs on the free Firebase Spark plan and does not require
Cloud Functions; `firebase.json` no longer lists a `functions` codebase, so a plain
`firebase deploy` will not touch this directory. Company-wide aggregate stats are intentionally
disabled in the app (see `PersonalCompanyCard` in `ClanScreen.kt`) until this backend is
deliberately enabled on a paid (Blaze) project.

This source folds private per-user company completions (`companies/{companyId}/completions/*`,
written directly by the client) into public rollups the client *could* read at
`companies/{companyId}/stats/{allTime,last7Days}` if this were deployed and firebase.json/rules
were updated accordingly. See `src/index.ts` for the detailed design notes (idempotency, daily
buckets, rolling window) and `../plans/clan-company-stats-plan.md` for the product context.

## Develop

```sh
npm install
npm run build   # tsc typecheck + compile to lib/
npm test        # pure-logic unit tests (no emulator required)
```

## Rules tests against the Firestore emulator

`src/firestore.rules.test.ts` exercises `../firestore.rules` against a real (local) Firestore
emulator. It auto-skips under a plain `npm test` (no `FIRESTORE_EMULATOR_HOST` set). The
emulator requires **JDK 21+**; on macOS with Homebrew:

```sh
brew install openjdk@21
```

Then, from the repo root:

```sh
JAVA_HOME="$(brew --prefix openjdk@21)" PATH="$(brew --prefix openjdk@21)/bin:$PATH" \
  npx firebase-tools emulators:exec --only firestore --project demo-flipfocus-rules-test \
  "cd functions && FIRESTORE_EMULATOR_HOST=localhost:8080 npx jest firestore.rules.test.ts"
```

This never touches a real Firebase project (`demo-` prefixed project IDs are emulator-only).

## Deploying (manual, explicit — do not automate; not part of the current app)

Requires the Blaze plan on the target Firebase project, plus re-adding a `functions` entry to
`firebase.json` and restoring the `stats`/`private` rules in `../firestore.rules` (removed for
the Spark-only schema). After reviewing the diff:

```sh
firebase deploy --only firestore:rules   # after rules changes
firebase deploy --only functions         # after functions changes
```
